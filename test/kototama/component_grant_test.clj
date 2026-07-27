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

(deftest bounded-stream-and-object-imports-have-individual-authority
  (let [imports #{:aiueos.component/aiueos-http-get-stream
                  :aiueos.component/aiueos-object-get-stream
                  :aiueos.component/aiueos-object-put-block
                  :aiueos.component/aiueos-object-compare-and-set-ref}
        {:keys [host-caps decision]}
        (adapter/host-caps-for-component
         {:capabilities imports}
         {:policy-overlay
          {:aiueos/grants
           {:kototama/guest
            #{:http/get-stream :object/get-stream :object/put-block
              :object/compare-and-set-ref}}}
          :limits {:allow-write-imports? true}})]
    (is (= :grant (:aiueos/decision decision)))
    (is (= #{:http-get-stream :object-get-stream :object-put-block
             :object-compare-and-set-ref}
           (:grants host-caps)))))

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
                  :budgets {:fuel 1 :memory-pages 1}
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

(deftest aiueos-policy-narrowing-reaches-the-provider-boundary
  (let [bytes (.getBytes "component" "UTF-8")
        import :aiueos.component/aiueos-clock-now
        requested {:target "clock://monotonic" :operation :clock/now
                   :max-bytes 64 :max-items 10 :deadline-ms 1000
                   :audit-id "artifact-request"}
        ceiling {:target "clock://monotonic" :operation :clock/now
                 :max-bytes 8 :max-items 2 :deadline-ms 100
                 :audit-id "policy-audit"}
        effective {:target "clock://monotonic" :operation :clock/now
                   :max-bytes 8 :max-items 2 :deadline-ms 100
                   :audit-id "policy-audit"}
        artifact {:capabilities #{import}
                  :budgets {:fuel 1 :memory-pages 1}
                  :component-imports {import requested}}
        world {:target :wasm-component-kotoba-v1 :wasi-version "0.3.0"
               :profile :sync :exports #{:app/run} :ambient-wasi false
               :budgets {:fuel 1 :memory-pages 1}
               :identity {:component-cid (mf/cidv1-raw bytes)
                          :package-lock-cid (mf/cidv1-raw (.getBytes "lock" "UTF-8"))
                          :definition-cids #{(mf/cidv1-raw (.getBytes "def" "UTF-8"))}}}
        admitted (atom nil)
        outcome
        (adapter/admit-component-with-aiueos!
         artifact world bytes
         (fn [request]
           (reset! admitted request)
           {:result 7})
         {import :clock-provider}
         {:runtime :wasmtime-component
          :component-host-sha256 (apply str (repeat 64 "a"))
          :ability-policy {import ceiling}
          :lease-epoch 7 :now-ms (constantly 1000)
          :lease-ttl-ms 10000})]
    (is (= {import effective} (:abilities @admitted)))
    (is (= {import effective} (:component-imports (:artifact @admitted))))
    (is (= {import effective} (get-in @admitted [:lease :aiueos/abilities])))
    (is (= {import effective} (get-in outcome [:receipt :abilities])))
    (is (true? ((:lease-authorize? @admitted) import effective)))
    (is (false? ((:lease-authorize? @admitted) import requested)))))

(deftest invalid-live-epoch-source-fails-closed
  (is (thrown? clojure.lang.ExceptionInfo
                 (#'adapter/current-lease-epoch! (constantly 0))))
  (is (thrown? clojure.lang.ExceptionInfo
                 (#'adapter/current-lease-epoch!
                  #(throw (ex-info "control plane unavailable" {}))))))
