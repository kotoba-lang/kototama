(ns kototama.tamaki-contract
  "Independent admission of Tamaki capability envelopes at the Wasm host.

  Tamaki decides desired work; Kototama still revalidates ABI, imports,
  grants, limits, and high-risk effect policy before creating HostCaps."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [kototama.contract :as contract]))

(def envelope-version 1)
(def decisions #{:autonomous :approval-required :voice-required :blocked})

(def detailed-import-effects
  {:gen-keypair #{:crypto :secret}
   :sign #{:crypto :secret}
   :verify #{:crypto}
   :sha256-hex #{:crypto}
   :http-post #{:network-write}
   :http-post-headers #{:network-write}
   :http-fetch #{:network-read}
   :log-read #{:storage-read}
   :log-write #{:storage-write}
   :clock-monotonic #{:clock}
   :llm-infer #{:llm-inference}
   :cbor-encode #{:codec}
   :json-encode #{:codec}
   :json-extract-field #{:codec}})

(defn- effects-for [imports]
  (reduce set/union #{} (map detailed-import-effects imports)))

(defn admit
  "Return {:ok? true :host-caps ...} or a fail-closed error report."
  [envelope]
  (let [imports (set (:tamaki.capability/imports envelope))
        grants (set (:tamaki.capability/grants envelope))
        limits (:tamaki.capability/limits envelope)
        policies (:tamaki.capability/effect-policy envelope)
        abi (:tamaki.capability/abi envelope)
        normalized-imports (set (keep contract/import-id imports))
        normalized-grants (set (keep contract/import-id grants))
        unknown-imports (set/difference imports normalized-imports)
        unknown-grants (set/difference grants normalized-grants)
        effects (effects-for normalized-imports)
        missing-policies (set/difference effects (set (keys policies)))
        invalid-policies (into {}
                               (remove (fn [[_ decision]]
                                         (contains? decisions decision)))
                               policies)
        prefixes (:allowed-url-prefixes limits)
        surface {:abi/namespace (:namespace abi)
                 :abi/version (:version abi)
                 :abi/imports (vec imports)}
        caps (contract/host-caps {:grants grants :limits limits})
        host-report (contract/validate-import-surface surface caps)
        errors
        (cond-> (vec (:errors host-report))
          (not= envelope-version (:tamaki.capability/version envelope))
          (conj {:error :tamaki.capability/version
                 :expected envelope-version
                 :actual (:tamaki.capability/version envelope)})

          (str/blank? (:tamaki.capability/actor envelope))
          (conj {:error :tamaki.capability/actor})

          (not= :kototama-wasm (:tamaki.capability/substrate envelope))
          (conj {:error :tamaki.capability/substrate})

          (seq unknown-imports)
          (conj {:error :imports/unknown :imports unknown-imports})

          (seq unknown-grants)
          (conj {:error :grants/unknown :grants unknown-grants})

          (seq missing-policies)
          (conj {:error :effect-policy/missing
                 :effects missing-policies})

          (seq invalid-policies)
          (conj {:error :effect-policy/invalid
                 :policies invalid-policies})

          (and (seq (set/intersection effects
                                      #{:network-read :network-write}))
               (or (nil? prefixes) (empty? prefixes)))
          (conj {:error :network/allowlist-required})

          (and (contains? effects :network-write)
               (= :autonomous (:network-write policies)))
          (conj {:error :effect-policy/network-write-needs-human})

          (and (contains? effects :secret)
               (= :autonomous (:secret policies)))
          (conj {:error :effect-policy/secret-needs-human}))]
    (cond-> {:ok? (empty? errors)
             :actor (:tamaki.capability/actor envelope)
             :imports normalized-imports
             :grants normalized-grants
             :effects effects
             :errors errors}
      (empty? errors) (assoc :host-caps caps))))

(defn admit!
  [envelope]
  (let [report (admit envelope)]
    (when-not (:ok? report)
      (throw (ex-info "Kototama rejected Tamaki capability envelope"
                      report)))
    report))
