(ns kototama.component-grant-test
  (:require [clojure.test :refer [deftest is]]
            [kototama.aiueos-adapter :as adapter]
            [multiformats.core :as mf]))

(deftest component-grants-are-translated-only-through-the-closed-wit-map
  (let [{:keys [host-caps decision]}
        (adapter/host-caps-for-component {:capabilities #{:aiueos.component/aiueos-clock-now}})]
    (is (= :grant (:aiueos/decision decision)))
    (is (= #{:clock-monotonic} (:grants host-caps))))
  (is (thrown? clojure.lang.ExceptionInfo
               (adapter/host-caps-for-component {:capabilities #{:aiueos.component/unknown}}))))

(deftest denied-or-unbound-component-never-reaches-linker
  (let [bytes (.getBytes "component" "UTF-8")
        artifact {:capabilities #{:aiueos.component/aiueos-clock-now}
                  :component-imports
                  {:aiueos.component/aiueos-clock-now
                   {:target "clock://monotonic" :operation :clock/now
                    :max-bytes 1 :max-items 1 :deadline-ms 10 :audit-id "test"}}}
        world {:target :wasm-component-kotoba-v1 :wasi-version "0.3.0" :profile :sync
               :exports #{:app/run} :ambient-wasi false :budgets {:fuel 1 :memory-pages 1}
               :identity {:component-cid (mf/cidv1-raw bytes)
                          :package-lock-cid (mf/cidv1-raw (.getBytes "lock" "UTF-8"))
                          :definition-cids #{(mf/cidv1-raw (.getBytes "def" "UTF-8"))}}}
        linked (atom false)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (adapter/admit-component-with-aiueos!
                  artifact world bytes #(reset! linked true) {} {})))
    (is (false? @linked))))

(deftest component-adapter-must-be-explicitly-qualified-before-linking
  (let [bytes (.getBytes "component" "UTF-8")
        artifact {:capabilities #{:aiueos.component/aiueos-clock-now}
                  :component-imports
                  {:aiueos.component/aiueos-clock-now
                   {:target "clock://monotonic" :operation :clock/now
                    :max-bytes 1 :max-items 1 :deadline-ms 10 :audit-id "test"}}}
        world {:target :wasm-component-kotoba-v1 :wasi-version "0.3.0" :profile :sync
               :exports #{:app/run} :ambient-wasi false :budgets {:fuel 1 :memory-pages 1}
               :identity {:component-cid (mf/cidv1-raw bytes)
                          :package-lock-cid (mf/cidv1-raw (.getBytes "lock" "UTF-8"))
                          :definition-cids #{(mf/cidv1-raw (.getBytes "def" "UTF-8"))}}
               :abilities {}}
        providers {:aiueos.component/aiueos-clock-now :clock-provider}
        linked (atom false)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (adapter/admit-component-with-aiueos!
                  artifact world bytes #(reset! linked true) providers {:runtime :chicory-core-compat})))
    (is (false? @linked))))

(deftest provider-authorization-observes-live-control-plane-epoch
  (let [bytes (.getBytes "component" "UTF-8")
        import :aiueos.component/aiueos-clock-now
        ability {:target "clock://monotonic" :operation :clock/now
                 :max-bytes 1 :max-items 1 :deadline-ms 10 :audit-id "epoch-test"}
        artifact {:capabilities #{import}
                  :component-imports {import ability}}
        world {:target :wasm-component-kotoba-v1 :wasi-version "0.3.0" :profile :sync
               :exports #{:app/run} :ambient-wasi false
               :budgets {:fuel 1 :memory-pages 1}
               :identity {:component-cid (mf/cidv1-raw bytes)
                          :package-lock-cid (mf/cidv1-raw (.getBytes "lock" "UTF-8"))
                          :definition-cids #{(mf/cidv1-raw (.getBytes "def" "UTF-8"))}}}
        epoch (atom 7)
        admitted (atom nil)]
    (adapter/admit-component-with-aiueos!
     artifact world bytes #(reset! admitted %) {import :clock-provider}
     {:runtime :wasmtime-component
      :component-host-sha256 (apply str (repeat 64 "a"))
      :lease-epoch-source #(deref epoch)
      :now-ms (constantly 1000)
      :lease-ttl-ms 10000})
    (is (= 7 (:lease-epoch @admitted)))
    (is (true? ((:lease-authorize? @admitted) import ability)))
    (reset! epoch 8)
    (is (false? ((:lease-authorize? @admitted) import ability)))))

(deftest invalid-live-epoch-source-fails-closed
  (is (thrown? clojure.lang.ExceptionInfo
                 (#'adapter/current-lease-epoch! (constantly 0))))
  (is (thrown? clojure.lang.ExceptionInfo
                 (#'adapter/current-lease-epoch!
                  #(throw (ex-info "control plane unavailable" {}))))))
