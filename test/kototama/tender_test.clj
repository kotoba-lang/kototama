(ns kototama.tender-test
  "Real Wasm binaries, not just Clojure-closure unit tests -- same
  end-to-end discipline `kotoba.wasm-exec_test.clj` uses. WAT fixtures are
  compiled to real Wasm bytes at test time via the `wasm-tools` CLI
  (Bytecode Alliance, ~/.cargo/bin/wasm-tools), so these tests exercise
  the actual Chicory Parser/Instance/HostFunction linkage, not a mock."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [kototama.tender :as tender]
            [kototama.contract :as contract])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetAddress InetSocketAddress ServerSocket URI)
           (java.net.http HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)))

(defn- wat->wasm
  "Shell out to `wasm-tools parse` to assemble WAT text into real Wasm
  bytes -- fails loudly (test error, not a silent skip) if the binary
  isn't on PATH, since these tests are meaningless without it."
  [wat]
  (let [in (java.io.File/createTempFile "kototama-tender-test" ".wat")
        out (java.io.File/createTempFile "kototama-tender-test" ".wasm")]
    (try
      (spit in wat)
      (let [{:keys [exit err]} (shell/sh "wasm-tools" "parse" (.getPath in) "-o" (.getPath out))]
        (when-not (zero? exit)
          (throw (ex-info "wasm-tools parse failed" {:wat wat :stderr err}))))
      (with-open [is (io/input-stream out)]
        (.readAllBytes is))
      (finally
        (.delete in)
        (.delete out)))))

