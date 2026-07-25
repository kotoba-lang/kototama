(ns kototama.component-provider
  "Runtime-neutral Component provider contract.

   Kototama owns this contract and grant enforcement; Wasmtime, Chicory-core
   compatibility, and workerd are engines/adapters, not competing policy
   implementations."
  (:require [clojure.set :as set]))

(def supported-runtimes #{:wasmtime-component :chicory-core-compat :workerd-core})

(def ^:private ability-keys
  #{:target :operation :max-bytes :max-items :deadline-ms :audit-id})

(defn- valid-ability? [ability]
  (and (map? ability)
       (= ability-keys (set (keys ability)))
       (string? (:target ability)) (seq (:target ability))
       (keyword? (:operation ability))
       (string? (:audit-id ability)) (seq (:audit-id ability))
       (every? #(pos-int? (get ability %))
               [:max-bytes :max-items :deadline-ms])))

(defn- provider-invoke [provider]
  (cond
    (ifn? provider) provider
    (and (map? provider) (ifn? (:invoke provider))) (:invoke provider)
    :else nil))

(defn prepare!
  "Validate the runtime-independent hand-off before any engine adapter is
   selected. Providers are opaque host callbacks/handles, but their keys must
   exactly equal the granted WIT imports. Core-Wasm-only adapters are never
   allowed to claim a Component binary."
  [{:keys [runtime artifact grants providers component?] :as request}]
  (when-not (contains? supported-runtimes runtime)
    (throw (ex-info "unsupported Component runtime" {:phase :component-provider :runtime runtime})))
  (let [declared (set (:capabilities artifact))
        granted (set grants)
        abilities (:component-imports artifact)]
    (when-not (= declared granted)
      (throw (ex-info "Component capabilities and grants differ"
                      {:phase :component-provider :declared declared :grants granted})))
    (when-not (= declared (set (keys providers)))
      (throw (ex-info "Component providers must exactly match grants"
                      {:phase :component-provider :declared declared
                       :providers (set (keys providers))})))
    ;; The artifact descriptor is the data which the native adapter must
    ;; independently enforce.  A capability name alone is not authority.
    (when-not (and (map? abilities)
                   (= declared (set (keys abilities)))
                   (every? valid-ability? (vals abilities)))
      (throw (ex-info "Component providers require complete scoped abilities"
                      {:phase :component-provider :declared declared})))
    (when-not (every? provider-invoke (vals providers))
      (throw (ex-info "Component provider must expose an invocation adapter"
                      {:phase :component-provider
                       :providers (set (keys providers))})))
    (when (and component? (not= runtime :wasmtime-component))
      (throw (ex-info "selected runtime cannot instantiate a standard Component"
                      {:phase :component-provider :runtime runtime})))
    request))

(defn invoke!
  "Invoke a native provider through the already-admitted capability boundary.

   The descriptor is supplied by the signed Component artifact, not by the
   guest.  Consequently a guest cannot widen a target, operation, quota,
   deadline, or audit identity by passing a larger request at call time.
   Engine-specific Wasmtime bindings must call this function (or enforce an
   equivalent check inside the native micro-TCB) for each imported WIT call."
  [{:keys [artifact providers]} import payload]
  (let [ability (get (:component-imports artifact) import)
        invoke (provider-invoke (get providers import))]
    (when-not (and ability invoke)
      (throw (ex-info "Component import is not admitted for invocation"
                      {:phase :component-provider :import import})))
    ;; Payload is deliberately the only guest-controlled value.  The
    ;; capability descriptor remains immutable and exact at the host edge.
    (invoke {:import import :ability ability :payload payload})))
