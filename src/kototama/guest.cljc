(ns kototama.guest
  "Guest-facing maturity helpers for the kototama tender (R1).

   Pure / portable checks that sit *above* `kototama.tender` execution:

   - lint .kotoba source for known `kotoba wasm emit` pitfalls
   - classify host-free vs host-import guests
   - maturity level of a given run profile

   Execution still goes through `kototama.tender` (JVM/Chicory). This ns does
   not parse Wasm itself (see tender/inspect-module for that)."
  (:require [clojure.string :as str]
            [kototama.contract :as contract]))

;; ── wasm field names (kotoba module) ────────────────────────────────────────

(def wasm-field-by-import-id
  "Map contract import id → field name under module \"kotoba\".
   Must match kototama.tender host-fn field strings and kotoba wasm emit."
  {:gen-keypair "gen_keypair"
   :sign "sign"
   :verify "verify"
   :sha256-hex "sha256_hex"
   :http-post "http_post"
   :log-read "log_read"
   :log-write "log_write"
   :clock-monotonic "clock_monotonic"
   :random-bytes "random_bytes"
   :kagi-sign "kagi_sign"
   :llm-infer "llm_infer"
   ;; Second wave (com-junkawasaki/root ADR-2607230943). :http-fetch's
   ;; field matches kotoba-core-contracts' pre-existing "http/fetch"
   ;; (id 205) entry verbatim -- see contract.cljc's :http-fetch comment.
   :http-fetch "http_fetch"
   :cbor-encode "cbor_encode"
   :json-encode "json_encode"
   :json-extract-field "json_extract_field"
   ;; Third wave (com-junkawasaki/root, this ADR). A SEPARATE field name
   ;; from :http-post's own "http_post" -- see contract.cljc's
   ;; :http-post-headers comment for why this couldn't just widen
   ;; http-post's own arity.
   :http-post-headers "http_post_headers"
   :transport-connect "transport_connect"
   :tls-open "tls_open"
   :tls-server-end-point "tls_server_end_point"
   :transport-write "transport_write"
   :transport-read "transport_read"
   :transport-close "transport_close"
   :pg-cancel-register "pg_cancel_register"
   :pg-cancel "pg_cancel"
   :pg-pool-open "pg_pool_open"
   :pg-pool-acquire "pg_pool_acquire"
   :pg-pool-query "pg_pool_query"
   :pg-pool-release "pg_pool_release"
   :pg-pool-stats "pg_pool_stats"
   :pg-pool-health "pg_pool_health"
   :pg-pool-drain "pg_pool_drain"
   :pg-pool-close "pg_pool_close"
   :pg-open "pg_open"
   :pg-query "pg_query"
   :pg-simple-query "pg_simple_query"
   :pg-prepare "pg_prepare"
   :pg-session-reset "pg_session_reset"
   :pg-close-statement "pg_close_statement"
   :pg-query-state "pg_query_state"
   :pg-prepare-typed "pg_prepare_typed"
   :pg-execute-params2 "pg_execute_params2"
   :pg-execute-params "pg_execute_params"
   :pg-bind-portal "pg_bind_portal"
   :pg-fetch-portal "pg_fetch_portal"
   :pg-close-portal "pg_close_portal"
   :pg-copy-out "pg_copy_out"
   :pg-copy-in "pg_copy_in"
   :pg-execute-batch "pg_execute_batch"
   :http-get-stream "http_get_stream"
   :object-get-stream "object_get_stream"
   :object-put-block "object_put_block"
   :object-compare-and-set-ref "object_compare_and_set_ref"})

(defn wasm-field-name
  "Canonical Wasm import field for a contract import id, or nil."
  [id]
  (get wasm-field-by-import-id (contract/import-id id)))

;; ── .kotoba source lint (emit pitfalls) ─────────────────────────────────────

(defn- strip-line-comments [s]
  (->> (str/split-lines s)
       (map (fn [line]
              (if-let [i (str/index-of line ";;")]
                (subs line 0 i)
                line)))
       (str/join "\n")))

