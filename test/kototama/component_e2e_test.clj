(ns kototama.component-e2e-test
  "Real compiler → Component → Kototama → Aiueos provider integration.

   This is deliberately opt-in because it needs the Rust micro-TCB executable.
   CI enables it after building that executable; ordinary Clojure unit tests
   retain their fast, host-independent path."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.abi.contract :as abi]
            [kotoba.compiler.core :as compiler]
            [kototama.aiueos-adapter :as adapter]
            [multiformats.core :as mf])
  (:import [java.io File]
           [java.security MessageDigest]))

(defn- sha256-file [^File file]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [input (java.io.FileInputStream. file)]
      (let [buffer (byte-array 8192)]
        (loop [n (.read input buffer)]
          (when (pos? n)
            (.update digest buffer 0 n)
            (recur (.read input buffer))))))
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) (.digest digest)))))

(defn- component-world [bytes]
  {:target abi/component-target :wasi-version abi/wasi-version :profile :sync
   :imports #{} :exports #{:app/main} :grants #{} :provider-bindings {}
   :abilities {} :ambient-wasi abi/ambient-wasi?
   :budgets {:fuel 100000 :memory-pages 4 :deadline-ms 10000}
   :identity {:component-cid (mf/cidv1-raw bytes)
              :package-lock-cid (mf/cidv1-raw (.getBytes "e2e-lock" "UTF-8"))
              :definition-cids #{(mf/cidv1-raw (.getBytes "e2e-definition" "UTF-8"))}}})

(deftest ^:integration compiler-component-aiueos-provider-round-trip
  (if-let [host-path (System/getenv "KOTOTAMA_COMPONENT_HOST")]
    (let [host (File. host-path)
          ability {:target "clock://monotonic" :operation :clock/now
                   :max-bytes 1 :max-items 1 :deadline-ms 1000 :audit-id "component-e2e"}
          artifact (compiler/compile-source
                    "(ns app (:capabilities #{:clock/now})) (defn main [] (cap-call :clock/now 7))"
                    abi/component-target
                    {:allow #{[:cap/call 7]} :component-abilities {7 ability}})
          seen (atom nil)
          providers {:aiueos.component/aiueos-clock-now
                     (fn [{:keys [payload] :as request}]
                       (reset! seen request)
                       (+ 100 (:value payload)))}
          outcome (adapter/admit-and-run-component-with-aiueos!
                   artifact (component-world (:bytes artifact)) (:bytes artifact) providers
                   {:runtime :wasmtime-component
                    :component-host (.getAbsolutePath host)
                    :component-host-sha256 (sha256-file host)
                    :lease-epoch 1
                    :lease-ttl-ms 10000
                    :lease-id "component-e2e-lease"})]
      (testing "only the admitted named WIT import crosses the native host"
        (is (= {:result 107 :runtime :wasmtime-component} outcome))
        (is (= {:import :aiueos.component/aiueos-clock-now
                :ability ability :payload {:value 7}}
               @seen))))
    ;; The executable is a deliberately separate native TCB.  Unit-test
    ;; invocations do not build it implicitly; CI sets this variable after
    ;; `cargo build --locked` and therefore cannot skip the integration path.
    (is true "KOTOTAMA_COMPONENT_HOST is not set; native integration test is CI-gated")))
