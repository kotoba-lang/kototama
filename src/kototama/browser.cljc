(ns kototama.browser
  "R2: browser-native host parity matrix vs kototama.tender (JVM).

   Pure data — no DOM, no Wasm. Documents what
   kotoba-lang/wasm-webcomponent `actor-host.js` implements synchronously
   vs what the JVM tender implements. Used by doctor / maturity report.

   Honest gaps:
   - http-post: absent in browser (needs JSPI or COOP/COEP SAB bridge)
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
   :http-post       {:jvm :yes :browser :coop-or-inject :node :inject
                     :note "inject opts.httpPost; browser SAB bridge needs COOP/COEP; JSPI experimental"}
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
     :http-post-paths [:inject :sab-coop :jspi-experimental]}))

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
   :library "kotoba-lang/wasm-webcomponent (actor-host.js, http-post-bridge.js, kgraph.js)"
   :verify ["node web/verify.mjs"
            "node web/verify-kgraph.mjs"
            "node web/verify-actor-host.mjs"
            "node web/verify-host-free.mjs"
            "node test/verify-http-post.mjs  ; in wasm-webcomponent"]
   :notes ["Policy re-enforcement at load: actor-host.js yes; kgraph.js no"
           "http-post: inject (Node) | SAB+COOP bridge (browser) | JSPI experimental"
           "llm-infer injectable on Node only"]})