(defn lint-kotoba-source
  "Static checks for `.kotoba` sources before `kotoba wasm emit`.

   Known emit pitfalls (verified 2026-07-10 against kotoba-lang/kotoba):
   - defn docstring treated as arity → `main-arity` emit failure
   - missing 0-arity `main` export target
   - empty source

   Returns {:ok? bool :problems [{:code :keyword :message str :hint str?}…]
            :has-main? bool :defn-count n}."
  [source]
  (let [src (strip-line-comments (or source ""))
        problems (atom [])
        add! (fn [code message & [hint]]
               (swap! problems conj (cond-> {:code code :message message}
                                      hint (assoc :hint hint))))
        ;; crude but effective: (defn name "doc" [args] …)
        docstring-defn?
        (re-seq #"(?s)\(defn\s+[A-Za-z0-9_*!?+\-><=]+(?:\s+\^[^\s]+)?\s+\"[^\"]+\"\s*\[" src)
        main-forms (re-seq #"(?s)\(defn\s+main\b" src)
        main-zero-arity (re-find #"(?s)\(defn\s+main\b(?:\s+\"[^\"]*\")?\s*\[\s*\]" src)
        defn-count (count (re-seq #"\(defn\b" src))]
    (when (str/blank? (str/trim src))
      (add! :source/empty "source is empty"))
    (when docstring-defn?
      (add! :emit/defn-docstring
            "defn docstring detected — kotoba wasm emit treats the string as arity"
            "Remove defn docstrings; put commentary in ;; line comments instead."))
    (when (empty? main-forms)
      (add! :emit/missing-main
            "no (defn main …) found — emit targets export \"main\""
            "Add (defn main [] …) as the guest entrypoint."))
    (when (and (seq main-forms) (not main-zero-arity))
      (add! :emit/main-arity
            "(defn main …) does not look 0-arity"
            "Use (defn main [] body) — tender call-main invokes 0-arity main only."))
    {:ok? (empty? @problems)
     :problems @problems
     :has-main? (boolean (seq main-forms))
     :defn-count defn-count}))

;; ── guest profile / maturity ────────────────────────────────────────────────

(def maturity-levels
  "Honest maturity ladder for kototama as a Wasm tender (not marketing).

   :r0  contract-only / dry-run membrane (pre-tender)
   :r1  tender runs real .wasm (host-free + actor:host imports), fuel + memory
        limits, session report, source lint, checked-in emit fixtures
   :r2  browser-native host parity matrix + host-free web fixtures
        (9/14 linkable; http-post real in a cross-origin-isolated tab via a
        Worker-hosted SAB+Atomics bridge (wasm-webcomponent PR #8);
        llm-infer now real too via the SAME bridge, through a
        caller-supplied proxy URL (wasm-webcomponent PR #11); the
        ADR-2607230943 second wave -- http-fetch/cbor-encode/json-encode/
        json-extract-field -- and the third wave -- http-post-headers --
        are JVM-only so far, an honest gap, not yet ported to
        wasm-webcomponent's actor-host.js)
   Fleet placement maturity is reported by the independent kotoba-lang/fleet
   repository; it consumes this tender rather than being part of it."
  {:r0 {:id :r0
        :title "Contract / dry-run"
        :status :stable
        :note "kototama.contract validates HostCaps; organism membrane R0."}
   :r1 {:id :r1
        :title "Tender execution (JVM/Chicory)"
        :status :stable
        :note "kototama.tender + aiueos adapter + real kotoba-emitted fixtures."}
   :r2 {:id :r2
        :title "Browser-native host parity"
        :status :advanced-partial
        :note "9/14 linkable; http-post and llm-infer both real via Worker-hosted SAB+Atomics bridge (needs COOP/COEP; llm-infer additionally needs a caller-supplied proxy URL); the ADR-2607230943 second wave (http-fetch/cbor-encode/json-encode/json-extract-field) and third wave (http-post-headers) are JVM-only so far; see kototama.browser."}})

(defn host-free?
  "True when the guest requests no host imports (pure compute)."
  [requested-imports]
  (empty? (contract/requested-import-ids requested-imports)))

(defn profile
  "Classify a guest run intent.

   requested-imports + optional caps →
   {:host-free? bool :maturity :r1 :imports […] :caps HostCaps
    :effects #{…} :network? bool :secret? bool :write? bool}"
  ([requested-imports] (profile requested-imports nil))
  ([requested-imports caps]
   (let [ids (vec (keep identity (contract/requested-import-ids requested-imports)))
         caps (contract/host-caps (or caps {}))
         effects (set (mapcat #(:import/effects (contract/import-by-id %)) ids))]
     {:host-free? (empty? ids)
      :maturity :r1
      :imports ids
      :caps caps
      :effects effects
      :network? (boolean (some #{:network} effects))
      :secret? (boolean (some #{:secret} effects))
      :write? (boolean (some #{:write} effects))})))

(defn maturity-report
  "Aggregate tender maturity snapshot for CLI doctor / CI.
   Placement detail lives in kotoba-lang/fleet."
  []
  {:current :r2
   :current-note "R1 stable + R2 advanced-partial; T6 placement is external"
   :levels maturity-levels
   :import-surface (mapv :import/id (:abi/imports contract/import-surface))
   :wasm-fields wasm-field-by-import-id
   :notes ["R1 gate: clojure -M:test (tender + contract + aiueos + guest + maturity)"
           "R2 gate: node web/verify*.mjs (+ verify-host-free.mjs)"
           "T6 placement gate: kotoba-lang/fleet `clojure -M:cli fleet-gate`"
           "Host-free pure guests: empty requested-imports + empty grants"
           "Emit path: kotoba-lang/kotoba `wasm emit` → .wasm → tender/run-report"
           "Lint .kotoba with kototama.guest/lint-kotoba-source before emit"]})
