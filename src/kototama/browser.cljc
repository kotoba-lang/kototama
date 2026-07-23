(ns kototama.browser
  "R2: browser-native host parity matrix vs kototama.tender (JVM).

   Pure data — no DOM, no Wasm. Documents what
   kotoba-lang/wasm-webcomponent `actor-host.js` implements synchronously
   vs what the JVM tender implements. Used by doctor / maturity report.

   Honest gaps:
   - http-post: real in a browser tab as of wasm-webcomponent PR #8
     (2026-07-16) -- a SharedArrayBuffer+Atomics.wait bridge
     (http-post-bridge.js's createSabHttpPostBridge), but only inside a
     cross-origin-isolated page (COOP/COEP headers) with the guest
     instantiated in a dedicated Worker (Atomics.wait is disallowed on the
     main/DOM thread) -- see kotoba-wasm-worker-host.js /
     kotoba-wasm-worker-element.js and
     test/browser/verify_http_post_browser.cljs there for the real,
     verified end-to-end path
   - llm-infer: real in a browser tab as of wasm-webcomponent PR #11
     (2026-07-16) -- reuses the SAME Worker+SAB+Atomics bridge http-post
     proved out (kotoba-wasm-worker-host.js passes ONE bridge instance as
     both httpPostBridge and llmInferBridge). NOT a direct call to a real
     LLM provider, though: a browser tab can never hold a provider API key
     without shipping it to every visitor, so this requires a
     developer-controlled proxy URL (llmInferUrl) that itself holds any
     real credential server-side -- see
     test/browser/verify_llm_infer_browser.cljs there.
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
   ;; :browser is genuinely :yes as of wasm-webcomponent PR #8 (2026-07-16):
   ;; createSabHttpPostBridge (SharedArrayBuffer+Atomics.wait) is real and
   ;; verified end-to-end in real headless Chromium
   ;; (test/browser/verify_http_post_browser.cljs there) -- but only inside
   ;; a cross-origin-isolated page (COOP/COEP headers), with the guest
   ;; instantiated in a dedicated Worker (Atomics.wait can't run on the
   ;; main/DOM thread). Two real bugs had to be fixed to get here (a
   ;; TextDecoder-on-SharedArrayBuffer rejection, and a Worker-startup race)
   ;; -- an earlier merge to that repo's main had wired this import but
   ;; never actually completed a request.
   :http-post       {:jvm :yes :browser :yes :node :inject
                     :note "browser: Worker-hosted guest + SAB+Atomics bridge, requires COOP/COEP; node: opts.httpPost inject"}
   :log-read        {:jvm :yes :browser :yes :node :yes}
   :log-write       {:jvm :yes :browser :yes :node :yes}
   :clock-monotonic {:jvm :yes :browser :yes :node :yes}
   ;; :browser is genuinely :yes as of wasm-webcomponent PR #11 (2026-07-16):
   ;; reuses the SAME Worker+SAB+Atomics bridge http-post proved out
   ;; (kotoba-wasm-worker-host.js constructs ONE bridge, passes it as both
   ;; httpPostBridge and llmInferBridge). Requires a caller-supplied
   ;; llmInferUrl pointing at a developer-controlled proxy that holds any
   ;; real LLM provider credential server-side -- never a provider endpoint
   ;; called directly with a key embedded in browser-shipped JS/HTML.
   :llm-infer       {:jvm :yes :browser :yes :node :inject
                     :note "browser: Worker-hosted guest + SAME SAB+Atomics bridge as http-post, via a caller-supplied proxy URL (never a provider key embedded client-side); node: opts.llmInfer inject"}
   ;; ADR-2607230943 second wave: JVM-only so far (kototama.tender), an
   ;; honest gap -- no wasm-webcomponent actor-host.js port exists yet for
   ;; any of these 4 (a future SAB+Atomics bridge could reuse http-post's
   ;; pattern for :http-fetch; cbor-encode/json-encode/json-extract-field
   ;; are pure computation and don't need one at all, just a JS port of
   ;; the same flat-pairs parsing + encode/scan logic).
   :http-fetch          {:jvm :yes :browser :no :node :no
                         :note "JVM only so far; reuses kotoba-core-contracts' pre-existing http/fetch (id 205) shape"}
   :cbor-encode         {:jvm :yes :browser :no :node :no
                         :note "JVM only so far; pure computation, no network/DOM dependency to port"}
   :json-encode         {:jvm :yes :browser :no :node :no
                         :note "JVM only so far; pure computation, no network/DOM dependency to port"}
   :json-extract-field  {:jvm :yes :browser :no :node :no
                         :note "JVM only so far; pure computation, no network/DOM dependency to port"}})

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
     ;; :sab-coop is real as of wasm-webcomponent PR #8 (2026-07-16, see
     ;; ADR-0007's second addendum) -- :jspi-experimental remains unbuilt
     ;; (Chrome-only, not broadly shipped).
     :http-post-paths {:inject :implemented
                       :sab-coop :implemented
                       :jspi-experimental :not-yet-built}
     ;; llm-infer's browser path (PR #11) reuses :sab-coop above, not a
     ;; distinct bridge -- tracked separately here since it additionally
     ;; requires a caller-supplied proxy URL.
     :llm-infer-paths {:inject :implemented
                       :sab-coop-via-proxy :implemented}}))

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
   :library "kotoba-lang/wasm-webcomponent (actor-host.js, http-post-bridge.js, kotoba-wasm-worker-host.js, kotoba-wasm-worker-element.js, kgraph.js)"
   :verify ["node web/verify.mjs"
            "node web/verify-kgraph.mjs"
            "node web/verify-actor-host.mjs"
            "node web/verify-host-free.mjs"
            "npm run test:http-post-browser  ; in wasm-webcomponent"
            "npm run test:llm-infer-browser  ; in wasm-webcomponent"]
   :notes ["Policy re-enforcement at load: actor-host.js yes; kgraph.js no"
           "http-post: real in a cross-origin-isolated tab via a Worker-hosted SAB+Atomics bridge; inject (Node) also available"
           "llm-infer: real in a cross-origin-isolated tab via the SAME bridge as http-post, through a caller-supplied proxy URL (never a provider key embedded client-side); inject (Node) also available"]})
