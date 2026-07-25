(ns kototama.workerd-core
  "Core-Wasm adapter plan for workerd-style WebAssembly hosts.

   workerd's WebAssembly API instantiates Core modules, not arbitrary standard
   Components.  This adapter therefore shares the same explicit grants and
   named-provider invariant, while refusing Component binaries instead of
   silently degrading their authority model.  The returned plan is consumed by
   the JavaScript host integration; it deliberately contains no ambient WASI."
  (:require [kototama.component-provider :as provider]))

(defn prepare-core-module!
  "Return the exact JavaScript import plan for a Core-Wasm artifact.

   Providers are retained as opaque handles for the embedding application. A
   workerd binding must turn only these named entries into its WebAssembly
   imports object and invoke `provider/invoke!` for every guest call."
  [{:keys [artifact] :as request}]
  (when-not (= :wasm/v1 (:format artifact))
    (throw (ex-info "workerd accepts Core-Wasm artifacts only"
                    {:phase :workerd-core :format (:format artifact)})))
  (when-not (bytes? (:bytes artifact))
    (throw (ex-info "workerd Core-Wasm artifact requires bytes"
                    {:phase :workerd-core})))
  (let [prepared (provider/prepare! (assoc request :runtime :workerd-core
                                                   :component? false))]
    {:runtime :workerd-core
     :ambient-wasi false
     :module-bytes (:bytes artifact)
     :imports (:providers prepared)
     :abilities (:component-imports artifact)
     :invoke (fn [import payload] (provider/invoke! prepared import payload))}))
