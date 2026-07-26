(ns kototama.component-authority-test
  (:require [clojure.test :refer [deftest is]]
            [ed25519.core :as ed]
            [kotoba.abi.contract :as abi]
            [kototama.component-authority :as authority]))

(def cid "bafycomponentauthority")
(def seed (byte-array (range 32)))
(def now 1785000000000)
(def trust {:trusted-keys
            {"murakumo-2026-01"
             {:issuer "did:key:murakumo"
              :public-key (ed/pubkey-from-seed seed)}}
            :audience "did:key:kototama-edge-a"
            :now-ms (constantly now)})

(defn hex [bytes]
  (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes)))

(defn event [kind epoch sequence node]
  {:murakumo.component/version 1
   :murakumo.component/event kind
   :murakumo.component/component-cid cid
   :murakumo.component/epoch epoch
   :murakumo.component/sequence sequence
   :murakumo.component/node node})

(defn envelope [event]
  (let [unsigned {:format :murakumo.component-authority/v1
                  :algorithm :ed25519
                  :key-id "murakumo-2026-01"
                  :issuer "did:key:murakumo"
                  :audience "did:key:kototama-edge-a"
                  :issued-at-ms now
                  :event event}]
    (assoc unsigned :signature
           (hex (ed/sign
                 seed
                 (.getBytes
                  (abi/component-authority-signing-payload unsigned)
                  "UTF-8"))))))

(deftest authenticated-events-drive-a-live-monotonic-epoch
  (let [state (atom (authority/initial-state))
        source (:lease-epoch-source (authority/admission-options state cid))]
    (authority/apply-envelope! state (envelope (event :placed 1 1 "edge-a")) trust)
    (is (= 1 (source)))
    (authority/apply-envelope! state (envelope (event :placed 1 2 "edge-b")) trust)
    (is (= 1 (source)))
    (authority/apply-envelope! state (envelope (event :revoked 2 3 nil)) trust)
    (is (= 2 (source)))))

(deftest signature-replay-stale-epoch-and-untrusted-audience-fail-closed
  (let [state (atom (authority/initial-state))]
    (authority/apply-envelope! state (envelope (event :placed 2 5 "edge-a")) trust)
    (is (thrown? clojure.lang.ExceptionInfo
                 (authority/apply-envelope!
                  state (envelope (event :placed 2 5 "edge-a")) trust)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (authority/apply-envelope!
                  state (envelope (event :placed 1 6 "edge-a")) trust)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (authority/apply-envelope!
                  state
                  (assoc (envelope (event :revoked 3 7 nil))
                         :audience "did:key:attacker")
                  trust)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (authority/apply-envelope!
                  state
                  (update (envelope (event :revoked 3 7 nil))
                          :event assoc :murakumo.component/epoch 99)
                  trust)))))

(deftest missing-authority-never-defaults-to-epoch-one
  (is (thrown? clojure.lang.ExceptionInfo
               ((authority/epoch-source
                 (atom (authority/initial-state)) "bafymissing")))))
