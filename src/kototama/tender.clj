(ns kototama.tender
  "The kototama tender: the host-side ROLE that runs/mediates the
  `actor:host` ABI for a `.kotoba` Wasm guest, named by ADR-2607022400
  after Solo5's tender/guest split (kototama = tender, the Wasm
  component it hosts = guest). 'Tender' names WHO plays this part
  (validate the guest's requested import surface, wire each granted
  host-fn, execute the guest) -- it is host-language-independent by
  design, NOT a synonym for any one implementation language.

  THIS namespace is one implementation of the tender role, not the role
  itself: ADR-2607022900 decided THIS PARTICULAR tender is JVM/Clojure
  via `com.dylibso.chicory` (a pure-JVM WebAssembly runtime — no native
  toolchain, no wasmtime/wasmer process), not Rust/wasmtime. Other
  tenders for the same role, in other host languages, already exist or
  are expected -- see `kotoba-lang/aiueos`'s own ADR-0014
  (`90-docs/adr/0014-self-owned-vmm-hvt-tender.md`), a JVM-FFM
  (non-Chicory) tender for that repo's guest, which is concrete proof
  the 'tender' name itself carries no JVM/Chicory assumption. See the
  outer monorepo's ADR-2607183010 for the fuller writeup of this
  role-vs-implementation distinction (it also keeps the 'tender'
  vocabulary rather than renaming it away).

  `kototama.contract` is pure data validation with no execution anywhere
  in it (`validate-import-surface` decides yes/no, it does not run
  anything). This namespace is what makes that decision load-bearing:
  every guest import here is wired to actually perform the effect ONLY
  when `contract/validate-import-surface` says the caller's `HostCaps`
  grant it, checked BOTH before `instantiate` (fail fast, no Instance
  built for a disallowed surface) and again at each call (defense in
  depth — no path bypasses the grant check just because pre-flight
  already passed).

  The generic Chicory wiring below (host-fn/instantiate/call-main/
  run-main/fuel-listener/memory ptr-len helpers) is written fresh here,
  not vendored from `kotoba-lang/kotoba`'s `wasm_exec.clj` — that
  namespace is tightly coupled to kotoba's OWN kgraph/capability
  vocabulary (`kotoba.runtime/capability-contract`, `kotoba.kgraph`),
  and pulling it in as a dependency would run a second, incompatible
  capability model next to kototama's own `actor:host` ABI (the
  'semantic authority duplication' ADR-2607022700 rules out). The
  Chicory API calls themselves are the same shape because there is only
  one sane way to wire a HostFunction — proof-of-pattern, not shared
  code.

  Host module name is `\"kotoba\"`, matching `kotoba wasm emit`'s own
  hardcoded import-module convention (`kotoba.runtime`'s WASM encoder) --
  a real compiled `.kotoba` guest can only ever import from that exact
  module name, so this MUST match it or Chicory's `.build` fails to link
  any guest actually emitted by the compiler (an earlier draft used
  `\"kototama\"` here and only ever linked against this namespace's own
  hand-written WAT test fixtures, which happened to agree with it --
  never against a real compiled guest). Field names (gen_keypair, sign,
  verify, sha256_hex, http_post, log_read, log_write, clock_monotonic,
  llm_infer) don't collide with `kotoba.wasm-exec`'s own fields
  (kgraph_assert, has_capability, ...) under the same module, so both
  host surfaces can coexist for a guest that needs imports from each.

  `:log-write`/`:clock-monotonic` (not `:log-append!`/`:now`, an earlier
  draft's names) match field-for-field the `log-write`/`clock-monotonic`
  entries `kotoba-core-contracts`' `capability_contract.edn` registers
  for `kotoba wasm emit` (landed independently, for aiueos's own kernel-
  capability vocabulary) -- same operation, so this ABI reuses that name
  instead of registering a second one a `.kotoba` author would have to
  pick between for no real difference.

  `:llm-infer` (`llm_infer`, capability id 225 in `kotoba-core-contracts`'
  `capability_contract.edn`) is the one host function here that talks to
  a THIRD party (the Anthropic Messages API), not just this JVM process
  or its injected store -- so, unlike every other host-fn above, it is
  built with an injectable `llm-client` (`{:infer-fn (fn [prompt] text-
  or-nil)}`, same DI shape `log-read-host-fn`/`log-write-host-fn` already
  use for `store`) instead of calling `HttpClient` inline. `default-llm-
  client` is the production implementation; tests inject a fake
  `:infer-fn` instead of hitting the real network."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [kototama.contract :as contract]
            [ed25519.core :as ed])
  (:import (com.dylibso.chicory.runtime ExecutionListener HostFunction ImportFunction
                                        ImportValues Instance WasmFunctionHandle)
           (com.dylibso.chicory.wasm Parser)
           (com.dylibso.chicory.wasm.types FunctionType MemoryLimits ValType)
           (java.security MessageDigest SecureRandom)
           (java.net InetAddress Inet6Address URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.time Duration)))

