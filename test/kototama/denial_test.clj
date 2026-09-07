(ns kototama.denial-test
  "The one denial shape (koto-h6) as a vocabulary: every registered reason
  has a message on every host, and an unregistered reason is refused rather
  than minted."
  (:require [clojure.test :refer [deftest is testing]]
            [kototama.denial :as denial]))

(deftest every-registered-reason-has-a-message-on-every-host
  (is (pos? (count denial/reasons)))
  (doseq [host (keys denial/hosts)
          reason (keys denial/reasons)]
    (let [d (denial/denial host reason :some-value
                           {:kototama.tender/fuel-scope :instance
                            :kototama.tender/calls 1})
          msg (denial/message host reason :some-value d)]
      (is (denial/denial? d) (str host " " reason))
      (is (= denial/shape-keys (set (filter denial/shape-keys (keys d)))))
      (is (= reason (:kototama.tender/reason d) (:kototama.tender/problem d)))
      (is (= :some-value (:kototama.tender/value d)))
      (is (= host (:kototama.tender/host d)))
      (is (.startsWith ^String msg (str (get denial/hosts host) ": "))
          (str "message carries the host prefix: " msg))
      (is (> (count msg) (+ 2 (count (get denial/hosts host))))
          "and says something after it"))))

(deftest an-unregistered-reason-is-a-caller-bug-not-a-denial
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unregistered reason"
                        (denial/denial :jvm :made-up/reason 1)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown host"
                        (denial/denial :toaster :fuel-exhausted 1))))

(deftest denial?-discriminates-the-shape
  (is (false? (denial/denial? {:kototama.tender/problem :fuel-exhausted}))
      "one of the four old shapes, alone, is not the shape")
  (is (false? (denial/denial? {:kototama.tender/problem :fuel-exhausted
                                :kototama.tender/reason :invalid-deadline
                                :kototama.tender/value 1
                                :kototama.tender/host :jvm}))
      "the two names must agree")
  (is (true? (denial/denial? (denial/denial :browser :host-surface-rejected [:x])))))

(deftest deny!-throws-the-shape-with-the-message-and-cause
  (let [cause (RuntimeException. "boom")
        e (try (denial/deny! :jvm :deadline-exceeded 30 {:extra 1} cause)
               nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e))
    (is (= "kototama.tender: wall-clock deadline exceeded" (.getMessage e)))
    (is (identical? cause (.getCause e)))
    (is (= 1 (:extra (ex-data e))))
    (is (denial/denial? (ex-data e)))))
