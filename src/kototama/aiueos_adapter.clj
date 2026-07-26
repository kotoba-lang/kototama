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
            [aiueos.component-abi :as component-abi]
            [kototama.contract :as contract]
            [kototama.component-platform :as component-platform]
            [kototama.component-provider :as component-provider]
            [kototama.wasmtime-component :as wasmtime-component]))

(def kototama-import->aiueos-capability
  "kototama.contract import id -> stable Aiueos policy capability. Only
  log/write, clock/monotonic, and random/bytes belong to Aiueos's default
  kernel set. The other typed Component operations therefore remain denied
  unless an explicit deployment policy overlay grants their exact keyword."
  {:sign :identity/sign
   :verify :identity/verify
   :sha256-hex :hash/sha256
   :http-post :http/post
   :log-read :log/read
   :log-write :log/write
   :clock-monotonic :clock/monotonic
   :random-bytes :random/bytes})

(def component-import->kototama-import
  "Stable compiler Component WIT import -> tender import id.  This is a
  closed map: an unknown Component import is never translated to ambient
  WASI or a best-effort host binding."
  {:aiueos.component/aiueos-identity-sign :sign
   :aiueos.component/aiueos-identity-verify :verify
   :aiueos.component/aiueos-hash-sha256 :sha256-hex
   :aiueos.component/aiueos-http-post :http-post
   :aiueos.component/aiueos-log-read :log-read
   :aiueos.component/aiueos-log-append :log-write
   :aiueos.component/aiueos-clock-now :clock-monotonic})

(declare host-caps-for-imports)

(defn- current-lease-epoch!
  [epoch-source]
  (let [epoch (try
                (epoch-source)
                (catch Exception cause
                  (throw (ex-info "Component lease epoch source failed"
                                  {:phase :component-grant
                                   :reason :epoch-source-failed}
                                  cause))))]
    (when-not (and (integer? epoch) (pos? epoch))
      (throw (ex-info "Component lease epoch must be a positive integer"
                      {:phase :component-grant
                       :reason :invalid-lease-epoch
                       :epoch epoch})))
    epoch))

(defn host-caps-for-component
  "Ask aiueos for exactly the imports declared by a compiler Component
  artifact. ARTIFACT is the compiler result's public capability set; unknown
  names fail closed before an aiueos request is made."
  ([artifact] (host-caps-for-component artifact {}))
  ([artifact opts]
   (let [declared (set (:capabilities artifact))
         _ (component-abi/requested-capabilities! declared)
         imports (mapv component-import->kototama-import declared)]
     (when (or (some nil? imports) (not= (count imports) (count declared)))
       (throw (ex-info "Component declares an unmapped WIT capability"
                       {:phase :component-grant :capabilities declared})))
     (let [{:keys [decision] :as result} (host-caps-for-imports imports opts)]
       (when-not (component-abi/decision-grants-imports? decision declared)
         (throw (ex-info "Aiueos decision does not cover every Component import"
                         {:phase :component-grant
                          :declared declared :decision decision})))
       result))))

(defn admit-component-with-aiueos!
  "The only bridge from a compiler Component artifact to the native Component
  linker. Aiueos decides; this function only translates a grant into the
  exact WIT bindings and delegates CID/world verification to component-platform.
  A denial, unknown import, or missing provider aborts before LINKER! runs."
  [artifact world component-bytes linker! providers
   {:keys [lease-id lease-epoch lease-epoch-source lease-ttl-ms now-ms
           execution-identity] :as opts}]
  (let [declared (set (:capabilities artifact))
        abilities (:component-imports artifact)
        {:keys [host-caps decision]} (host-caps-for-component artifact opts)
        now-ms (or now-ms #(System/currentTimeMillis))
        issued-at (long (if (ifn? now-ms) (now-ms) now-ms))
        lease-epoch (or lease-epoch 1)
        _ (when (and lease-epoch-source (not (ifn? lease-epoch-source)))
            (throw (ex-info "Component lease epoch source must be callable"
                            {:phase :component-grant :reason :invalid-epoch-source})))
        epoch-source (or lease-epoch-source (constantly (or lease-epoch 1)))
        issued-epoch (current-lease-epoch! epoch-source)
        lease (component-abi/issue-lease
               {:decision decision :imports declared :abilities abilities
                :now issued-at :epoch issued-epoch
                :ttl-ms (or lease-ttl-ms 30000)
                :lease-id (or lease-id (str "component-" issued-at))})
        lease-authorize?
        #(component-abi/lease-authorizes?
          lease (current-lease-epoch! epoch-source)
          (long (if (ifn? now-ms) (now-ms) now-ms)) %1 %2)
        expected (set (keep component-import->kototama-import declared))]
    (when-not (= expected (:grants host-caps))
      (throw (ex-info "aiueos did not grant every declared Component import"
                      {:phase :component-grant :decision decision
                       :declared declared :granted (:grants host-caps)})))
    (when-not (= declared (set (keys (select-keys providers declared))))
      (throw (ex-info "Component provider binding is missing"
                      {:phase :component-grant :declared declared
                       :providers (set (keys providers))})))
    (when-not (= declared (set (keys abilities)))
      (throw (ex-info "Component artifact ability descriptors do not match imports"
                      {:phase :component-grant :declared declared
                       :abilities (set (keys abilities))})))
    ;; Engine selection is part of the TCB boundary, never an ambient detail
    ;; of LINKER!.  Today the sole admitted Component adapter is Wasmtime;
    ;; Chicory and workerd remain core-Wasm-only compatibility paths.
    (component-provider/prepare!
     {:runtime (:runtime opts) :component? true :artifact artifact
      :grants declared :providers (select-keys providers declared)
      ;; Admission itself must use the same lease predicate that gates each
      ;; provider call.  Otherwise a malformed or already-revoked lease could
      ;; reach the native linker before the first operation is rejected.
      :lease-authorize? lease-authorize?})
    (component-platform/admit-and-link!
     (assoc world :imports declared :grants declared
            :provider-bindings (select-keys providers declared)
            :abilities abilities
            ;; The native executable is part of the authority boundary, so
            ;; bind its digest during admission rather than trusting a caller
            ;; supplied world envelope to have done so.  A previously
            ;; admitted world may already carry that exact binding; retain it
            ;; for replay of a signed admission envelope.
            :runtime-bindings {:component-host-sha256
                               (or (get-in world [:runtime-bindings
                                                 :component-host-sha256])
                                   (:component-host-sha256 opts))})
     execution-identity
     component-bytes
     #(linker! (assoc % :lease lease :lease-epoch issued-epoch
                       :lease-epoch-source epoch-source :now-ms now-ms
                       :lease-authorize? lease-authorize?
                       :audit-sink (:audit-sink opts))))))

(defn admit-and-run-component-with-aiueos!
  [artifact world component-bytes providers
   {:keys [component-host execution-identity] :as opts}]
  (admit-component-with-aiueos!
   artifact world component-bytes
   (fn [admitted]
     (wasmtime-component/run-effectful!
      (assoc admitted :runtime :wasmtime-component
             :artifact artifact :providers providers
             :component-host component-host
             :execution-identity execution-identity)))
   providers opts))

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
  ([import-ids {:keys [component kind trust]
                :or {component :kototama/guest kind :service trust :verified}}]
   {:aiueos/component component
    :aiueos/kind kind
    :aiueos/trust trust
    :aiueos/imports (into #{} (map kototama-import->aiueos-capability) import-ids)
    :aiueos/exports #{}}))

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
      :decision decision})))
