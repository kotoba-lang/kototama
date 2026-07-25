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
        artifact {:capabilities #{:aiueos.component/aiueos-clock-now}}
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
