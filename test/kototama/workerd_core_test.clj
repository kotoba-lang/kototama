(ns kototama.workerd-core-test
  (:require [clojure.test :refer [deftest is]]
            [kototama.workerd-core :as workerd]))

(def capability :aiueos.component/aiueos-clock-now)
(def ability {:target "clock://monotonic" :operation :clock/now
              :max-bytes 1 :max-items 1 :deadline-ms 10 :audit-id "workerd-test"})

(deftest workerd-plan-is-core-only-and-explicitly-bound
  (let [seen (atom nil)
        request {:artifact {:format :wasm/v1 :bytes (byte-array [0 97 115 109])
                            :capabilities #{capability} :component-imports {capability ability}}
                 :grants #{capability}
                 :providers {capability #(reset! seen %)}}
        plan (workerd/prepare-core-module! request)]
    (is (= :workerd-core (:runtime plan)))
    (is (false? (:ambient-wasi plan)))
    ((:invoke plan) capability {:value 1})
    (is (= {:import capability :ability ability :payload {:value 1}} @seen))
    (is (thrown? clojure.lang.ExceptionInfo
                 (workerd/prepare-core-module!
                  (assoc-in request [:artifact :format] :wasm-component/v1))))))
