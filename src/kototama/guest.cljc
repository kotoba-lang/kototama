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
   :llm-infer "llm_infer"})

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
        (8/9 linkable; http-post inject|SAB-COOP; llm-infer Node-inject only)
   :r3  fleet lease/budget/tick/governor/checkpoint + disk/B2 + tender execute"
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
        :note "8/9 linkable; http-post inject|SAB-COOP; see kototama.browser."}
   :r3 {:id :r3
        :title "Fleet multi-tenant tender"
        :status :skeleton+persist
        :note "fleet + disk/B2 store + tender execute — not cross-node."}})

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
  "Aggregate maturity snapshot for CLI doctor / CI.
   R2/R3 detail lives in kototama.browser/r2-report and kototama.fleet/r3-report
   (loaded optionally by CLI to keep this ns free of those requires)."
  []
  {:current :r2
   :current-note "R1 stable + R2 advanced-partial; R3 skeleton+persist"
   :levels maturity-levels
   :import-surface (mapv :import/id (:abi/imports contract/import-surface))
   :wasm-fields wasm-field-by-import-id
   :notes ["R1 gate: clojure -M:test (tender + contract + aiueos + guest + maturity)"
           "R2 gate: node web/verify*.mjs (+ verify-host-free.mjs)"
           "R3: fleet-run + disk/B2 checkpoint; tender execute at fleet-exec edge"
           "Host-free pure guests: empty requested-imports + empty grants"
           "Emit path: kotoba-lang/kotoba `wasm emit` → .wasm → tender/run-report"
           "Lint .kotoba with kototama.guest/lint-kotoba-source before emit"]})
