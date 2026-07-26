(ns kototama.component-e2e-test
  "Real compiler → Component → Kototama → Aiueos provider integration.

   This is deliberately opt-in because it needs the Rust micro-TCB executable.
   CI enables it after building that executable; ordinary Clojure unit tests
   retain their fast, host-independent path."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.abi.contract :as abi]
            [kotoba.compiler.core :as compiler]
            [kototama.aiueos-adapter :as adapter]
            [kototama.wasmtime-component :as wasmtime]
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

(defn- pure-v2-world [bytes]
  {:target abi/component-target-v2 :wasi-version abi/wasi-version :profile :sync
   :imports #{} :exports #{:app/main} :grants #{} :provider-bindings {}
   :abilities {} :runtime-bindings {} :ambient-wasi abi/ambient-wasi?
   :budgets {:fuel 100000 :memory-pages 4 :deadline-ms 10000}
   :identity {:component-cid (mf/cidv1-raw bytes)
              :package-lock-cid (mf/cidv1-raw (.getBytes "v2-e2e-lock" "UTF-8"))
              :definition-cids #{(mf/cidv1-raw (.getBytes "v2-e2e-definition" "UTF-8"))}}})

(defn- effectful-v3-world [bytes host-sha256]
  (assoc (pure-v2-world bytes)
         :runtime-bindings {:component-host-sha256 host-sha256}))

(deftest compiler-component-v2-pure-round-trip
  (let [artifact (compiler/compile-source "(defn main [] 42)" abi/component-target-v2)]
    (is (= {:result 42 :runtime :wasmtime-component}
           (wasmtime/admit-and-run-provider-free!
            (pure-v2-world (:bytes artifact)) (:bytes artifact))))))

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

(deftest ^:integration compiler-component-v3-typed-clock-round-trip
  (if-let [host-path (System/getenv "KOTOTAMA_COMPONENT_HOST")]
    (let [host (File. host-path)
          ability {:target "clock://monotonic" :operation :clock/now
                   :max-bytes 64 :max-items 1 :deadline-ms 1000
                   :audit-id "component-v3-e2e"}
          artifact (compiler/compile-source
                    "(ns app (:capabilities #{:clock/now})) (defn main [] (cap-call :clock/now 0))"
                    abi/component-target-v2
                    {:allow #{[:cap/call 7]} :component-abilities {7 ability}})
          seen (atom nil)
          audited (atom nil)
          providers {:aiueos.component/aiueos-clock-now
                     (fn [{:keys [payload] :as request}]
                       (reset! seen request)
                       (is (nil? payload))
                       4242)}
          host-sha256 (sha256-file host)
          outcome (adapter/admit-and-run-component-with-aiueos!
                   artifact (effectful-v3-world (:bytes artifact) host-sha256)
                   (:bytes artifact) providers
                   {:runtime :wasmtime-component
                    :component-host (.getAbsolutePath host)
                    :audit-sink (fn [record]
                                  (reset! audited record)
                                  "persisted-component-v3-e2e")})]
      (is (= "aiueos:capability/application@0.3.0" (:component-world artifact)))
      (is (= {:result 4242 :runtime :wasmtime-component} outcome))
      (is (= ability (:ability @seen)))
      (is (= "component-v3-e2e" (:audit-id @audited)))
      (testing "typed success is denied without a persisted audit receipt"
        (is (thrown?
             java.util.concurrent.ExecutionException
             (adapter/admit-and-run-component-with-aiueos!
              artifact (effectful-v3-world (:bytes artifact) host-sha256)
              (:bytes artifact) providers
              {:runtime :wasmtime-component
               :component-host (.getAbsolutePath host)
               :audit-sink (constantly nil)})))))
    (is true "KOTOTAMA_COMPONENT_HOST is not set; native integration test is CI-gated")))

(deftest ^:integration compiler-component-v3-all-typed-operations-round-trip
  (if-let [host-path (System/getenv "KOTOTAMA_COMPONENT_HOST")]
    (let [host (File. host-path)
          host-sha256 (sha256-file host)
          cases
          [{:id 2 :operation :identity/verify :import :aiueos.component/aiueos-identity-verify
            :provider-result true :expected 1}
           {:id 1 :operation :identity/sign :import :aiueos.component/aiueos-identity-sign
            :provider-result {:bytes [11 0 0 0 0 0 0 0]} :expected 11}
           {:id 3 :operation :hash/sha256 :import :aiueos.component/aiueos-hash-sha256
            :provider-result {:bytes [33 0 0 0 0 0 0 0]} :expected 33}
           {:id 4 :operation :http/post :import :aiueos.component/aiueos-http-post
            :provider-result {:status 201 :headers [] :body []} :expected 201}
           {:id 5 :operation :log/read :import :aiueos.component/aiueos-log-read
            :provider-result {:next-cursor 43 :bytes []} :expected 43}
           {:id 6 :operation :log/append :import :aiueos.component/aiueos-log-append
            :provider-result nil :expected 0}
           {:id 7 :operation :clock/now :import :aiueos.component/aiueos-clock-now
            :provider-result 44 :expected 44}]]
      (doseq [{:keys [id operation import provider-result expected]} cases]
        (testing (str operation)
          (let [ability {:target (str "test://" (name operation))
                         :operation operation :max-bytes 256 :max-items 1
                         :deadline-ms 1000 :audit-id (str "component-v3-all-" id)}
                source (format
                        "(ns app (:capabilities #{:%s})) (defn main [] (cap-call :%s 42))"
                        (subs (str operation) 1) (subs (str operation) 1))
                artifact (compiler/compile-source
                          source abi/component-target-v2
                          {:allow #{[:cap/call id]}
                           :component-abilities {id ability}})
                seen (atom nil)
                outcome
                (adapter/admit-and-run-component-with-aiueos!
                 artifact (effectful-v3-world (:bytes artifact) host-sha256)
                 (:bytes artifact)
                 {import (fn [{:keys [payload]}]
                           (reset! seen payload)
                           provider-result)}
                 {:runtime :wasmtime-component
                  :component-host (.getAbsolutePath host)
                  :policy-overlay
                  {:aiueos/kernel-caps
                   #{(get adapter/kototama-import->aiueos-capability
                          (get adapter/component-import->kototama-import import))}}
                  :audit-sink (constantly (str "persisted-v3-" id))})]
            (is (= {:result expected :runtime :wasmtime-component} outcome))
            (cond
              (contains? #{1 2 3 6} id)
              (is (= [42 0 0 0 0 0 0 0] (:bytes @seen)))
              (= 4 id)
              (is (= {:path "" :headers [] :body [42 0 0 0 0 0 0 0]} @seen))
              (= 5 id)
              (is (= {:cursor 42 :max-bytes 8} @seen))
              (= 7 id)
              (is (nil? @seen)))))))
    (is true "KOTOTAMA_COMPONENT_HOST is not set; native integration test is CI-gated")))
