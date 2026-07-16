(ns kototama.browser
  "R2: browser-native host parity matrix vs kototama.tender (JVM).

   Pure data — no DOM, no Wasm. Documents what
   kotoba-lang/wasm-webcomponent `actor-host.js` implements synchronously
   vs what the JVM tender implements. Used by doctor / maturity report.

   Honest gaps:
   - http-post: not bare-sync in a tab; a SAB+COOP bridge or JSPI could
     make it so but neither is built yet (2026-07-16 audit) -- Node inject
     is the only real path today
   - llm-infer: browser absent; Node can inject synchronous backend
   - kgraph-*: separate surface (kgraph.js), not actor:host"
  (:require [kototama.contract :as contract]
            [kototama.guest :as guest]))

(def host-impl
  "Per-import availability by host kind.

   :jvm     — kototama.tender (Chicory)
   :browser — wasm-webcomponent actor-host.js (sync only)
   :node    — same JS module under Node (can inject llmInfer)"
  {:gen-keypair     {:jvm :yes :browser :yes :node :yes
                     :note "browser: @noble/curves vendored sync Ed25519"}
   :sign            {:jvm :yes :browser :yes :node :yes}
   :verify          {:jvm :yes :browser :yes :node :yes}
   :sha256-hex      {:jvm :yes :browser :yes :node :yes}
   ;; :browser is genuinely :no, not :coop-or-inject -- a 2026-07-16 audit
   ;; found no SAB+COOP bridge or JSPI wiring anywhere in
   ;; wasm-webcomponent (working tree or history); `actor-host.js` itself
   ;; deliberately omits `http_post` from its exported imports for exactly
   ;; this reason. Counting it as browser-linkable inflated `parity-score`
   ;; to a false "8/9" (ADR-0007 addendum, 2026-07-16).
   :http-post       {:jvm :yes :browser :no :node :inject
                     :note "inject opts.httpPost (Node only); SAB+COOP bridge and JSPI are unbuilt, not yet a real browser path"}
   :log-read        {:jvm :yes :browser :yes :node :yes}
   :log-write       {:jvm :yes :browser :yes :node :yes}
   :clock-monotonic {:jvm :yes :browser :yes :node :yes}
   :llm-infer       {:jvm :yes :browser :no  :node :inject
                     :note "browser: no sync network; node: opts.llmInfer inject"}})

(defn import-status
  "Status map for one import id under :jvm | :browser | :node."
  [id host]
  (let [id (contract/import-id id)
        row (get host-impl id)]
    (when row
      {:import id
       :host host
       :status (get row host)
       :wasm-field (guest/wasm-field-name id)
       :note (:note row)})))

(defn parity-matrix
  "Full matrix: vector of {:import :jvm :browser :node :wasm-field :note}."
  []
  (mapv (fn [id]
          (let [row (get host-impl id)]
            {:import id
             :jvm (:jvm row)
             :browser (:browser row)
             :node (:node row)
             :wasm-field (guest/wasm-field-name id)
             :note (:note row)}))
        (map :import/id (:abi/imports contract/import-surface))))

(defn- browser-linkable?
  "True when browser can link the import (possibly needing COOP/inject)."
  [status]
  (contains? #{:yes :coop-or-inject :inject} status))

(defn browser-available-ids
  "Import ids linkable in a browser (incl. COOP/inject paths)."
  []
  (vec (keep (fn [[id row]]
               (when (browser-linkable? (:browser row)) id))
             host-impl)))

(defn browser-missing-ids
  "Import ids not available as host imports in a standard browser tab
   without a special backend."
  []
  (vec (keep (fn [[id row]]
               (when-not (browser-linkable? (:browser row)) id))
             host-impl)))

(defn parity-score
  "R2 score: fraction of actor:host imports browser-linkable."
  []
  (let [rows (parity-matrix)
        n (count rows)
        yes (count (filter #(browser-linkable? (:browser %)) rows))]
    {:total n
     :browser-yes yes
     :browser-no (- n yes)
     :ratio (if (pos? n) (double (/ yes n)) 0.0)
     :available (browser-available-ids)
     :missing (browser-missing-ids)
     ;; :sab-coop / :jspi-experimental are NOT built (2026-07-16 audit, see
     ;; ADR-0007 addendum) -- listed here as the known candidate approaches
     ;; for closing the gap, not as existing paths. Only :inject is real.
     :http-post-paths {:inject :implemented
                       :sab-coop :not-yet-built
                       :jspi-experimental :not-yet-built}}))

(defn r2-report
  "Aggregate R2 snapshot for CLI doctor."
  []
  {:level :r2
   :status :advanced-partial
   :title "Browser-native host parity"
   :score (parity-score)
   :matrix (parity-matrix)
   :host-free-guests ["web/host-free-fact.wasm"
                      "web/host-free-peak-cells.wasm"
                      "web/demo.wasm"]
   :actor-host-demo "web/actor-host-demo.wasm"
   :library "kotoba-lang/wasm-webcomponent (actor-host.js, kgraph.js)"
   :verify ["node web/verify.mjs"
            "node web/verify-kgraph.mjs"
            "node web/verify-actor-host.mjs"
            "node web/verify-host-free.mjs"]
   :notes ["Policy re-enforcement at load: actor-host.js yes; kgraph.js no"
           "http-post: inject (Node) only -- no SAB+COOP bridge or JSPI wiring exists yet"
           "llm-infer injectable on Node only"]})
