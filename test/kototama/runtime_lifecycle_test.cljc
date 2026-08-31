(ns kototama.runtime-lifecycle-test
  (:require [clojure.test :refer [deftest is testing]]
            [kototama.runtime-lifecycle :as lifecycle]))

(def bundle
  {:bundle-id "bafk-runtime"
   :component-cid "bafk-component"
   :guest-cid "bafk-guest"
   :model-cid "bafk-model"
   :runtime-abi 1})
(def probes {:runtime-admission true :model-bind true :murakumo-result true})

(defn ready-runtime []
  (-> (lifecycle/initial-state)
      (lifecycle/stage bundle true)
      lifecycle/start-candidate
      (lifecycle/mark-ready probes)))

(deftest signed-bundle-walks-blue-green-lifecycle
  (let [ready (ready-runtime)]
    (is (= :ready (:state ready)))
    (is (= bundle (:bundle ready)))
    (is (= 1 (:epoch ready)))
    (is (false? (:kernel-reboot? ready)))))

(deftest inference-failure-and-restart-never-reboot-aiueos
  (let [ready (ready-runtime)
        failed (-> ready lifecycle/begin-invocation
                   (lifecycle/invocation-failed :softmax-nonfinite))
        restarted (lifecycle/restart failed)]
    (is (= :degraded (:state failed)))
    (is (= :ready (:state restarted)))
    (is (= 2 (:epoch restarted)))
    (is (= 1 (:restarts restarted)))
    (is (false? (:kernel-reboot? restarted)))
    (is (= {:schema lifecycle/schema :action :restart-runtime
            :from :degraded :to :ready :bundle-id "bafk-runtime"
            :epoch 2 :restarts 1 :kernel-reboot? false}
           (lifecycle/receipt failed restarted :restart-runtime)))))

(deftest admission-and-health-fail-closed
  (testing "unsigned bytes never become a candidate"
    (is (= :publisher-not-admitted
           (:reason (lifecycle/stage (lifecycle/initial-state)
                                     bundle false)))))
  (testing "a failed model bind degrades only the candidate"
    (let [failed (-> (lifecycle/initial-state)
                     (lifecycle/stage bundle true)
                     lifecycle/start-candidate
                     (lifecycle/mark-ready (assoc probes :model-bind false)))]
      (is (= :degraded (:state failed)))
      (is (false? (:kernel-reboot? failed))))))
