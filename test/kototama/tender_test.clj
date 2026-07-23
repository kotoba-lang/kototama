(ns kototama.tender-test
  "Real Wasm binaries, not just Clojure-closure unit tests -- same
  end-to-end discipline `kotoba.wasm-exec_test.clj` uses. WAT fixtures are
  compiled to real Wasm bytes at test time via the `wasm-tools` CLI
  (Bytecode Alliance, ~/.cargo/bin/wasm-tools), so these tests exercise
  the actual Chicory Parser/Instance/HostFunction linkage, not a mock."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
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

;; ── ADR-2607230943 second wave: http-fetch (GET) shares http-post-wat's
;; shape (URL baked into a data segment, ASCII/no-"/\\ only); cbor-encode/
;; json-encode/json-extract-field need a WAT string ESCAPE helper since
;; their guest-facing wire format embeds literal TAB/LF byte separators,
;; which the WAT text grammar can only express via `\t`/`\n` escapes --
;; `wat-escape` produces THOSE TWO SOURCE CHARACTERS (backslash + letter),
;; not an actual tab/newline byte, so `wasm-tools parse` decodes them back
;; to the single real byte kototama's own `parse-flat-pairs` expects. ────

(def http-fetch-wat
  "Imports http_fetch, GETs URL (baked into a data segment at offset 0,
  URL's own byte length as url-len -- same ASCII/no-\"/\\\\ constraint
  http-post-wat's URL has), writes the response at offset 200
  (256-byte capacity)."
  (fn [url]
    (str "(module
            (import \"kotoba\" \"http_fetch\" (func $http_fetch (param i32 i32 i32 i32) (result i32)))
            (memory (export \"memory\") 1)
            (data (i32.const 0) \"" url "\")
            (func (export \"main\") (result i64)
              (i64.extend_i32_s
                (call $http_fetch (i32.const 0) (i32.const " (count url) ")
                                  (i32.const 200) (i32.const 256)))))")))

(defn- wat-escape
  "Escapes S (a real Java/Clojure string, possibly containing literal
  TAB/LF/`\"`/`\\\\` bytes) into WAT string-literal SOURCE TEXT --
  `wasm-tools parse` decodes `\\t`/`\\n`/`\\\"`/`\\\\` back to the single
  real byte each represents, matching the grammar every other `.wat`
  fixture's `(data ...)` string already relies on."
  [^String s]
  (-> s
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\t" "\\t")
      (str/replace "\n" "\\n")))

(def cbor-encode-wat
  "Imports cbor_encode, encodes RAW-PAIRS (kototama's flat key<TAB>value
  wire format, byte length = RAW-PAIRS' own character count since test
  data below is pure ASCII) baked at offset 0, writes CBOR bytes at
  offset 300 (OUT-CAP capacity)."
  (fn [raw-pairs out-cap]
    (str "(module
            (import \"kotoba\" \"cbor_encode\" (func $cbor_encode (param i32 i32 i32 i32) (result i32)))
            (memory (export \"memory\") 1)
            (data (i32.const 0) \"" (wat-escape raw-pairs) "\")
            (func (export \"main\") (result i64)
              (i64.extend_i32_s
                (call $cbor_encode (i32.const 0) (i32.const " (count raw-pairs) ")
                                   (i32.const 300) (i32.const " out-cap ")))))")))

(def json-encode-wat
  "Imports json_encode, same shape as cbor-encode-wat above."
  (fn [raw-pairs out-cap]
    (str "(module
            (import \"kotoba\" \"json_encode\" (func $json_encode (param i32 i32 i32 i32) (result i32)))
            (memory (export \"memory\") 1)
            (data (i32.const 0) \"" (wat-escape raw-pairs) "\")
            (func (export \"main\") (result i64)
              (i64.extend_i32_s
                (call $json_encode (i32.const 0) (i32.const " (count raw-pairs) ")
                                   (i32.const 300) (i32.const " out-cap ")))))")))

(def json-extract-field-wat
  "Imports json_extract_field, scans JSON-TEXT (offset 0) for FIELD
  (offset 100), writes the extracted string value at offset 300
  (OUT-CAP capacity)."
  (fn [json-text field out-cap]
    (str "(module
            (import \"kotoba\" \"json_extract_field\"
              (func $json_extract_field (param i32 i32 i32 i32 i32 i32) (result i32)))
            (memory (export \"memory\") 1)
            (data (i32.const 0) \"" (wat-escape json-text) "\")
            (data (i32.const 100) \"" (wat-escape field) "\")
            (func (export \"main\") (result i64)
              (i64.extend_i32_s
                (call $json_extract_field (i32.const 0) (i32.const " (count json-text) ")
                                          (i32.const 100) (i32.const " (count field) ")
                                          (i32.const 300) (i32.const " out-cap ")))))")))

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

;; ── http-fetch (GET, ADR-2607230943 second wave): shares http-post-host-
;; fn's `blocked-http-post-destination?`/`contract/url-allowed?` verbatim,
;; so every real local `HttpServer` this test process can stand up is
;; necessarily loopback -- same "denylist proven via refusal, not via a
;; completed round-trip" shape http-post's own tests above use, for the
;; same structural reason (see that section's header comment). ────────────

(deftest http-fetch-host-fn-refuses-a-loopback-destination-before-any-real-connection
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
            wasm (wat->wasm (http-fetch-wat url))
            caps (contract/host-caps {:grants [:http-fetch] :limits {:max-http-fetches 1}})
            started (System/currentTimeMillis)
            n (tender/run-main wasm [:http-fetch] caps)
            elapsed (- (System/currentTimeMillis) started)]
        (is (= -1 n) "loopback destination refused, the standard fail-closed -1 convention")
        (is (zero? @hits) "the local server never received a request -- rejected BEFORE any real HTTP call was attempted")
        (is (< elapsed 2000) "rejection is immediate (pure classification), not gated behind a connect/request timeout"))
      (finally (.stop server 0)))))

(deftest http-fetch-host-fn-allowlist-does-not-weaken-the-unconditional-denylist
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
            wasm (wat->wasm (http-fetch-wat url))
            caps (contract/host-caps {:grants [:http-fetch]
                                       :limits {:max-http-fetches 1
                                                :allowed-url-prefixes [url]}})
            n (tender/run-main wasm [:http-fetch] caps)]
        (is (= -1 n) "still refused despite an allowlist that explicitly matches this exact URL")
        (is (zero? @hits) "the local server never received a request"))
      (finally (.stop server 0)))))

(deftest http-fetch-denied-pre-flight-when-max-http-fetches-is-zero-by-default
  (testing "granted but :max-http-fetches stays at its default-runtime-limits 0 -- rejected before any Instance is built (validate-import-surface counts REQUESTED import declarations against this same limit, same as :max-http-posts already does for http-post -- no test in this file ever requests a given import type more than once, so the separate RUNTIME per-call counter this limit ALSO gates via within-count-limit? is only exercised by imports a guest calls multiple times per `main`, e.g. log-write-thrice-wat/llm-infer-twice-wat below, not http-post/http-fetch)"
    (let [caps (contract/host-caps {:grants [:http-fetch]})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"rejected by contract"
                            (tender/instantiate (byte-array 0) [:http-fetch] caps))))))

;; ── cbor-encode / json-encode / json-extract-field (ADR-2607230943 second
;; wave): pure computation, real byte-level round trips via Chicory,
;; ground-truth values computed once via a direct REPL call to the same
;; private functions these WAT fixtures exercise through the full guest/
;; host-fn path (`#'tender/cbor-pairs-bytes` etc.), same "reach into a
;; private var" pattern `blocked-http-post-destination?` above uses. ──────

(deftest cbor-encode-host-fn-round-trips-through-real-chicory
  (let [pairs "iss\tdid:key:zTest\naud\tpds.aozora.app"
        wasm (wat->wasm (cbor-encode-wat pairs 256))
        caps (contract/host-caps {:grants [:cbor-encode]})
        instance (tender/instantiate wasm [:cbor-encode] caps)
        written (tender/call-main instance)]
    (is (= 38 written) "definite-length CBOR map header + 2 string-key/string-value pairs")
    (is (= [0xA2 0x63 0x69 0x73 0x73 0x6d 0x64 0x69 0x64 0x3a 0x6b 0x65 0x79
            0x3a 0x7a 0x54 0x65 0x73 0x74 0x63 0x61 0x75 0x64 0x6e 0x70 0x64
            0x73 0x2e 0x61 0x6f 0x7a 0x6f 0x72 0x61 0x2e 0x61 0x70 0x70]
           (vec (map #(bit-and (int %) 0xff) (#'tender/read-bytes! instance 300 written))))
        "0xA2 = map(2); 0x63 \"iss\" (3 bytes); 0x6d \"did:key:zTest\" (13 bytes); 0x63 \"aud\" (3 bytes); 0x70 \"pds.aozora.app\" (16 bytes)")))

(deftest cbor-encode-host-fn-out-cap-overflow-is-in-band-minus-one
  (let [pairs "iss\tdid:key:zTest\naud\tpds.aozora.app"
        wasm (wat->wasm (cbor-encode-wat pairs 4)) ; 38 bytes needed, 4 given
        caps (contract/host-caps {:grants [:cbor-encode]})
        n (tender/run-main wasm [:cbor-encode] caps)]
    (is (= -1 n) "same write-bytes! overflow convention every other host-fn uses")))

(deftest cbor-encode-host-fn-skips-a-malformed-line-fail-soft
  (let [pairs "a\tb\nnotab\nc\td" ; middle line has no TAB
        wasm (wat->wasm (cbor-encode-wat pairs 256))
        caps (contract/host-caps {:grants [:cbor-encode]})
        instance (tender/instantiate wasm [:cbor-encode] caps)
        written (tender/call-main instance)]
    ;; map(2): "a"->"b", "c"->"d" -- "notab" contributed no pair
    (is (= [0xA2 0x61 0x61 0x61 0x62 0x61 0x63 0x61 0x64]
           (vec (map #(bit-and (int %) 0xff) (#'tender/read-bytes! instance 300 written)))))))

(deftest json-encode-host-fn-round-trips-through-real-chicory
  (let [pairs "identifier\tuser.bsky.social\npassword\thunter2"
        wasm (wat->wasm (json-encode-wat pairs 256))
        caps (contract/host-caps {:grants [:json-encode]})
        instance (tender/instantiate wasm [:json-encode] caps)
        written (tender/call-main instance)]
    (is (= "{\"identifier\":\"user.bsky.social\",\"password\":\"hunter2\"}"
           (tender/read-memory-string instance 300 written))
        "flat XRPC-shaped request body")))

(deftest json-encode-host-fn-escapes-quotes-backslashes-and-tabs
  ;; A literal newline in the VALUE is deliberately NOT covered here --
  ;; `parse-flat-pairs` uses LF as the pair separator, so an embedded
  ;; newline would split this into two (guest-level) lines, not survive
  ;; as part of one value; see `json-escape-directly-handles-newline-and-
  ;; control-chars` below for that escaping logic tested in isolation,
  ;; bypassing the wire format's own LF-as-delimiter constraint.
  (let [raw-value "has \"quote\" and \\backslash\\ and\ttab"
        pairs (str "k\t" raw-value)
        wasm (wat->wasm (json-encode-wat pairs 256))
        caps (contract/host-caps {:grants [:json-encode]})
        instance (tender/instantiate wasm [:json-encode] caps)
        written (tender/call-main instance)]
    (is (= "{\"k\":\"has \\\"quote\\\" and \\\\backslash\\\\ and\\ttab\"}"
           (tender/read-memory-string instance 300 written)))))

(deftest json-escape-directly-handles-newline-and-control-chars
  ;; Bypasses parse-flat-pairs' LF-as-pair-separator wire format entirely
  ;; -- calls the private json-escape helper directly, the same pattern
  ;; blocked-http-post-destination? above uses.
  (is (= "line1\\nline2" (#'tender/json-escape "line1\nline2")))
  (is (= "cr\\rhere" (#'tender/json-escape "cr\rhere")))
  (is (= "\\u0001\\u0002" (#'tender/json-escape (str (char 1) (char 2))))
      "other C0 control characters get \\uXXXX, not passed through raw"))

(deftest json-encode-host-fn-out-cap-overflow-is-in-band-minus-one
  (let [pairs "a\tb"
        wasm (wat->wasm (json-encode-wat pairs 2)) ; {"a":"b"} needs 9 bytes, 2 given
        caps (contract/host-caps {:grants [:json-encode]})
        n (tender/run-main wasm [:json-encode] caps)]
    (is (= -1 n))))

(deftest json-extract-field-host-fn-round-trips-through-real-chicory
  (let [json-text "{\"accessJwt\":\"abc.def.ghi\",\"uri\":\"at://x\"}"
        wasm (wat->wasm (json-extract-field-wat json-text "accessJwt" 64))
        caps (contract/host-caps {:grants [:json-extract-field]})
        instance (tender/instantiate wasm [:json-extract-field] caps)
        written (tender/call-main instance)]
    (is (= "abc.def.ghi" (tender/read-memory-string instance 300 written)))))

(deftest json-extract-field-host-fn-tolerates-whitespace-after-the-colon
  (let [json-text "{\"accessJwt\": \"abc\"}"
        wasm (wat->wasm (json-extract-field-wat json-text "accessJwt" 64))
        caps (contract/host-caps {:grants [:json-extract-field]})
        instance (tender/instantiate wasm [:json-extract-field] caps)
        written (tender/call-main instance)]
    (is (= "abc" (tender/read-memory-string instance 300 written)))))

(deftest json-extract-field-host-fn-missing-field-is-in-band-minus-one
  (let [json-text "{\"foo\":\"bar\"}"
        wasm (wat->wasm (json-extract-field-wat json-text "accessJwt" 64))
        caps (contract/host-caps {:grants [:json-extract-field]})
        n (tender/run-main wasm [:json-extract-field] caps)]
    (is (= -1 n) "field absent -- not a general parser, so no attempt to synthesize a value")))

(deftest json-extract-field-host-fn-non-string-value-is-in-band-minus-one
  (let [json-text "{\"n\":123}"
        wasm (wat->wasm (json-extract-field-wat json-text "n" 64))
        caps (contract/host-caps {:grants [:json-extract-field]})
        n (tender/run-main wasm [:json-extract-field] caps)]
    (is (= -1 n) "value isn't a quoted string -- out of this bounded scan's scope, not coerced")))

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

(deftest kotoba-compiled-http-fetch-guest-round-trips-through-real-chicory
  ;; ADR-2607230943 second wave's proof that the independent `kotoba`
  ;; compiler and kototama.tender agree on http-fetch's shape too --
  ;; compiled straight from kotoba-core-contracts' PRE-EXISTING "http/fetch"
  ;; (id 205) capability, no kotoba-core-contracts change needed for this
  ;; one fixture (unlike cbor-encode/json-encode/json-extract-field, which
  ;; are net-new to that table and so aren't exercised here). The guest's
  ;; literal URL ("http://127.0.0.1/") is deliberately loopback -- kototama
  ;; unconditionally refuses it (same denylist http-post's own compiled
  ;; fixtures would hit, which is why neither has one either), so this
  ;; proves real compiler-to-tender LINKAGE + real SSRF-guard EXECUTION,
  ;; not a live network round trip (see tender_test.clj's http-fetch WAT
  ;; tests above for that same guard tested directly against the host-fn).
  (testing "(http-fetch (str-ptr url) (str-len url) (alloc 64) 64), compiled by `kotoba wasm emit`, not WAT"
    (let [wasm (read-fixture "kotoba-compiled-http-fetch.wasm")
          caps (contract/host-caps {:grants [:http-fetch] :limits {:max-http-fetches 1}})
          written (tender/run-main wasm [:http-fetch] caps)]
      (is (= -1 written) "loopback URL refused by the same denylist the WAT-based tests above verify"))))

;; cbor-encode/json-encode/json-extract-field, unlike http-fetch above,
;; ARE net-new to kotoba-core-contracts' capability_contract.edn (ids 245/
;; 246) -- compiled with `clojure -M:dev` against a LOCAL sibling checkout
;; of the SAME edited kotoba-core-contracts this wave's own PR carries
;; (com-junkawasaki/root ADR-2607230943), proving the independent `kotoba`
;; compiler resolves these new host-import call targets and kototama.tender
;; links the resulting binary, end to end -- same E2E proof PR #23
;; established for sha256-hex/gen-keypair.

;; `alloc`'s returned pointer is `kotoba wasm emit`'s fixed heap-base for
;; every one of these short guests (verified once via a direct
;; `kotoba.runtime/wasm-binary` call at fixture-generation time, same
;; value `kotoba-compiled-sha256-hex.kotoba`'s own literal `2048` offset
;; already uses -- these 3 fixtures just reach it through `alloc` instead
;; of a hand-picked literal, since their source strings are built with
;; `str-ptr`/`str-len` rather than assuming offset 0 holds nothing).
(def ^:private alloc-heap-base 2048)

(deftest kotoba-compiled-cbor-encode-guest-round-trips-through-real-chicory
  (testing "(cbor-encode (str-ptr \"a\\tb\") (str-len \"a\\tb\") (alloc 64) 64), compiled by `kotoba wasm emit`, not WAT"
    (let [wasm (read-fixture "kotoba-compiled-cbor-encode.wasm")
          caps (contract/host-caps {:grants [:cbor-encode]})
          instance (tender/instantiate wasm [:cbor-encode] caps)
          written (tender/call-main instance)]
      (is (= 5 written) "map(1) header + 1-byte-string \"a\" + 1-byte-string \"b\"")
      (is (= [0xA1 0x61 0x61 0x61 0x62]
             (vec (map #(bit-and (int %) 0xff) (#'tender/read-bytes! instance alloc-heap-base written))))))))

(deftest kotoba-compiled-json-encode-guest-round-trips-through-real-chicory
  (testing "(json-encode (str-ptr \"a\\tb\") (str-len \"a\\tb\") (alloc 64) 64), compiled by `kotoba wasm emit`, not WAT"
    (let [wasm (read-fixture "kotoba-compiled-json-encode.wasm")
          caps (contract/host-caps {:grants [:json-encode]})
          instance (tender/instantiate wasm [:json-encode] caps)
          written (tender/call-main instance)]
      (is (= "{\"a\":\"b\"}" (tender/read-memory-string instance alloc-heap-base written))))))

(deftest kotoba-compiled-json-extract-field-guest-round-trips-through-real-chicory
  (testing "(json-extract-field ... \"{\\\"k\\\":\\\"v\\\"}\" ... \"k\" ...), compiled by `kotoba wasm emit`, not WAT"
    (let [wasm (read-fixture "kotoba-compiled-json-extract-field.wasm")
          caps (contract/host-caps {:grants [:json-extract-field]})
          instance (tender/instantiate wasm [:json-extract-field] caps)
          written (tender/call-main instance)]
      (is (= "v" (tender/read-memory-string instance alloc-heap-base written))))))