;; ── memory ABI: (ptr,len) in / (out-ptr,out-cap) out, same convention
;; `kotoba.wasm-exec`'s read-str/write-bytes! establish ─────────────────────

(defn- read-bytes! [instance ptr len]
  (.readBytes (.memory instance) (int ptr) (int len)))

(defn- write-bytes! [instance ptr cap bs]
  (let [n (count bs)]
    (if (> n cap)
      -1
      (do (.write (.memory instance) (int ptr) (byte-array bs) 0 n)
          n))))

(defn host-fn
  "One (module \"kotoba\") host import: FIELD, param/result ValTypes, and
  a Clojure fn [instance long-args] -> long (the single i32/i64 result)."
  [field params result f]
  (HostFunction. "kotoba" field
                 (FunctionType/of params [result])
                 (reify WasmFunctionHandle
                   (apply [_ instance args]
                     (long-array [(f instance args)])))))

(def ^:private valtype {:i32 ValType/I32 :i64 ValType/I64})

;; ── fail-closed denial (same shape as kotoba.wasm-exec's guard pattern) ───

(defn denied!
  "Throws the standard kototama tender denial for a structural authority
  violation -- `:grant/missing`, the import id isn't in HostCaps'
  :grants (checked both pre-flight and per-call; a guest was never
  supposed to reach this at all, so this aborts the call, it does not
  hand the guest an in-band error to react to)."
  [import-id reason info]
  (throw (ex-info "kototama.tender: host import denied"
                  (merge {:kototama.tender/denied import-id
                          :kototama.tender/reason reason}
                         info))))

;; ── RuntimeLimits enforcement (Chicory has no native notion of "N calls
;; of category X" — this is kototama's own responsibility, per-instance
;; mutable counters closed over by each HostFunction below).
;;
;; Unlike `denied!` (a structural violation, hard throw), running out of
;; quota is an ordinary, recoverable condition -- signaled IN-BAND as -1,
;; the same convention `write-bytes!`'s overflow case already uses, so a
;; well-behaved guest can see it and back off instead of the whole `main`
;; call crashing on a Java exception it never gets a chance to handle. ──

(defn- new-limits-state [] (atom {:http-posts 0 :llm-infers 0 :log-read-bytes 0 :log-write-bytes 0}))

(defn- within-count-limit? [state-key limit-key caps limits-state]
  (< (get @limits-state state-key) (get (:limits caps) limit-key)))

(defn- try-add-bytes!
  "true and records N against STATE-KEY iff doing so would stay within
  LIMIT-KEY; false (no state change) otherwise."
  [state-key limit-key caps limits-state n]
  (let [limit (get (:limits caps) limit-key)
        current (get @limits-state state-key)]
    (if (> (+ current n) limit)
      false
      (do (swap! limits-state update state-key + n) true))))

;; ── per-call grant re-check (defense in depth — pre-flight already ran in
;; `instantiate`, but no HostFunction body trusts that alone) ───────────────

(defn- ensure-granted! [caps id]
  (when-not (contains? (:grants caps) id)
    (denied! id :grant/missing {:granted (:grants caps)})))

;; ── the 9 kototama.contract/import-surface host functions ──────────────────

(defn- gen-keypair-host-fn
  "`(out-ptr out-cap) -> bytes-written|-1`. Writes a fresh random 32-byte
  Ed25519 seed followed by its 32-byte derived public key (64 bytes total)
  into guest memory. `#{:crypto :secret}` -- gated by `:allow-secret-imports?`."
  [caps _limits-state]
  (host-fn "gen_keypair" [ValType/I32 ValType/I32] ValType/I32
           (fn [instance args]
             (ensure-granted! caps :gen-keypair)
             (let [seed (byte-array 32)]
               (.nextBytes (SecureRandom.) seed)
               (let [pub (ed/pubkey-from-seed seed)]
                 (write-bytes! instance (aget args 0) (aget args 1)
                              (byte-array (concat seed pub))))))))

(defn- sign-host-fn
  "`(seed-ptr msg-ptr msg-len out-ptr out-cap) -> bytes-written|-1`. Signs
  MSG with the raw 32-byte seed at SEED-PTR, writes the 64-byte signature.
  `#{:crypto :secret}`."
  [caps _limits-state]
  (host-fn "sign" [ValType/I32 ValType/I32 ValType/I32 ValType/I32 ValType/I32] ValType/I32
           (fn [instance args]
             (ensure-granted! caps :sign)
             (let [seed (read-bytes! instance (aget args 0) 32)
                   msg (read-bytes! instance (aget args 1) (aget args 2))
                   sig (ed/sign seed msg)]
               (write-bytes! instance (aget args 3) (aget args 4) sig)))))

(defn- verify-host-fn
  "`(pub-ptr pub-len msg-ptr msg-len sig-ptr sig-len) -> 1|0`. `#{:crypto}`."
  [caps _limits-state]
  (host-fn "verify" (mapv valtype [:i32 :i32 :i32 :i32 :i32 :i32]) ValType/I32
           (fn [instance args]
             (ensure-granted! caps :verify)
             (let [pub (read-bytes! instance (aget args 0) (aget args 1))
                   msg (read-bytes! instance (aget args 2) (aget args 3))
                   sig (read-bytes! instance (aget args 4) (aget args 5))]
               (if (ed/verify pub msg sig) 1 0)))))

(defn- sha256-hex-host-fn
  "`(ptr len out-ptr out-cap) -> bytes-written|-1`. Writes the lowercase
  hex SHA-256 digest of the input bytes. `#{:crypto}`."
  [caps _limits-state]
  (host-fn "sha256_hex" (mapv valtype [:i32 :i32 :i32 :i32]) ValType/I32
           (fn [instance args]
             (ensure-granted! caps :sha256-hex)
             (let [bs (read-bytes! instance (aget args 0) (aget args 1))
                   digest (.digest (MessageDigest/getInstance "SHA-256") bs)
                   hex (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))]
               (write-bytes! instance (aget args 2) (aget args 3) (.getBytes hex "UTF-8"))))))

