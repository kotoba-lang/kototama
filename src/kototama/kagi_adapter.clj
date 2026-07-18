(ns kototama.kagi-adapter
  "Final execution boundary for kagi secret references.

  aiueos supplies a grant decision; an injected kagi resolver reveals bytes only
  for the duration of with-secret. Kototama never persists or returns them."
  (:require [kagi.key-registry :as key-registry]
            [kagi.native-key :as native-key])
  (:import [java.util Arrays]))

(defprotocol KagiResolver
  (reveal-bytes [resolver secret-ref purpose]))

(defprotocol KagiSigner
  (sign-bytes [signer key-ref purpose message]))

(defn with-secret
  [resolver decision f]
  (when-not (and (= :grant (:decision decision))
                 (= :kagi/reveal (:capability decision))
                 (:secret-ref decision) (:purpose decision))
    (throw (ex-info "kagi reveal denied or incomplete" {:decision (dissoc decision :secret)})))
  (let [secret (reveal-bytes resolver (:secret-ref decision) (:purpose decision))]
    (when-not (bytes? secret)
      (throw (ex-info "kagi resolver must return byte[]" {:type (type secret)})))
    (try
      (f secret)
      (finally
        (Arrays/fill ^bytes secret (byte 0))))))

(defn resolver [reveal-fn]
  (reify KagiResolver
    (reveal-bytes [_ ref purpose] (reveal-fn ref purpose))))

(defn signer [sign-fn]
  (reify KagiSigner
    (sign-bytes [_ ref purpose message] (sign-fn ref purpose message))))

(defn native-kagi-signer
  "Concrete kagi signer backed by a non-exportable JCA/PKCS#11 handle. The
  lifecycle record is checked at every call using caller-supplied trusted time."
  [keystore handle key-ref key-record now-fn]
  (when-not (and keystore handle (seq key-ref) key-record (ifn? now-fn))
    (throw (ex-info "incomplete native kagi signer configuration" {})))
  (signer
   (fn [requested-ref _purpose message]
     (when-not (= key-ref requested-ref)
       (throw (ex-info "native kagi key reference mismatch"
                       {:expected key-ref :actual requested-ref})))
     (key-registry/authorize! key-record :sign (now-fn))
     (native-key/sign-encoded keystore handle message))))

(defn authorized-sign
  "Sign through a non-exportable kagi handle selected by an aiueos decision.
  Neither this function nor the guest receives private-key material."
  [signer decisions key-ref message]
  (let [decision (first (filter #(and (= :grant (:decision %))
                                      (= :kagi/sign (:capability %))
                                      (= key-ref (:secret-ref %))
                                      (:purpose %))
                                decisions))]
    (when-not decision
      (throw (ex-info "kagi signing reference was not authorized"
                      {:key-ref key-ref})))
    (when-not signer
      (throw (ex-info "kagi signer is required" {:key-ref key-ref})))
    (let [signature (sign-bytes signer key-ref (:purpose decision) message)]
      (when-not (bytes? signature)
        (throw (ex-info "kagi signer must return byte[]" {:type (type signature)})))
      signature)))
