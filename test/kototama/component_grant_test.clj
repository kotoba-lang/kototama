(ns kototama.component-grant-test
  (:require [clojure.test :refer [deftest is]]
            [kototama.aiueos-adapter :as adapter]))

(deftest component-grants-are-translated-only-through-the-closed-wit-map
  (let [{:keys [host-caps decision]}
        (adapter/host-caps-for-component {:capabilities #{:aiueos.component/aiueos-clock-now}})]
    (is (= :grant (:aiueos/decision decision)))
    (is (= #{:clock-monotonic} (:grants host-caps))))
  (is (thrown? clojure.lang.ExceptionInfo
               (adapter/host-caps-for-component {:capabilities #{:aiueos.component/unknown}}))))