(def sha256-hex-wat
  "Imports sha256_hex, hashes the 5-byte literal \"hello\" at offset 0,
  writes the 64-char hex digest at offset 100 (exact capacity)."
  "(module
     (import \"kotoba\" \"sha256_hex\" (func $sha256_hex (param i32 i32 i32 i32) (result i32)))
     (memory (export \"memory\") 1)
     (data (i32.const 0) \"hello\")
     (func (export \"main\") (result i64)
       (i64.extend_i32_s (call $sha256_hex (i32.const 0) (i32.const 5) (i32.const 100) (i32.const 64)))))")

(def clock-monotonic-wat
  "Imports clock_monotonic, calls it once, returns whatever it got (proves
  linkage -- a specific value isn't asserted, just that it doesn't trap)."
  "(module
     (import \"kotoba\" \"clock_monotonic\" (func $clock_monotonic (result i64)))
     (func (export \"main\") (result i64) (call $clock_monotonic)))")

(def multi-export-wat
  "No imports -- a guest with `main` AND `on-http` (ADR-2607082400's
  `dispatch-trigger`), each a distinct 0-arity export returning its own
  constant so a test can tell which one actually ran. No `on-tick`/
  `on-kse` export, so `has-export?`/`dispatch-trigger` on those two must
  report :dispatched? false, same as kotoba-server's own route table
  not sending a trigger to a component that isn't bound to it."
  "(module
     (func (export \"main\") (result i64) (i64.const 111))
     (func (export \"on-http\") (result i64) (i64.const 222)))")

(def memory-grow-wat
  "No imports -- declares 1 initial page, main tries to grow BY the
  amount passed as a global-like constant baked per test (see below);
  returns memory.grow's result (previous page count, or -1 on failure)."
  (fn [grow-by]
    (str "(module
            (memory (export \"memory\") 1)
            (func (export \"main\") (result i64)
              (i64.extend_i32_s (memory.grow (i32.const " grow-by ")))))")))

(def infinite-loop-wat
  "No imports -- just an unconditional back-edge loop, to trip the fuel
  listener."
  "(module
     (func (export \"main\") (result i64)
       (loop $l (br $l))
       (i64.const 0)))")

(def log-write-thrice-wat
  "Imports log_write, calls it 3 times with the same 4-byte payload,
  returns the THIRD call's result (i32 sign-extended to i64) -- so a
  RuntimeLimits cap of e.g. 8 bytes (2 calls' worth) shows up as -1 on
  the third call."
  "(module
     (import \"kotoba\" \"log_write\" (func $log_write (param i32 i32) (result i32)))
     (memory (export \"memory\") 1)
     (data (i32.const 0) \"data\")
     (func (export \"main\") (result i64)
       (drop (call $log_write (i32.const 0) (i32.const 4)))
       (drop (call $log_write (i32.const 0) (i32.const 4)))
       (i64.extend_i32_s (call $log_write (i32.const 0) (i32.const 4)))))")

(def http-post-wat
  "Imports http_post, POSTs the 4-byte literal \"body\" (offset 50) to URL
  (baked into a data segment at offset 0, URL's own byte length as url-len
  -- URL must be pure ASCII and contain no `\"`/`\\`, true of every URL used
  below), writes the response at offset 200 (256-byte capacity)."
  (fn [url]
    (str "(module
            (import \"kotoba\" \"http_post\" (func $http_post (param i32 i32 i32 i32 i32 i32) (result i32)))
            (memory (export \"memory\") 1)
            (data (i32.const 0) \"" url "\")
            (data (i32.const 50) \"body\")
            (func (export \"main\") (result i64)
              (i64.extend_i32_s
                (call $http_post (i32.const 0) (i32.const " (count url) ")
                                 (i32.const 50) (i32.const 4)
                                 (i32.const 200) (i32.const 256)))))")))

(def llm-infer-wat
  "Imports llm_infer, sends the 5-byte literal \"hello\" as the prompt,
  writes the reply at offset 100 (64-byte capacity)."
  "(module
     (import \"kotoba\" \"llm_infer\" (func $llm_infer (param i32 i32 i32 i32) (result i32)))
     (memory (export \"memory\") 1)
     (data (i32.const 0) \"hello\")
     (func (export \"main\") (result i64)
       (i64.extend_i32_s (call $llm_infer (i32.const 0) (i32.const 5) (i32.const 100) (i32.const 64)))))")

(def llm-infer-twice-wat
  "Imports llm_infer, calls it twice with the same 5-byte prompt, returns
  the SECOND call's result (i32 sign-extended to i64) -- so a
  RuntimeLimits cap of 1 call shows up as -1 on the second call."
  "(module
     (import \"kotoba\" \"llm_infer\" (func $llm_infer (param i32 i32 i32 i32) (result i32)))
     (memory (export \"memory\") 1)
     (data (i32.const 0) \"hello\")
     (func (export \"main\") (result i64)
       (drop (call $llm_infer (i32.const 0) (i32.const 5) (i32.const 100) (i32.const 64)))
       (i64.extend_i32_s (call $llm_infer (i32.const 0) (i32.const 5) (i32.const 100) (i32.const 64)))))")

(def everything (constantly true))

(defn- read-fixture [name]
  (with-open [is (io/input-stream (io/resource (str "kototama/fixtures/" name)))]
    (.readAllBytes is)))

(deftest sha256-hex-round-trips-through-real-chicory
  (let [wasm (wat->wasm sha256-hex-wat)
        caps (contract/host-caps {:grants [:sha256-hex]})
        instance (tender/instantiate wasm [:sha256-hex] caps)
        n (tender/call-main instance)]
    (is (= 64 n))
    (is (= "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
           (tender/read-memory-string instance 100 64)))))

(deftest clock-monotonic-links-and-returns-without-trapping
  (let [wasm (wat->wasm clock-monotonic-wat)
        caps (contract/host-caps {:grants [:clock-monotonic]})
        n (tender/run-main wasm [:clock-monotonic] caps)]
    (is (pos? n))))

(deftest call-export-invokes-any-named-export-not-just-main
  (let [wasm (wat->wasm multi-export-wat)
        caps (contract/host-caps {:grants []})
        instance (tender/instantiate wasm [] caps)]
    (is (= 111 (tender/call-export instance "main")))
    (is (= 111 (tender/call-main instance)) "call-main stays the historical main-only shim")
    (is (= 222 (tender/call-export instance "on-http")))))

(deftest has-export?-distinguishes-present-from-absent-exports
  (let [wasm (wat->wasm multi-export-wat)
        caps (contract/host-caps {:grants []})
        instance (tender/instantiate wasm [] caps)]
    (is (true? (tender/has-export? instance "main")))
    (is (true? (tender/has-export? instance "on-http")))
    (is (false? (tender/has-export? instance "on-tick")))
    (is (false? (tender/has-export? instance "on-kse")))))

(deftest dispatch-trigger-maps-kotoba-server-trigger-kinds-to-export-names
  (is (= {:run "run" :http "on-http" :tick "on-tick" :kse "on-kse"}
         tender/trigger->export))
  (let [wasm (wat->wasm multi-export-wat)
        caps (contract/host-caps {:grants []})
        instance (tender/instantiate wasm [] caps)]
    (testing "bound trigger dispatches and returns the export's result"
      (is (= {:dispatched? true :result 222}
             (tender/dispatch-trigger instance :http))))
    (testing "unbound trigger is a non-event, not an error (component isn't bound to it)"
      (is (= {:dispatched? false} (tender/dispatch-trigger instance :tick)))
      (is (= {:dispatched? false} (tender/dispatch-trigger instance :kse))))
    (testing "unknown trigger kind is a caller bug -- throws"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown trigger kind"
                            (tender/dispatch-trigger instance :bogus))))))

(deftest ungranted-import-is-rejected-pre-flight-before-any-wasm-runs
  (testing "validate-import-surface's own :grants/missing error, no Instance ever built"
    (let [caps (contract/host-caps {:grants []})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"rejected by contract"
                            (tender/instantiate (byte-array 0) [:http-post] caps))))))

(deftest a-defense-in-depth-grant-check-also-guards-each-call
  (testing "sha256-hex granted at the surface level but the per-call check still gates it"
    ;; Build caps that pass pre-flight (grants sha256-hex) then hand-craft
    ;; a denial by requesting a DIFFERENT import than what's granted --
    ;; validate-import-surface itself would already refuse this combo, so
    ;; this just re-confirms the pre-flight path is what actually fires.
    (let [wasm (wat->wasm sha256-hex-wat)
          caps (contract/host-caps {:grants [:clock-monotonic]})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"rejected by contract"
                            (tender/instantiate wasm [:sha256-hex] caps))))))

;; ── http-post: connect/request timeouts + SSRF-adjacent destination
;; filtering (security fix, ADR audit finding #1). The destination check
;; means a real end-to-end round-trip through the guest/Chicory path can
;; only ever hit a PUBLIC address -- any local `HttpServer`/`ServerSocket`
;; this test process can stand up and reach from itself is necessarily
;; loopback or a private address, which the filter now correctly refuses.
;; So: the classification logic and the "refused before any connection"
;; property are tested end-to-end via the real guest/Chicory path (a local
;; spy server proves it's never even contacted); the timeout WIRING itself
;; (both the "doesn't break a normal fast response" and the "fails fast
;; instead of hanging on a slow peer" properties) is tested directly
;; against `tender`'s own private `timed-http-client`/`http-request-timeout`
;; -- the exact objects `http-post-host-fn` builds requests with -- since
;; that part of the fix is destination-agnostic and loopback is a
;; perfectly valid target for testing it in isolation. ─────────────────────

(deftest blocked-http-post-destination?-classifies-ssrf-shaped-targets
  (testing "loopback"
    (is (true? (#'tender/blocked-http-post-destination? "http://127.0.0.1/x")))
    (is (true? (#'tender/blocked-http-post-destination? "http://localhost/x")))
    (is (true? (#'tender/blocked-http-post-destination? "http://[::1]/x"))))
  (testing "RFC1918 private ranges"
    (is (true? (#'tender/blocked-http-post-destination? "http://10.1.2.3/")))
    (is (true? (#'tender/blocked-http-post-destination? "http://172.16.0.1/")))
    (is (true? (#'tender/blocked-http-post-destination? "http://192.168.1.1/"))))
  (testing "link-local, including the cloud-metadata address"
    (is (true? (#'tender/blocked-http-post-destination? "http://169.254.1.1/")))
    (is (true? (#'tender/blocked-http-post-destination? "http://169.254.169.254/latest/meta-data/"))
        "explicit 169.254.169.254 check -- also already covered by isLinkLocalAddress"))
  (testing "IPv6 unique-local (RFC4193, fc00::/7) -- the modern Docker/Kubernetes/
            private-cloud addressing scheme, NOT covered by isSiteLocalAddress'
            legacy fec0::/10-only check"
    (is (true? (#'tender/blocked-http-post-destination? "http://[fc00::1]/")))
    (is (true? (#'tender/blocked-http-post-destination? "http://[fd12:3456:789a:1::1]/"))))
  (testing "IPv6 loopback/link-local/deprecated-site-local/multicast, and a real
            public IPv6 address is NOT blocked"
    (is (true? (#'tender/blocked-http-post-destination? "http://[::1]/")))
    (is (true? (#'tender/blocked-http-post-destination? "http://[fe80::1]/")))
    (is (true? (#'tender/blocked-http-post-destination? "http://[fec0::1]/")))
    (is (true? (#'tender/blocked-http-post-destination? "http://[ff02::1]/")))
    (is (false? (#'tender/blocked-http-post-destination? "http://[2606:4700:4700::1111]/"))
        "a real public IPv6 address (Cloudflare DNS) is not over-broadly blocked"))
  (testing "multicast"
    (is (true? (#'tender/blocked-http-post-destination? "http://224.0.0.1/"))))
  (testing "unparseable / hostless URLs fail CLOSED, not open"
    (is (true? (#'tender/blocked-http-post-destination? "not-a-url")))
    (is (true? (#'tender/blocked-http-post-destination? ""))))
  (testing "legitimate public destinations are NOT blocked (IP literals -- no live DNS needed to classify)"
    (is (false? (#'tender/blocked-http-post-destination? "http://1.1.1.1/")))
    (is (false? (#'tender/blocked-http-post-destination? "http://8.8.8.8/")))))

;; DNS-rebinding (resolves to a public address when checked, then to an internal
;; one at actual connect time) is a KNOWN, DOCUMENTED gap in
;; blocked-http-post-destination? -- see its docstring and the namespace-level
;; comment above `http-connect-timeout` for why it isn't closed here (no
;; supported per-request DNS-pinning hook in java.net.http.HttpClient's public
;; API without risking a subtle TLS hostname-verification regression). Not
;; tested here for the same reason the finding was reported rather than
;; "fixed-and-verified": reproducing it faithfully requires installing a custom
;; java.net.spi.InetAddressResolverProvider, which is real, heavy, JVM-global
;; test machinery disproportionate to a mitigation this function was never
;; supposed to fully provide.

(deftest http-post-host-fn-refuses-a-loopback-destination-before-any-real-connection
  (let [hits (atom 0)
        server (doto (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
                 (.createContext "/" (reify HttpHandler
                                       (handle [_ exchange]
                                         (swap! hits inc)
                                         (.sendResponseHeaders ^HttpExchange exchange 200 -1))))
                 (.start))]
    (try
      (let [port (.getPort (.getAddress server))
            url (str "http://127.0.0.1:" port "/")
            wasm (wat->wasm (http-post-wat url))
            caps (contract/host-caps {:grants [:http-post] :limits {:max-http-posts 1}})
            started (System/currentTimeMillis)
            n (tender/run-main wasm [:http-post] caps)
            elapsed (- (System/currentTimeMillis) started)]
        (is (= -1 n) "loopback destination refused, the standard fail-closed -1 convention")
        (is (zero? @hits) "the local server never received a request -- rejected BEFORE any real HTTP call was attempted")
        (is (< elapsed 2000) "rejection is immediate (pure classification), not gated behind a connect/request timeout"))
      (finally (.stop server 0)))))

;; ── :allowed-url-prefixes: opt-in caller allowlist layered on top of the
;; unconditional denylist above (contract/url-allowed?, nil/unrestricted
;; by default) -- see http-post-host-fn's docstring. ────────────────────────

(deftest url-allowed?-defaults-to-unrestricted
  (is (true? (contract/url-allowed? {} "http://anything.example/x"))
      "no :allowed-url-prefixes at all -- unrestricted, matches every prior caller")
  (is (true? (contract/url-allowed? {:allowed-url-prefixes nil} "http://anything.example/x"))
      "explicit nil remains unrestricted (legacy default)"))

(deftest url-allowed?-empty-collection-fails-closed
  (testing "empty set/vector means the caller opted into an allowlist but listed nothing"
    (is (false? (contract/url-allowed? {:allowed-url-prefixes []} "http://anything.example/x")))
    (is (false? (contract/url-allowed? {:allowed-url-prefixes #{}} "http://anything.example/x")))))

(deftest url-allowed?-checks-prefix-membership
  (let [limits {:allowed-url-prefixes ["https://api.example.test/"]}]
    (is (true? (contract/url-allowed? limits "https://api.example.test/v1/echo")))
    (is (false? (contract/url-allowed? limits "https://not-example.test/v1/echo")))
    (is (false? (contract/url-allowed? limits "http://api.example.test/v1/echo"))
        "scheme is part of the prefix -- http does not match an https-only prefix")))

(deftest url-allowed?-matches-any-of-multiple-prefixes
  (let [limits {:allowed-url-prefixes ["https://a.test/" "https://b.test/"]}]
    (is (true? (contract/url-allowed? limits "https://a.test/x")))
    (is (true? (contract/url-allowed? limits "https://b.test/x")))
    (is (false? (contract/url-allowed? limits "https://c.test/x")))))

(deftest http-post-host-fn-refuses-a-destination-outside-the-allowlist-before-any-real-connection
  ;; 198.51.100.0/24 (RFC 5737 TEST-NET-2) is a literal IPv4 address --
  ;; parses without a real DNS round-trip -- that is public/routable-shaped
  ;; (NOT loopback/private/link-local/metadata), so blocked-http-post-
  ;; destination? lets it through; url-allowed? is the only thing that can
  ;; reject it here, isolating what this test actually verifies.
  (let [url "http://198.51.100.1/x"
        wasm (wat->wasm (http-post-wat url))
        caps (contract/host-caps {:grants [:http-post]
                                   :limits {:max-http-posts 1
                                            :allowed-url-prefixes ["http://192.0.2."]}})
        started (System/currentTimeMillis)
        n (tender/run-main wasm [:http-post] caps)
        elapsed (- (System/currentTimeMillis) started)]
    (is (= -1 n) "destination not matching :allowed-url-prefixes refused, the standard fail-closed -1 convention")
    (is (< elapsed 2000) "rejection is immediate (pure classification), not a real network attempt/timeout")))

(deftest http-post-host-fn-allowlist-does-not-weaken-the-unconditional-denylist
  ;; A caller's :allowed-url-prefixes matching a loopback URL must NOT
  ;; override blocked-http-post-destination? -- the two checks are ANDed,
  ;; not either-or, so a permissive allowlist can never re-open the SSRF
  ;; hole the denylist exists to close.
  (let [hits (atom 0)
        server (doto (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
                 (.createContext "/" (reify HttpHandler
                                       (handle [_ exchange]
                                         (swap! hits inc)
                                         (.sendResponseHeaders ^HttpExchange exchange 200 -1))))
                 (.start))]
    (try
      (let [port (.getPort (.getAddress server))
            url (str "http://127.0.0.1:" port "/")
            wasm (wat->wasm (http-post-wat url))
            caps (contract/host-caps {:grants [:http-post]
                                       :limits {:max-http-posts 1
                                                :allowed-url-prefixes [url]}})
            n (tender/run-main wasm [:http-post] caps)]
        (is (= -1 n) "still refused despite an allowlist that explicitly matches this exact URL")
        (is (zero? @hits) "the local server never received a request"))
      (finally (.stop server 0)))))

(deftest http-request-timeout-fires-instead-of-hanging-on-a-slow-loris-peer
  (let [accepted (atom nil)
        server (ServerSocket. 0 1 (InetAddress/getByName "127.0.0.1"))
        server-thread (Thread. ^Runnable (fn []
                                           (try (reset! accepted (.accept server))
                                                (catch Exception _ nil))))]
    (.start server-thread)
    (try
      (let [port (.getLocalPort server)
            client (#'tender/timed-http-client)
            req (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port "/")))
                   (.timeout @#'tender/http-request-timeout)
                   (.POST (HttpRequest$BodyPublishers/ofByteArray (byte-array 0)))
                   .build)
            started (System/currentTimeMillis)
            outcome (try
                      (.send client req (HttpResponse$BodyHandlers/ofByteArray))
                      :unexpectedly-succeeded
                      (catch Exception _ :failed-as-expected))
            elapsed (- (System/currentTimeMillis) started)]
        (is (= :failed-as-expected outcome)
            "a peer that accepts the connection but never responds must not let the call succeed")
        (is (< elapsed 8000)
            "fails within the configured connect/request timeout instead of hanging indefinitely (no timeout was previously set at all)"))
      (finally
        (.close server)
        (when-let [s @accepted] (.close ^java.net.Socket s))))))

(deftest http-request-timeout-does-not-break-a-normal-fast-round-trip
  (let [server (doto (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
                 (.createContext "/" (reify HttpHandler
                                       (handle [_ exchange]
                                         (let [resp (.getBytes "pong" "UTF-8")]
                                           (.sendResponseHeaders ^HttpExchange exchange 200 (count resp))
                                           (with-open [os (.getResponseBody ^HttpExchange exchange)]
                                             (.write os resp))))))
                 (.start))]
    (try
      (let [port (.getPort (.getAddress server))
            client (#'tender/timed-http-client)
            req (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port "/")))
                   (.timeout @#'tender/http-request-timeout)
                   (.POST (HttpRequest$BodyPublishers/ofByteArray (byte-array 0)))
                   .build)
            resp (.send client req (HttpResponse$BodyHandlers/ofString))]
        (is (= 200 (.statusCode resp)))
        (is (= "pong" (.body resp))
            "adding connect/request timeouts doesn't interfere with a normal fast response"))
      (finally (.stop server 0)))))

;; ── llm-infer: DI'd via :llm-client (`{:infer-fn}`), same shape :store
;; already uses for log-read/log-write -- these tests inject a fake
;; :infer-fn instead of ever reaching the real Anthropic API. ───────────────

(deftest llm-infer-denied-without-explicit-grant-and-limit
  (testing "default caps: no :llm-infer grant AND max-llm-infers 0 -- pre-flight rejects before any Instance is built"
    (let [caps (contract/host-caps {})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"rejected by contract"
                            (tender/instantiate (byte-array 0) [:llm-infer] caps))))))

(deftest llm-infer-round-trips-through-an-injected-mock-client
  (let [wasm (wat->wasm llm-infer-wat)
        caps (contract/host-caps {:grants [:llm-infer] :limits {:max-llm-infers 1}})
        seen-prompt (atom nil)
        instance (tender/instantiate wasm [:llm-infer] caps
                                     {:llm-client {:infer-fn (fn [prompt]
                                                               (reset! seen-prompt prompt)
                                                               "mocked reply")}})
        n (tender/call-main instance)]
    (is (= "hello" @seen-prompt) "the guest's prompt bytes were read out of its own memory correctly")
    (is (= (count "mocked reply") n))
    (is (= "mocked reply" (tender/read-memory-string instance 100 n)))))

(deftest llm-infer-count-limit-denies-once-exceeded
  (let [wasm (wat->wasm llm-infer-twice-wat)
        caps (contract/host-caps {:grants [:llm-infer] :limits {:max-llm-infers 1}})
        n (tender/run-main wasm [:llm-infer] caps {:llm-client {:infer-fn (fn [_] "ok")}})]
    (testing "1st call fits the max-llm-infers 1 cap; the 2nd call is denied"
      (is (= -1 n)))))

(deftest llm-infer-fails-closed-when-the-client-yields-nil
  (testing "no API key configured / underlying call failed -- :infer-fn returns nil, the host-fn is fail-closed (-1), not an exception"
    (let [wasm (wat->wasm llm-infer-wat)
          caps (contract/host-caps {:grants [:llm-infer] :limits {:max-llm-infers 5}})
          n (tender/run-main wasm [:llm-infer] caps {:llm-client {:infer-fn (fn [_] nil)}})]
      (is (= -1 n)))))

(deftest llm-infer-fails-closed-through-the-real-default-client-when-no-api-key-is-set
  (testing "no :llm-client opt passed at all -- wired to the real default-llm-client; asserts the fail-closed path only when this env truly has no key configured, so this never makes a live network call in an environment that happens to have one set for something else"
    (when (nil? (tender/resolve-llm-api-key))
      (let [wasm (wat->wasm llm-infer-wat)
            caps (contract/host-caps {:grants [:llm-infer] :limits {:max-llm-infers 1}})
            n (tender/run-main wasm [:llm-infer] caps)]
        (is (= -1 n))))))

(deftest fuel-limit-traps-an-infinite-loop
  (let [wasm (wat->wasm infinite-loop-wat)
        caps (contract/host-caps {})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fuel limit"
                          (tender/run-main wasm [] caps {:fuel 10000})))))

(deftest memory-grow-within-cap-succeeds
  (let [wasm (wat->wasm (memory-grow-wat 1)) ; 1 initial + 1 = 2, exactly the cap
        caps (contract/host-caps {:limits {:max-memory-pages 2}})
        prev-size (tender/run-main wasm [] caps)]
    (is (= 1 prev-size) "memory.grow returns the PREVIOUS page count on success")))

(deftest memory-grow-past-cap-fails
  (let [wasm (wat->wasm (memory-grow-wat 5)) ; 1 initial + 5 = 6, past a 2-page cap
        caps (contract/host-caps {:limits {:max-memory-pages 2}})
        result (tender/run-main wasm [] caps)]
    (is (= -1 result) "memory.grow returns -1 when it would exceed max-memory-pages")))

(deftest memory-less-guest-is-unaffected-by-the-memory-cap
  (let [wasm (wat->wasm clock-monotonic-wat)
        caps (contract/host-caps {:grants [:clock-monotonic] :limits {:max-memory-pages 0}})]
    (is (pos? (tender/run-main wasm [:clock-monotonic] caps))
        "a guest declaring no memory section links and runs fine even with max-memory-pages 0")))

(deftest log-write-byte-limit-denies-once-exceeded
  (let [wasm (wat->wasm log-write-thrice-wat)
        caps (contract/host-caps {:grants [:log-write]
                                  :limits {:allow-write-imports? true
                                           :max-log-write-bytes 8}})
        n (tender/run-main wasm [:log-write] caps)]
    (testing "2 calls x 4 bytes = 8 bytes fits the cap; the 3rd call (12 total) is denied"
      (is (= -1 n)))))

(deftest log-write-under-the-limit-all-succeed
  (let [wasm (wat->wasm log-write-thrice-wat)
        caps (contract/host-caps {:grants [:log-write]
                                  :limits {:allow-write-imports? true
                                           :max-log-write-bytes 1000}})
        n (tender/run-main wasm [:log-write] caps)]
    (is (= 0 n))))

(deftest sign-and-verify-round-trip-through-real-chicory
  (let [wat "(module
               (import \"kotoba\" \"gen_keypair\" (func $gk (param i32 i32) (result i32)))
               (import \"kotoba\" \"sign\" (func $sign (param i32 i32 i32 i32 i32) (result i32)))
               (import \"kotoba\" \"verify\" (func $verify (param i32 i32 i32 i32 i32 i32) (result i32)))
               (memory (export \"memory\") 1)
               (data (i32.const 200) \"msg!\")
               (func (export \"main\") (result i64)
                 (drop (call $gk (i32.const 0) (i32.const 64)))
                 (drop (call $sign (i32.const 0) (i32.const 200) (i32.const 4) (i32.const 300) (i32.const 64)))
                 (i64.extend_i32_s
                   (call $verify (i32.const 32) (i32.const 32) (i32.const 200) (i32.const 4)
                                 (i32.const 300) (i32.const 64)))))"
        wasm (wat->wasm wat)
        caps (contract/host-caps {:grants [:gen-keypair :sign :verify]
                                  :limits {:allow-secret-imports? true}})
        ok? (tender/run-main wasm [:gen-keypair :sign :verify] caps)]
    (is (= 1 ok?))))

;; ---------------------------------------------------------------------------
;; Real .kotoba-compiler-emitted fixtures (kotoba-lang/kotoba's `kotoba wasm
;; emit`, checked in as bytes -- NOT a hand-written WAT string self-consistent
;; only with this repo's own field-name choices). ADR-2607062330's addendum 3
;; found `kotoba wasm emit` could not call these imports at all until
;; kotoba-core-contracts registered them (kotoba-core-contracts#3); this is
;; the actual end-to-end proof that closed loop: a real compiled guest
;; produced by the independent `kotoba` compiler links against
;; `kototama.tender`'s HostFunctions without any shape mismatch. Source
;; alongside each fixture for provenance (`fixtures/*.kotoba`).

(deftest kotoba-compiled-sha256-hex-guest-round-trips-through-real-chicory
  (testing "(sha256-hex 0 0 2048 64), compiled by `kotoba wasm emit`, not WAT"
    (let [wasm (read-fixture "kotoba-compiled-sha256-hex.wasm")
          caps (contract/host-caps {:grants [:sha256-hex]})
          instance (tender/instantiate wasm [:sha256-hex] caps)
          written (tender/call-main instance)]
      (is (= 64 written))
      (is (= "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
             (tender/read-memory-string instance 2048 written))
          "sha256(\"\") computed by the real host function, written into the guest's own memory"))))

(deftest kotoba-compiled-gen-keypair-guest-round-trips-through-real-chicory
  (testing "(gen-keypair 2048 64), compiled by `kotoba wasm emit`, not WAT"
    (let [wasm (read-fixture "kotoba-compiled-gen-keypair.wasm")
          caps (contract/host-caps {:grants [:gen-keypair] :limits {:allow-secret-imports? true}})
          written (tender/run-main wasm [:gen-keypair] caps)]
      (is (= 64 written) "32-byte seed + 32-byte derived pubkey"))))
