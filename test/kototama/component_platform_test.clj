(ns kototama.component-platform-test
  (:require [clojure.test :refer [deftest is testing]]
            [kototama.component-platform :as platform]))

(def valid
  {:target :wasm-component-kotoba-v1 :wasi-version "0.3.0" :profile :sync
   :imports #{:kotoba/http-post} :exports #{:app/run}
   :grants #{:kotoba/http-post}
   :provider-bindings {:kotoba/http-post :provider/http}
   :ambient-wasi false :budgets {}})

(defn code [value]
  (try (platform/validate-world! value) nil
       (catch clojure.lang.ExceptionInfo e (:kototama.component/code (ex-data e)))))

(deftest component-world-admission-is-closed
  (is (= valid (platform/validate-world! valid)))
  (is (= :invalid-envelope (code (assoc valid :invented true))))
  (is (= :wasi-mismatch (code (assoc valid :wasi-version "0.2.11"))))
  (is (= :ambient-authority (code (assoc valid :ambient-wasi true))))
  (is (= :capability-denied (code (assoc valid :grants #{}))))
  (is (= :unbound-import (code (assoc valid :provider-bindings {})))))

(deftest async-world-requires-cancellation-and-bounds
  (testing "WASI 0.3 does not imply unbounded async authority"
    (is (= :invalid-budgets (code (assoc valid :profile :async))))
    (let [async (assoc valid :profile :async
                       :budgets {:cancellation true :deadline-ms 1000
                                 :max-items 32 :max-bytes 65536})]
      (is (= async (platform/validate-world! async))))))
