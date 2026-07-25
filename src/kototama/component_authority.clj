(ns kototama.component-authority
  "Fail-closed consumer for Murakumo Component placement epochs.

  Transport authentication happens before this boundary and supplies the
  authenticated issuer identity. This namespace validates the shared wire ABI,
  trusted issuer, replay order, and monotonic epoch before updating the live
  source consulted at every provider call."
  (:require [kotoba.abi.contract :as abi]))

(defn initial-state []
  {:epochs {} :sequences {}})

(defn- reject [reason message data]
  (throw (ex-info message (assoc data :kototama.component-authority/reason reason))))

(defn apply-event!
  "Consume one transport-authenticated Murakumo event.

  Sequence is tracked per issuer because one host can subscribe to independent
  control planes. Events may skip sequence numbers (unrelated Components), but
  may never repeat or move backwards. Revocation must strictly advance epoch;
  placement can add a replica at the current epoch."
  [state-atom event {:keys [authenticated-issuer trusted-issuer]}]
  (when-not (and (string? authenticated-issuer)
                 (= trusted-issuer authenticated-issuer))
    (reject :untrusted-issuer "Component authority event issuer is not trusted"
            {:authenticated-issuer authenticated-issuer}))
  (when-not (abi/valid-component-authority-event? event)
    (reject :invalid-event "Component authority event does not match the shared ABI"
            {:event event}))
  (let [cid (:murakumo.component/component-cid event)
        epoch (:murakumo.component/epoch event)
        sequence (:murakumo.component/sequence event)
        kind (:murakumo.component/event event)]
    (swap! state-atom
           (fn [state]
             (let [previous-sequence (get-in state [:sequences authenticated-issuer] 0)
                   previous-epoch (get-in state [:epochs cid] 0)]
               (when-not (> sequence previous-sequence)
                 (reject :replayed-event "Component authority event is replayed or out of order"
                         {:sequence sequence :previous-sequence previous-sequence}))
               (when-not (if (= :revoked kind)
                           (> epoch previous-epoch)
                           (>= epoch previous-epoch))
                 (reject :stale-epoch "Component authority epoch moved backwards"
                         {:epoch epoch :previous-epoch previous-epoch :event kind}))
               (-> state
                   (assoc-in [:sequences authenticated-issuer] sequence)
                   (assoc-in [:epochs cid] epoch)))))
    event))

(defn epoch-source
  "Return the live callable used by aiueos-adapter provider authorization."
  [state-atom component-cid]
  (fn []
    (or (get-in @state-atom [:epochs component-cid])
        (reject :missing-authority "Component has no authenticated authority epoch"
                {:component-cid component-cid}))))

(defn admission-options
  "Build the explicit options fragment for
  `aiueos-adapter/admit-component-with-aiueos!`."
  [state-atom component-cid]
  {:lease-epoch-source (epoch-source state-atom component-cid)})
