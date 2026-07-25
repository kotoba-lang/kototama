(ns kototama.component-authority-test
  (:require [clojure.test :refer [deftest is]]
            [kototama.component-authority :as authority]))

(def cid "bafycomponentauthority")
(def trust {:authenticated-issuer "did:key:murakumo"
            :trusted-issuer "did:key:murakumo"})

(defn event [kind epoch sequence node]
  {:murakumo.component/version 1
   :murakumo.component/event kind
   :murakumo.component/component-cid cid
   :murakumo.component/epoch epoch
   :murakumo.component/sequence sequence
   :murakumo.component/node node})

(deftest authenticated-events-drive-a-live-monotonic-epoch
  (let [state (atom (authority/initial-state))
        source (:lease-epoch-source (authority/admission-options state cid))]
    (authority/apply-event! state (event :placed 1 1 "edge-a") trust)
    (is (= 1 (source)))
    (authority/apply-event! state (event :placed 1 2 "edge-b") trust)
    (is (= 1 (source)))
    (authority/apply-event! state (event :revoked 2 3 nil) trust)
    (is (= 2 (source)))))

(deftest replay-stale-epoch-and-untrusted-issuer-fail-closed
  (let [state (atom (authority/initial-state))]
    (authority/apply-event! state (event :placed 2 5 "edge-a") trust)
    (is (thrown? clojure.lang.ExceptionInfo
                 (authority/apply-event! state (event :placed 2 5 "edge-a") trust)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (authority/apply-event! state (event :placed 1 6 "edge-a") trust)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (authority/apply-event!
                  state (event :revoked 3 7 nil)
                  {:authenticated-issuer "did:key:attacker"
                   :trusted-issuer "did:key:murakumo"})))))

(deftest missing-authority-never-defaults-to-epoch-one
  (is (thrown? clojure.lang.ExceptionInfo
               ((authority/epoch-source
                 (atom (authority/initial-state)) "bafymissing")))))