;; ── outbound-HTTP hardening (guest-triggered network calls only) ──────────
;;
;; `http-post-host-fn`'s URL is entirely guest-controlled (read straight out
;; of guest linear memory) and lease/tick execution
;; (`kototama.fleet`/`kototama.fleet-exec`'s `run-loop-step`/`run-daemon!`/
;; `recovery-pass!`) is fully synchronous and single-threaded per tick -- a
;; guest granted `:http-post` that points the POST at a slow-loris /
;; non-responding endpoint could otherwise block the host thread
;; indefinitely, stalling the whole daemon process for every other tenant
;; sharing it (a DoS, not just an SSRF concern). Both `http-post-host-fn`
;; and `anthropic-infer` below share the same connect/request timeouts so a
;; non-responding endpoint fails fast (in-band -1 / nil) instead of hanging.
;; This part -- the DoS fix -- is complete and unconditional.
;;
;; `blocked-http-post-destination?` below is a SEPARATE, weaker mitigation:
;; best-effort SSRF hardening against literal/naive targets (a guest that
;; hardcodes "http://127.0.0.1/..." or "http://169.254.169.254/...", the
;; overwhelmingly common real-world shape of this bug class), NOT a proof
;; against a determined adversary who controls DNS for their own domain
;; (DNS rebinding: resolve to a public IP when checked, then to
;; 127.0.0.1/an internal address at the ACTUAL connection time a moment
;; later, since `java.net.http.HttpClient` re-resolves the hostname
;; independently and there is no supported per-request hook in its public
;; API (`HttpClient.Builder`, JDK 24) to pin the connection to a
;; specific, pre-validated `InetAddress` -- confirmed absent by inspecting
;; the Builder's method set). A same-process fix exists in principle
;; (rewrite the connection URI to the validated IP literal, preserve TLS
;; SNI to the original host via `SSLParameters.setServerNames`) but was
;; deliberately NOT implemented here: doing that correctly requires also
;; controlling what hostname the JDK's TLS endpoint-identification
;; algorithm validates the peer certificate against, which is
;; implementation-detail-sensitive territory -- getting it subtly wrong
;; would silently weaken certificate hostname verification for every
;; legitimate HTTPS call this function makes, trading a narrower SSRF gap
;; for a broader MITM one. That tradeoff was judged worse than shipping
;; an honestly-scoped, narrower mitigation. Closing the DNS-rebind gap
;; for real needs either an HTTP client with a pluggable resolver/
;; connector (e.g. OkHttp's `Dns`/`EventListener`) or a vetted, tested
;; SNI-preserving IP-pinning implementation as a dedicated follow-up --
;; not a rushed addition here.

(def ^:private http-connect-timeout
  "How long to wait for the TCP/TLS handshake before giving up."
  (Duration/ofSeconds 5))

(def ^:private http-request-timeout
  "How long to wait for the whole request (handshake + send + response)
  before giving up -- guards a peer that accepts the connection but then
  never responds (slow-loris), which `http-connect-timeout` alone does not."
  (Duration/ofSeconds 5))

(defn- timed-http-client
  "A fresh `HttpClient` with `http-connect-timeout` wired in (same shape
  `kototama.fleet-store/http-client` already establishes for the B2 native
  API calls elsewhere in this repo, `.connectTimeout` on the builder)."
  []
  (-> (HttpClient/newBuilder)
      (.connectTimeout http-connect-timeout)
      .build))

(defn- ipv6-unique-local?
  "True when ADDR is an IPv6 Unique Local Address (RFC 4193, fc00::/7) --
  the modern private-addressing scheme Docker/Kubernetes/most private
  cloud networks actually assign today. `Inet6Address.isSiteLocalAddress`
  only recognizes the legacy, deprecated fec0::/10 prefix, not fc00::/7,
  so without this explicit check a real internal fc00::/fd00::-range
  target would sail through unblocked."
  [^InetAddress addr]
  (and (instance? Inet6Address addr)
       (let [b (.getAddress addr)]
         (= 0xfc (bit-and (aget b 0) 0xfe)))))

(defn- blocked-network-address?
  "True when ADDR (a resolved `java.net.InetAddress`) is loopback /
  link-local / site-local (RFC1918) / IPv6 unique-local (RFC4193,
  fc00::/7) / multicast / any-local, or the well-known cloud-metadata
  address 169.254.169.254 -- checked explicitly as defense-in-depth even
  though `isLinkLocalAddress` already covers the 169.254.0.0/16 block
  that address lives in."
  [^InetAddress addr]
  (or (.isLoopbackAddress addr)
      (.isLinkLocalAddress addr)
      (.isSiteLocalAddress addr)
      (ipv6-unique-local? addr)
      (.isMulticastAddress addr)
      (.isAnyLocalAddress addr)
      (= "169.254.169.254" (.getHostAddress addr))))

(defn- blocked-http-post-destination?
  "True when URL's host is missing/unparseable, or resolves to ANY address
  `blocked-network-address?` flags -- fail CLOSED (an unresolvable host, or
  any exception while resolving, is treated as blocked, never allowed
  through). Best-effort hardening against LITERAL/naive SSRF targets (a
  guest hardcoding a loopback/private/metadata address), run BEFORE any
  real connection is attempted -- NOT a defense against DNS rebinding (a
  host resolving to a public address here, then to an internal one at
  actual connect time); see the namespace-level comment above for why
  that gap is not closed in this function."
  [^String url]
  (try
    (let [host (.getHost (URI/create url))]
      (boolean
       (or (nil? host)
           (str/blank? host)
           (some blocked-network-address? (InetAddress/getAllByName host)))))
    (catch Exception _ true)))

(defn- http-post-host-fn
  "`(url-ptr url-len body-ptr body-len out-ptr out-cap) -> bytes-written|-1`.
  Synchronous POST (guest calls block on it -- Chicory host functions have
  no async contract); writes the response body. `#{:network}`, metered
  against `:max-http-posts` (default 0 -- fully denied unless a caller's
  HostCaps explicitly raises the limit AND grants the import, matching
  `kototama.contract/default-runtime-limits`).

  A slow/non-responding destination fails fast (`http-connect-timeout`/
  `http-request-timeout`) instead of hanging the host thread -- this is a
  complete, unconditional fix for the DoS this was built to close. A
  destination that resolves to a LITERAL loopback/private/link-local/
  metadata address is refused (-1, the same in-band fail-closed
  convention every other quota/overflow case here uses -- never an
  exception) BEFORE any connection is attempted; this is best-effort SSRF
  hardening, not a DNS-rebinding-proof one (see
  `blocked-http-post-destination?`'s docstring).

  `contract/url-allowed?` layers an OPT-IN, caller-specified positive list
  on top (`:allowed-url-prefixes`, nil/unrestricted by default -- every
  existing caller that never set it behaves exactly as before): a caller
  wiring a guest that should only ever reach its own approved API
  endpoint can restrict it further than the unconditional denylist above,
  which only blocks internal/metadata destinations, not \"any public
  host\"."
  [caps limits-state]
  (host-fn "http_post" (mapv valtype [:i32 :i32 :i32 :i32 :i32 :i32]) ValType/I32
           (fn [instance args]
             (ensure-granted! caps :http-post)
             (if-not (within-count-limit? :http-posts :max-http-posts caps limits-state)
               -1
               (let [url (String. ^bytes (read-bytes! instance (aget args 0) (aget args 1)) "UTF-8")]
                 (if (or (blocked-http-post-destination? url)
                         (not (contract/url-allowed? (:limits caps) url)))
                   -1
                   (let [body (read-bytes! instance (aget args 2) (aget args 3))
                         req (-> (HttpRequest/newBuilder (URI/create url))
                                (.timeout http-request-timeout)
                                (.POST (HttpRequest$BodyPublishers/ofByteArray body))
                                .build)
                         resp (.send (timed-http-client) req (HttpResponse$BodyHandlers/ofByteArray))]
                     (swap! limits-state update :http-posts inc)
                     (write-bytes! instance (aget args 4) (aget args 5) (.body resp)))))))))

;; ── llm-infer's injected client: real Anthropic call in production,
;; fake `:infer-fn` in tests (same DI shape as `store` below) ──────────────

(def ^:private anthropic-messages-url "https://api.anthropic.com/v1/messages")
(def ^:private default-llm-model "claude-opus-4-8")
(def ^:private default-llm-max-tokens 1024)

(def ^:private llm-api-key-env-vars
  "Same resolution order `cloud_itonami.runtime/model-config` uses --
  ITO_MODEL_API_KEY takes precedence over the more generic provider-
  specific vars, ANTHROPIC_API_KEY is the last resort."
  ["ITO_MODEL_API_KEY" "OPENAI_API_KEY" "OPENCLAW_API_KEY" "HERMES_API_KEY" "ANTHROPIC_API_KEY"])

(defn resolve-llm-api-key
  "First non-blank value among `llm-api-key-env-vars`, or nil if every one
  of them is unset/blank -- callers treat nil as \"no key configured\",
  never throw."
  []
  (some (fn [env-var]
          (let [v (System/getenv env-var)]
            (when-not (str/blank? v) v)))
        llm-api-key-env-vars))

(defn- anthropic-infer
  "Calls the real Anthropic Messages API with PROMPT as a single user
  message, authenticated with API-KEY; returns the assistant's text
  reply, or nil on ANY failure (non-2xx status, network error, malformed
  JSON) -- never throws, so a caller can treat nil uniformly with \"no
  key configured\" and fail closed.

  `anthropic-messages-url` is a fixed constant (not guest-controlled), so
  no destination check is needed here the way `http-post-host-fn` needs
  one -- but the same `http-connect-timeout`/`http-request-timeout` apply
  so a non-responding/slow endpoint still fails fast (in-band nil, via the
  existing catch) instead of hanging the host thread for every tenant
  sharing this tick."
  [api-key prompt]
  (try
    (let [body (json/write-str {:model default-llm-model
                                :max_tokens default-llm-max-tokens
                                :messages [{:role "user" :content prompt}]})
          req (-> (HttpRequest/newBuilder (URI/create anthropic-messages-url))
                 (.header "content-type" "application/json")
                 (.header "x-api-key" api-key)
                 (.header "anthropic-version" "2023-06-01")
                 (.timeout http-request-timeout)
                 (.POST (HttpRequest$BodyPublishers/ofString body))
                 .build)
          resp (.send (timed-http-client) req (HttpResponse$BodyHandlers/ofString))]
      (when (<= 200 (.statusCode resp) 299)
        (let [resp-body (json/read-str (.body resp) :key-fn keyword)
              text (apply str (keep #(when (= "text" (:type %)) (:text %))
                                    (:content resp-body)))]
          (when-not (str/blank? text) text))))
    (catch Exception _ nil)))

(defn default-llm-client
  "Production `{:infer-fn}` backing `llm-infer-host-fn`: resolves the API
  key fresh on every call (env can change between calls in a long-lived
  tender process) and calls the real Anthropic Messages API, or returns
  nil (fail-closed) with no network call at all when no key is
  configured."
  []
  {:infer-fn (fn [prompt]
              (when-let [api-key (resolve-llm-api-key)]
                (anthropic-infer api-key prompt)))})

(defn- llm-infer-host-fn
  "`(prompt-ptr prompt-len out-ptr out-cap) -> bytes-written|-1`. Reads
  PROMPT out of guest memory, sends it to the injected LLM-CLIENT's
  `:infer-fn` as a single user message, and writes the text reply.
  `#{:network}`, metered against `:max-llm-infers` (default 0 -- fully
  denied unless a caller's HostCaps explicitly raises the limit AND
  grants the import, same convention `http-post-host-fn` uses).

  Fail-closed like every quota/overflow case here (-1, never an
  exception): `:infer-fn` returning nil (no API key configured, or the
  underlying call failed) is indistinguishable in-band from a metering
  denial or an oversized reply -- a well-behaved guest sees -1 either
  way and can't tell which, which is the point (it never gets to probe
  the host's credential state)."
  [caps limits-state llm-client]
  (host-fn "llm_infer" (mapv valtype [:i32 :i32 :i32 :i32]) ValType/I32
           (fn [instance args]
             (ensure-granted! caps :llm-infer)
             (if-not (within-count-limit? :llm-infers :max-llm-infers caps limits-state)
               -1
               (let [prompt (String. ^bytes (read-bytes! instance (aget args 0) (aget args 1)) "UTF-8")
                     text ((:infer-fn llm-client) prompt)]
                 (if (nil? text)
                   -1
                   (do (swap! limits-state update :llm-infers inc)
                       (write-bytes! instance (aget args 2) (aget args 3) (.getBytes ^String text "UTF-8")))))))))

(defn- log-read-host-fn
  "`(out-ptr out-cap) -> bytes-written|-1`. Reads the injected store's
  `:read-fn` (no args -> bytes) and writes it. `#{:storage}`, metered
  against `:max-log-read-bytes` (bytes actually read, not just requested)."
  [caps limits-state store]
  (host-fn "log_read" [ValType/I32 ValType/I32] ValType/I32
           (fn [instance args]
             (ensure-granted! caps :log-read)
             (let [bs ((:read-fn store))]
               (if (try-add-bytes! :log-read-bytes :max-log-read-bytes caps limits-state (count bs))
                 (write-bytes! instance (aget args 0) (aget args 1) bs)
                 -1)))))

(defn- log-write-host-fn
  "`(ptr len) -> 0|-1`. Appends the input bytes via the injected store's
  `:append-fn`. `#{:storage :write}` -- gated by `:allow-write-imports?`
  AND metered against `:max-log-write-bytes` (cumulative across this
  Instance's lifetime)."
  [caps limits-state store]
  (host-fn "log_write" [ValType/I32 ValType/I32] ValType/I32
           (fn [instance args]
             (ensure-granted! caps :log-write)
             (let [bs (read-bytes! instance (aget args 0) (aget args 1))]
               (if (try-add-bytes! :log-write-bytes :max-log-write-bytes caps limits-state (count bs))
                 (do ((:append-fn store) bs) 0)
                 -1)))))

(defn- clock-monotonic-host-fn [caps _limits-state]
  (host-fn "clock_monotonic" [] ValType/I64
           (fn [_instance _args]
             (ensure-granted! caps :clock-monotonic)
             (System/currentTimeMillis))))

(defn in-memory-store
  "A trivial `{:read-fn :append-fn}` log store backing `log-read`/
  `log-write`, an atom of concatenated bytes -- the default for tests/
  standalone use. A production tender injects its own (file, R2,
  whatever backs its actual log)."
  []
  (let [state (atom (byte-array 0))]
    {:read-fn (fn [] @state)
     :append-fn (fn [bs] (swap! state #(byte-array (concat % bs))))}))

(defn fuel-listener*
  "Returns `[listener counter-atom]` so a session can report how many
   Wasm instructions were executed (maturity R1 observability)."
  [limit]
  (let [n (atom 0)
        listener (reify ExecutionListener
                   (onExecution [_ _instruction _stack]
                     (when (> (swap! n inc) limit)
                       (throw (ex-info "kototama.tender: wasm execution exceeded fuel limit"
                                       {:kototama.tender/problem :fuel-exhausted
                                        :kototama.tender/fuel-limit limit
                                        :kototama.tender/fuel-used @n})))))]
    [listener n]))

(defn fuel-listener
  "Same `ExecutionListener` per-instruction counting hook
  `kotoba.wasm-exec/fuel-listener` uses (verified against Chicory
  1.4.0's own dispatch loop there) -- traps a runaway/looping guest
  instead of hanging the tender process.

   Returns only the listener (backward compatible). Prefer
   `fuel-listener*` when the caller needs the counter atom for a
   post-run report (`open-session` / `run-report`)."
  [limit]
  (first (fuel-listener* limit)))

(def default-fuel-limit
  "Default max Wasm-instruction budget per `instantiate`d guest, absent an
  explicit `:fuel` in `opts`. Same order of magnitude reasoning as
  `kotoba.wasm-exec/default-fuel-limit`: generous for legitimate small
  guests, still trips a genuinely unbounded loop in a fraction of a second."
  5000000)

(defn- memory-limits-for
  "Chicory `MemoryLimits` capping how far the guest's `memory.grow` can
  reach, to `min(caps-limit, module's-own-declared-max)` -- the module's
  OWN declared INITIAL page count is read and never overridden (a guest
  that needs N pages to even start must still get them; only the ceiling
  on growth is capped). `nil` when MODULE declares no memory section at
  all (a memory-less guest, e.g. one only calling `clock-monotonic`, has
  nothing to limit). Same approach `aiueos.execute/memory-limits-for`
  establishes,
  via Chicory's STABLE (not :unsafe/:experimental, unlike the fuel
  listener above) `Instance.Builder/withMemoryLimits` API."
  [module caps-limit]
  (let [section (.memorySection module)]
    (when (.isPresent section)
      (let [own-limits (.limits (.getMemory (.get section) 0))]
        (MemoryLimits. (.initialPages own-limits) (min caps-limit (.maximumPages own-limits)))))))

(defn open-session
  "Parse WASM-BYTES and build a Chicory session (Instance + observability).

   Returns:
     {:instance      Chicory Instance
      :limits-state  atom of RuntimeLimits counters (http-posts, llm-infers, …)
      :fuel-used     atom of instruction count so far
      :fuel-limit    long
      :caps          normalized HostCaps
      :requested     vector of granted import ids wired into the Instance
      :validation    contract/validate-import-surface result}

   Fail-closed pre-flight via `contract/validate-import-surface`. Each
   HostFunction re-checks grants at call time. Memory growth capped to
   HostCaps `:max-memory-pages`. Maturity R1 (ADR-2607101200).

   opts: :store, :llm-client, :fuel (see previous instantiate docstring)."
  ([wasm-bytes requested-imports host-caps]
   (open-session wasm-bytes requested-imports host-caps {}))
  ([wasm-bytes requested-imports host-caps
    {:keys [store llm-client fuel]
     :or {store (in-memory-store) llm-client (default-llm-client) fuel default-fuel-limit}}]
   (let [caps (contract/host-caps host-caps)
         validation (contract/validate-import-surface requested-imports caps)]
     (when-not (:ok? validation)
       (throw (ex-info "kototama.tender: import surface rejected by contract"
                       {:kototama.tender/rejected requested-imports
                        :kototama.tender/errors (:errors validation)})))
     (let [limits-state (new-limits-state)
           fn-by-id {:gen-keypair #(gen-keypair-host-fn caps limits-state)
                     :sign #(sign-host-fn caps limits-state)
                     :verify #(verify-host-fn caps limits-state)
                     :sha256-hex #(sha256-hex-host-fn caps limits-state)
                     :http-post #(http-post-host-fn caps limits-state)
                     :llm-infer #(llm-infer-host-fn caps limits-state llm-client)
                     :log-read #(log-read-host-fn caps limits-state store)
                     :log-write #(log-write-host-fn caps limits-state store)
                     :clock-monotonic #(clock-monotonic-host-fn caps limits-state)}
           host-fns (mapv (fn [id] ((get fn-by-id id))) (:requested validation))
           imports (-> (ImportValues/builder)
                      (.addFunction (into-array ImportFunction host-fns))
                      .build)
           module (Parser/parse ^bytes wasm-bytes)
           mem-limits (memory-limits-for module (:max-memory-pages (:limits caps)))
           [listener fuel-used] (fuel-listener* fuel)
           builder (-> (Instance/builder module)
                      (.withImportValues imports)
                      (.withUnsafeExecutionListener listener))
           instance (.build (if mem-limits (.withMemoryLimits builder mem-limits) builder))]
       {:instance instance
        :limits-state limits-state
        :fuel-used fuel-used
        :fuel-limit fuel
        :caps caps
        :requested (:requested validation)
        :validation validation}))))

(defn instantiate
  "Backward-compatible Instance-only entry. Prefer `open-session` /
   `run-report` for maturity R1 observability."
  ([wasm-bytes requested-imports host-caps]
   (instantiate wasm-bytes requested-imports host-caps {}))
  ([wasm-bytes requested-imports host-caps opts]
   (:instance (open-session wasm-bytes requested-imports host-caps opts))))

(defn call-export
  "Invoke an already-built Instance's 0-arity EXPORT-NAME and return its
  single i32/i64 result as a long. Throws (Chicory's own exception) if
  INSTANCE has no such export -- see `has-export?` to check first."
  [instance export-name]
  (aget ^longs (.apply (.export instance export-name) (long-array 0)) 0))

(defn call-main
  "`(call-export instance \"main\")` -- kept as the historical name every
  existing caller/test uses; `main` is still the one export every guest
  is required to have."
  [instance]
  (call-export instance "main"))

(defn has-export?
  "True when INSTANCE exports a function named EXPORT-NAME. For a caller
  that wants to dispatch an OPTIONAL export (not every guest implements
  every trigger -- see `dispatch-trigger`) without relying on Chicory's
  exception type as control flow."
  [instance export-name]
  (try (some? (.export instance export-name))
       (catch Exception _ false)))

(def trigger->export
  "ADR-2607082400: the export-name mapping kotoba-server's own
  `net_actor.rs::trigger_name` uses for its four `ComponentTrigger`
  kinds (Run/Http/Tick/Kse) -- kept as data here so a caller dispatching
  by trigger keyword doesn't hand-roll the string shape. NOTE this is
  the kotoba-server WIRE VOCABULARY, not a claim that kototama can host
  kotoba-server's own guests: those are WASM Components (`kotoba-clj`
  compiler, WIT canonical ABI) and Chicory -- kototama's only engine --
  rejects that binary format outright (verified: `Parser/parse` throws
  `MalformedException` on a real compiled mesh-guest .wasm, version
  header `[13,0,1,0]` vs the core-module `[1,0,0,0]` it expects). This
  map exists so a FUTURE `.kotoba`-language guest with more than one
  export can be dispatched by the same trigger vocabulary, not to
  imply today's mesh guests are already hostable here."
  {:run "run" :http "on-http" :tick "on-tick" :kse "on-kse"})

(defn dispatch-trigger
  "Invoke the export for TRIGGER-KIND (one of `(keys trigger->export)`)
  if INSTANCE has it. Returns `{:dispatched? true :result <long>}`, or
  `{:dispatched? false}` when the guest doesn't implement that trigger
  -- not an error, the same non-event kotoba-server's own route table
  represents when a component isn't bound to a given trigger kind.
  Throws for an unrecognised TRIGGER-KIND (a caller bug, not a guest
  shape question)."
  [instance trigger-kind]
  (let [export-name (get trigger->export trigger-kind)]
    (when-not export-name
      (throw (ex-info "kototama.tender: unknown trigger kind"
                      {:kototama.tender/trigger-kind trigger-kind
                       :kototama.tender/known-kinds (set (keys trigger->export))})))
    (if (has-export? instance export-name)
      {:dispatched? true :result (call-export instance export-name)}
      {:dispatched? false})))

(defn session-call-main
  "Invoke `main` on an `open-session` map; returns the i32/i64 result."
  [session]
  (call-main (:instance session)))

(defn run-main
  "`instantiate` + `call-main` in one call. See `instantiate` for the
  requested-imports/host-caps/opts contract."
  ([wasm-bytes requested-imports host-caps]
   (call-main (instantiate wasm-bytes requested-imports host-caps)))
  ([wasm-bytes requested-imports host-caps opts]
   (call-main (instantiate wasm-bytes requested-imports host-caps opts))))

(defn run-report
  "`open-session` + `call-main` with a structured post-run report.

   Returns {:ok? true :result long :fuel-used n :fuel-limit n
            :limits {...} :requested [...] :caps HostCaps}
   or {:ok? false :error ex-data-or-message ...} on denial/fuel/trap."
  ([wasm-bytes requested-imports host-caps]
   (run-report wasm-bytes requested-imports host-caps {}))
  ([wasm-bytes requested-imports host-caps opts]
   (try
     (let [session (open-session wasm-bytes requested-imports host-caps opts)
           result (session-call-main session)]
       {:ok? true
        :result result
        :fuel-used @(:fuel-used session)
        :fuel-limit (:fuel-limit session)
        :limits @(:limits-state session)
        :requested (:requested session)
        :caps (:caps session)})
     (catch Exception e
       {:ok? false
        :error (or (ex-data e) {:message (.getMessage e)})
        :message (.getMessage e)}))))

(defn inspect-module
  "Parse WASM-BYTES (no Instantiation) and report structural surface:

     {:magic-ok? bool
      :byte-count n
      :export-names [\"main\" …]
      :import-names [\"gen_keypair\" …]   ; field names under module \"kotoba\"
      :import-modules [\"kotoba\" …]
      :has-memory? bool
      :has-main? bool}

   Pure observation — does not run guest code or check HostCaps. Used by
   CLI doctor / maturity gates before a run."
  [^bytes wasm-bytes]
  (let [module (Parser/parse wasm-bytes)
        ;; Chicory 1.4: exportSection/importSection are concrete sections
        ;; (not Optional); memorySection is Optional.
        export-names (try
                       (let [es (.exportSection module)
                             n (.exportCount es)]
                         (mapv #(.name (.getExport es %)) (range n)))
                       (catch Exception _ []))
        imports (try
                  (let [isec (.importSection module)
                        n (.importCount isec)]
                    (mapv (fn [i]
                            (let [imp (.getImport isec i)]
                              {:module (.module imp)
                               :name (.name imp)}))
                          (range n)))
                  (catch Exception _ []))
        has-memory? (try
                      (let [ms (.memorySection module)]
                        (if (instance? java.util.Optional ms)
                          (.isPresent ^java.util.Optional ms)
                          (some? ms)))
                      (catch Exception _ false))]
    {:magic-ok? (and (>= (alength wasm-bytes) 4)
                     (= (bit-and (aget wasm-bytes 0) 0xff) 0x00)
                     (= (bit-and (aget wasm-bytes 1) 0xff) 0x61)
                     (= (bit-and (aget wasm-bytes 2) 0xff) 0x73)
                     (= (bit-and (aget wasm-bytes 3) 0xff) 0x6d))
     :byte-count (alength wasm-bytes)
     :export-names export-names
     :import-names (mapv :name imports)
     :import-modules (vec (distinct (map :module imports)))
     :imports imports
     :has-memory? has-memory?
     :has-main? (boolean (some #{"main"} export-names))}))

(defn read-memory-string
  "Read a UTF-8 string of LEN bytes at PTR out of INSTANCE's memory -- for
  a caller inspecting a result `main` wrote into a buffer it provided."
  [instance ptr len]
  (String. ^bytes (read-bytes! instance ptr len) "UTF-8"))
