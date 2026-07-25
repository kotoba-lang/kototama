(ns kototama.component-provider-test
  (:require [clojure.test :refer [deftest is]]
            [aiueos.component-abi :as component-abi]
            [kototama.component-provider :as provider]))

(deftest provider-contract-keeps-policy-identical-across-adapters
  (let [request {:runtime :wasmtime-component :component? true
                 :grants #{:aiueos.component/aiueos-clock-now}
                 :providers {:aiueos.component/aiueos-clock-now (fn [_] :clock)}
                 :artifact {:capabilities #{:aiueos.component/aiueos-clock-now}
                            :component-imports
                            {:aiueos.component/aiueos-clock-now
                             {:target "clock://monotonic" :operation :clock/now
                              :max-bytes 1 :max-items 1 :deadline-ms 10
                              :audit-id "component-test"}}}}]
    (is (= request (provider/prepare! request)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (provider/prepare! (assoc request :runtime :workerd-core))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (provider/prepare! (assoc request :grants #{}))))))

(deftest provider-invocation-keeps-the-ability-out-of-guest-control
  (let [seen (atom nil)
        prepared (provider/prepare!
                  {:runtime :wasmtime-component :component? true
                   :artifact {:capabilities #{:aiueos.component/aiueos-clock-now}
                              :component-imports
                              {:aiueos.component/aiueos-clock-now
                               {:target "clock://monotonic" :operation :clock/now
                                :max-bytes 1 :max-items 1 :deadline-ms 10
                                :audit-id "component-test"}}}
                   :grants #{:aiueos.component/aiueos-clock-now}
                   :providers
                   {:aiueos.component/aiueos-clock-now #(reset! seen %) }})]
    (provider/invoke! prepared :aiueos.component/aiueos-clock-now {:value 42})
    (is (= {:import :aiueos.component/aiueos-clock-now
            :ability {:target "clock://monotonic" :operation :clock/now
                      :max-bytes 1 :max-items 1 :deadline-ms 10
                      :audit-id "component-test"}
            :payload {:value 42}}
           @seen))
    (is (thrown? clojure.lang.ExceptionInfo
                 (provider/invoke! prepared :aiueos.component/unknown {})))))

(deftest provider-rechecks-an-aiueos-lease-on-every-component-call
  (let [import :aiueos.component/aiueos-clock-now
        ability {:target "clock://monotonic" :operation :clock/now
                 :max-bytes 1 :max-items 1 :deadline-ms 10 :audit-id "revocation-test"}
        lease (component-abi/issue-lease
               {:decision {:aiueos/decision :grant :aiueos/capabilities #{:clock/monotonic}}
                :imports #{import} :abilities {import ability}
                :now 100 :epoch 7 :ttl-ms 10 :lease-id "revocation-test"})
        prepared (provider/prepare!
                  {:runtime :wasmtime-component :component? true
                   :artifact {:capabilities #{import} :component-imports {import ability}}
                   :grants #{import} :providers {import (constantly 42)}
                   :lease lease :lease-epoch (constantly 8) :now-ms (constantly 105)})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"lease is expired, revoked"
                          #(provider/invoke! prepared import {:value 0})))))
