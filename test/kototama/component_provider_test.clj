(ns kototama.component-provider-test
  (:require [clojure.test :refer [deftest is]]
            [kototama.component-provider :as provider]))

(deftest provider-contract-keeps-policy-identical-across-adapters
  (let [request {:runtime :wasmtime-component :component? true
                 :artifact {:capabilities #{:aiueos.component/aiueos-clock-now}}
                 :grants #{:aiueos.component/aiueos-clock-now}
                 :providers {:aiueos.component/aiueos-clock-now :clock-provider}}]
    (is (= request (provider/prepare! request)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (provider/prepare! (assoc request :runtime :workerd-core))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (provider/prepare! (assoc request :grants #{}))))))
