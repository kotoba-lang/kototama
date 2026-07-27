(ns kototama.kagi-adapter-test
  (:require [clojure.test :refer [deftest is]]
            [kototama.kagi-adapter :as adapter]
            [kagi.key-registry :as key-registry]
            [kagi.native-key :as native-key])
  (:import [java.security KeyStore]))

(deftest secret-is-resolved-only-at-final-boundary-and-zeroized
  (let [captured (atom nil)
        resolver (adapter/resolver (fn [_ _] (.getBytes "secret" "UTF-8")))
        result (adapter/with-secret resolver
                 {:decision :grant :capability :kagi/reveal
                  :secret-ref "kagi://work/github" :purpose :deploy}
                 (fn [b] (reset! captured b) (count b)))]
    (is (= 6 result))
    (is (every? zero? @captured))
    (is (thrown? Exception
                 (adapter/with-secret resolver {:decision :deny} identity)))))

(deftest signing-uses-exact-authorized-reference-without-a-private-key
  (let [seen (atom nil)
        signer (adapter/signer
                (fn [ref purpose message]
                  (reset! seen [ref purpose (String. ^bytes message "UTF-8")])
                  (.getBytes "hybrid-signature" "UTF-8")))
        decisions [{:decision :grant :capability :kagi/sign
                    :secret-ref "kagi://ops/key" :purpose :artifact-signing}]
        sig (adapter/authorized-sign signer decisions "kagi://ops/key"
                                     (.getBytes "payload" "UTF-8"))]
    (is (= "hybrid-signature" (String. ^bytes sig "UTF-8")))
    (is (= ["kagi://ops/key" :artifact-signing "payload"] @seen))
    (is (thrown? clojure.lang.ExceptionInfo
                 (adapter/authorized-sign signer decisions "kagi://ops/other"
                                          (.getBytes "payload" "UTF-8"))))))

(deftest native-kagi-signer-enforces-lifecycle-before-token-use
  (let [ks (doto (KeyStore/getInstance "JCEKS") (.load nil nil))
        handle (native-key/handle "test" "missing-ed" "missing-pq")
        active (key-registry/transition
                (key-registry/key-record
                 {:id "key-1" :purpose :identity-signing :suite :authority-v1 :epoch 1
                  :created-at "2026-01-01T00:00:00Z"
                  :not-before "2026-01-01T00:00:00Z"
                  :originator-not-after "2026-02-01T00:00:00Z"
                  :custody-ref "pkcs11://test/key-1"})
                :active "2026-01-01T00:00:00Z")
        signer (adapter/native-kagi-signer ks handle "kagi://ops/key" active
                                           (constantly "2026-03-01T00:00:00Z"))
        decisions [{:decision :grant :capability :kagi/sign
                    :secret-ref "kagi://ops/key" :purpose :artifact-signing}]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"lifecycle denied"
                          (adapter/authorized-sign signer decisions "kagi://ops/key"
                                                   (.getBytes "payload" "UTF-8"))))))
