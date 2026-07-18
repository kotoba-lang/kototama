(ns kototama.aiueos-adapter
  "The 'aiueos decides, kototama enforces' loop ADR-2607062330's follow-up
  (and ADR-2607062330 addendum 6) left open: translates a REAL aiueos
  policy decision into a `kototama.contract/host-caps` value, instead of
  every `kototama.tender` caller hand-building `HostCaps` in a test.

  aiueos's own decision entry point is `aiueos.decide` (ADR-2607022700's
  decision-subprocess design): a process a native host adapter shells out
  to via `bb decide`, reading/writing newline-delimited EDN. That subprocess
  wrapper exists for host adapters that can't or don't want a JVM/Clojure
  dependency on aiueos directly (a Rust or Node tender, say). `kototama`
  IS already JVM/Clojure, and `aiueos.decide/handle-request` is documented
  as \"Pure aside from the contract lookup -- no stdio -- so it's directly
  testable without a subprocess\" -- so this adapter calls `aiueos.cli/
  command-result` (what `handle-request` itself calls, one layer down,
  skipping the EDN-line marshaling a subprocess boundary needs but an
  in-process call doesn't) directly, as a real `io.github.kotoba-lang/
  aiueos` dependency, rather than spawning `bb decide` per decision. This
  is still `aiueos.decide!`'s and `aiueos.cli/command-result`'s decision --
  this adapter only translates the answer, exactly as the deferred
  follow-up specified -- not a second, kototama-owned decision algorithm,
  and not a code-level merge of the two execution namespaces (kototama.
  tender still never decides a grant itself; ADR-2607022700's rule)."
  (:require [aiueos.cli :as cli]
            [kototama.contract :as contract]))

(def kototama-import->aiueos-capability
  "kototama.contract import id -> aiueos capability keyword, for the subset
  both actor:host and aiueos's own vocabulary recognize (aiueos's default
  kernel capabilities, `aiueos.policy/default-kernel-caps`) -- these are
  the ones a manifest can ask for and have aiueos's OWN default policy
  grant with no overlay at all. `:gen-keypair`/`:sign`/`:verify`/
  `:sha256-hex`/`:http-post`/`:log-read` are actor:host-only (no aiueos
  kernel-capability counterpart) and are not translatable through this
  adapter -- a caller needing those still supplies HostCaps directly, same
  as before this adapter existed."
  {:log-write :log/write
   :clock-monotonic :clock/monotonic
   :random-bytes :random/bytes})

(def ^:private aiueos-cli-contract
  (delay (cli/read-contract)))

(defn manifest-for-imports
  "An aiueos manifest requesting IMPORT-IDS (kototama.contract import ids
  translatable via `kototama-import->aiueos-capability`), shaped like
  `aiueos.decide-test`'s own real granted example. COMPONENT/TRUST/KIND
  are overridable (e.g. a caller can pass `:aiueos/trust :untrusted` to
  exercise a real denial) -- default trust is `:verified`, the same level
  `aiueos.decide-test`'s granted fixture uses."
  ([import-ids] (manifest-for-imports import-ids {}))
  ([import-ids {:keys [component kind trust kagi-requests]
                :or {component :kototama/guest kind :service trust :verified}}]
   (cond-> {:aiueos/component component
            :aiueos/kind kind
            :aiueos/trust trust
            :aiueos/imports (into #{} (keep kototama-import->aiueos-capability) import-ids)
            :aiueos/exports #{}}
     (seq kagi-requests) (assoc :aiueos/kagi-requests (vec kagi-requests)))))

(defn decide
  "The real aiueos :verify decision for MANIFEST (`aiueos.cli/command-
  result`'s :aiueos/decision map -- :grant or :deny, plus whatever else
  `aiueos.broker/verify-one` returns). POLICY-OVERLAY (optional) is the
  same `:aiueos/*` EDN `aiueos.contract/validate-deployment-policy`
  describes -- omit it to decide under aiueos's own unmodified default
  policy (which already grants every `default-kernel-caps` capability)."
  ([manifest] (decide manifest nil))
  ([manifest policy-overlay]
   (cli/command-result @aiueos-cli-contract :verify
                        (cond-> {:aiueos/manifest manifest}
                          policy-overlay (assoc :aiueos/policy-overlay policy-overlay)))))

(defn host-caps-for-imports
  "Ask aiueos (a real `aiueos.cli/command-result :verify` call, not a
  test-hardcoded grant) whether IMPORT-IDS are allowed, and build a
  `kototama.contract/host-caps` value from the REAL answer: `:grant` ->
  grants exactly IMPORT-IDS; `:deny` -> grants nothing (`host-caps`'s own
  fail-closed default), never a partial or best-effort grant. OPTS is
  `manifest-for-imports`' component/kind/trust plus `:policy-overlay`/
  `:limits` (merged into the resulting HostCaps' :limits, since aiueos's
  decision only speaks to WHICH imports are allowed, not kototama's own
  RuntimeLimits vocabulary -- a caller still opts into e.g.
  :allow-write-imports? here, same as calling contract/host-caps
  directly)."
  ([import-ids] (host-caps-for-imports import-ids {}))
  ([import-ids {:keys [policy-overlay limits] :as opts}]
   (let [manifest (manifest-for-imports import-ids opts)
         decision (decide manifest policy-overlay)
         granted? (= :grant (:aiueos/decision decision))]
     {:host-caps (contract/host-caps {:grants (if granted? import-ids #{})
                                      :limits limits})
      :decision decision
      :kagi-decisions (when granted? (:aiueos.broker/kagi-decisions decision))})))

(defn kagi-sign-context
  "Return HostCaps plus the exact aiueos decisions tender requires for one
  handle-based signing reference. No secret or private key is resolved here."
  [{:keys [component key-ref purpose policy-overlay limits]
    :or {component :kototama/guest}}]
  (host-caps-for-imports
   [:kagi-sign]
   {:component component
    :limits limits
    :kagi-requests [{:secret-ref key-ref :purpose purpose :operation :sign}]
    :policy-overlay policy-overlay}))
