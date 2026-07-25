(ns kototama.component-provider
  "Runtime-neutral Component provider contract.

   Kototama owns this contract and grant enforcement; Wasmtime, Chicory-core
   compatibility, and workerd are engines/adapters, not competing policy
   implementations."
  (:require [clojure.set :as set]))

(def supported-runtimes #{:wasmtime-component :chicory-core-compat :workerd-core})

(defn prepare!
  "Validate the runtime-independent hand-off before any engine adapter is
   selected. Providers are opaque host callbacks/handles, but their keys must
   exactly equal the granted WIT imports. Core-Wasm-only adapters are never
   allowed to claim a Component binary."
  [{:keys [runtime artifact grants providers component?] :as request}]
  (when-not (contains? supported-runtimes runtime)
    (throw (ex-info "unsupported Component runtime" {:phase :component-provider :runtime runtime})))
  (let [declared (set (:capabilities artifact))
        granted (set grants)]
    (when-not (= declared granted)
      (throw (ex-info "Component capabilities and grants differ"
                      {:phase :component-provider :declared declared :grants granted})))
    (when-not (= declared (set (keys providers)))
      (throw (ex-info "Component providers must exactly match grants"
                      {:phase :component-provider :declared declared
                       :providers (set (keys providers))})))
    (when (and component? (not= runtime :wasmtime-component))
      (throw (ex-info "selected runtime cannot instantiate a standard Component"
                      {:phase :component-provider :runtime runtime})))
    request))
