(ns kototama.tender-test
  "Real Wasm binaries, not just Clojure-closure unit tests -- same
  end-to-end discipline `kotoba.wasm-exec_test.clj` uses. WAT fixtures are
  compiled to real Wasm bytes at test time via the `wasm-tools` CLI
  (Bytecode Alliance, ~/.cargo/bin/wasm-tools), so these tests exercise
  the actual Chicory Parser/Instance/HostFunction linkage, not a mock."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [kototama.tender :as tender]
            [kototama.contract :as contract]
            [kototama.linker :as linker]
            [kototama.transport-provider :as transport-provider]
            [kototama.kagi-adapter :as kagi-adapter]
            [kotoba.runtime :as kotoba-runtime]))

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

(def scram-sha256-wat
  "RFC-style SCRAM-SHA-256 vector; the password is injected into the tender
  and never appears in guest memory."
  "(module
     (import \"kotoba\" \"scram_sha256\"
       (func $scram (param i32 i32 i32 i32 i32 i32 i32 i32 i32) (result i32)))
     (memory (export \"memory\") 1)
     (data (i32.const 0) \"db/primary\")
     (data (i32.const 32) \"\\5b\\6d\\99\\68\\9d\\12\\35\\8e\\ec\\a0\\4b\\14\\12\\36\\fa\\81\")
     (data (i32.const 64) \"n=user,r=fyko+d2lbbFgONRv9qkxdawL,r=fyko+d2lbbFgONRv9qkxdawL3rfcNHYJY1ZVvWVs7j,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096,c=biws,r=fyko+d2lbbFgONRv9qkxdawL3rfcNHYJY1ZVvWVs7j\")
     (func (export \"main\") (result i64)
       (i64.extend_i32_s
        (call $scram (i32.const 0) (i32.const 10)
                     (i32.const 32) (i32.const 16) (i32.const 4096)
                     (i32.const 64) (i32.const 164)
                     (i32.const 512) (i32.const 64)))))")

(def clock-monotonic-wat
  "Imports clock_monotonic, calls it once, returns whatever it got (proves
  linkage -- a specific value isn't asserted, just that it doesn't trap)."
  "(module
     (import \"kotoba\" \"clock_monotonic\" (func $clock_monotonic (result i64)))
     (func (export \"main\") (result i64) (call $clock_monotonic)))")

(def kagi-sign-wat
  "(module
     (import \"kotoba\" \"kagi_sign\" (func $kagi_sign (param i32 i32 i32 i32 i32 i32) (result i32)))
     (memory (export \"memory\") 1)
     (data (i32.const 0) \"kagi://ops/key\")
     (data (i32.const 32) \"hello\")
     (func (export \"main\") (result i64)
       (i64.extend_i32_s
        (call $kagi_sign (i32.const 0) (i32.const 14)
                         (i32.const 32) (i32.const 5)
                         (i32.const 100) (i32.const 64)))))")

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

(defn transport-echo-wat
  "Connects to an injected local endpoint, writes ping, reads pong, closes the
  affine handle, and returns the read byte count."
  [port]
  (str "(module
     (import \"kotoba\" \"transport_connect\" (func $connect (param i32 i32 i32) (result i64)))
     (import \"kotoba\" \"transport_write\" (func $write (param i64 i32 i32) (result i32)))
     (import \"kotoba\" \"transport_read\" (func $read (param i64 i32 i32) (result i32)))
     (import \"kotoba\" \"transport_close\" (func $close (param i64) (result i32)))
     (memory (export \"memory\") 1)
     (data (i32.const 0) \"127.0.0.1\")
     (data (i32.const 32) \"ping\")
     (func (export \"main\") (result i64)
       (local $h i64) (local $n i32)
       (local.set $h (call $connect (i32.const 0) (i32.const 9) (i32.const " port ")))
       (drop (call $write (local.get $h) (i32.const 32) (i32.const 4)))
       (local.set $n (call $read (local.get $h) (i32.const 64) (i32.const 4)))
       (drop (call $close (local.get $h)))
       (i64.extend_i32_s (local.get $n))))"))

(defn transport-double-close-wat [port]
  (str "(module
     (import \"kotoba\" \"transport_connect\" (func $connect (param i32 i32 i32) (result i64)))
     (import \"kotoba\" \"transport_close\" (func $close (param i64) (result i32)))
     (memory (export \"memory\") 1)
     (data (i32.const 0) \"127.0.0.1\")
     (func (export \"main\") (result i64)
       (local $h i64)
       (local.set $h (call $connect (i32.const 0) (i32.const 9) (i32.const " port ")))
       (drop (call $close (local.get $h)))
       (i64.extend_i32_s (call $close (local.get $h)))))"))

(defn transport-connect-only-wat [host port]
  (str "(module
     (import \"kotoba\" \"transport_connect\" (func $connect (param i32 i32 i32) (result i64)))
     (memory (export \"memory\") 1)
     (data (i32.const 0) \"" host "\")
     (func (export \"main\") (result i64)
       (call $connect (i32.const 0) (i32.const " (count host) ") (i32.const " port "))))"))

(defn tls-echo-wat [port server-name]
  (str "(module
     (import \"kotoba\" \"transport_connect\" (func $connect (param i32 i32 i32) (result i64)))
     (import \"kotoba\" \"tls_open\" (func $tls (param i64 i32 i32) (result i64)))
     (import \"kotoba\" \"transport_write\" (func $write (param i64 i32 i32) (result i32)))
     (import \"kotoba\" \"transport_read\" (func $read (param i64 i32 i32) (result i32)))
     (import \"kotoba\" \"transport_close\" (func $close (param i64) (result i32)))
     (memory (export \"memory\") 1)
     (data (i32.const 0) \"localhost\")
     (data (i32.const 16) \"" server-name "\")
     (data (i32.const 32) \"ping\")
     (func (export \"main\") (result i64)
       (local $tcp i64) (local $tls i64) (local $n i32)
       (local.set $tcp (call $connect (i32.const 0) (i32.const 9) (i32.const " port ")))
       (local.set $tls (call $tls (local.get $tcp) (i32.const 16) (i32.const " (count server-name) ")))
       (drop (call $write (local.get $tls) (i32.const 32) (i32.const 4)))
       (local.set $n (call $read (local.get $tls) (i32.const 64) (i32.const 4)))
       (drop (call $close (local.get $tls)))
       (i64.extend_i32_s (local.get $n))))"))

(defn tls-open-only-wat [port server-name]
  (str "(module
     (import \"kotoba\" \"transport_connect\" (func $connect (param i32 i32 i32) (result i64)))
     (import \"kotoba\" \"tls_open\" (func $tls (param i64 i32 i32) (result i64)))
     (memory (export \"memory\") 1)
     (data (i32.const 0) \"localhost\")
     (data (i32.const 16) \"" server-name "\")
     (func (export \"main\") (result i64)
       (call $tls
         (call $connect (i32.const 0) (i32.const 9) (i32.const " port "))
         (i32.const 16) (i32.const " (count server-name) "))))"))

(defn- test-tls-contexts [keystore-file password]
  (.delete keystore-file)
  (let [result (shell/sh "keytool" "-genkeypair" "-alias" "server"
                         "-keyalg" "RSA" "-validity" "1"
                         "-dname" "CN=localhost"
                         "-ext" "SAN=dns:localhost,ip:127.0.0.1"
                         "-storetype" "PKCS12"
                         "-keystore" (.getPath keystore-file)
                         "-storepass" password "-keypass" password "-noprompt")]
    (when-not (zero? (:exit result))
      (throw (ex-info "keytool test certificate generation failed" result)))
    (let [chars (.toCharArray password)
          store (java.security.KeyStore/getInstance "PKCS12")]
      (with-open [in (java.io.FileInputStream. keystore-file)]
        (.load store in chars))
      (let [kmf (javax.net.ssl.KeyManagerFactory/getInstance
                 (javax.net.ssl.KeyManagerFactory/getDefaultAlgorithm))
            tmf (javax.net.ssl.TrustManagerFactory/getInstance
                 (javax.net.ssl.TrustManagerFactory/getDefaultAlgorithm))
            server (javax.net.ssl.SSLContext/getInstance "TLS")
            client (javax.net.ssl.SSLContext/getInstance "TLS")
            mutual-client (javax.net.ssl.SSLContext/getInstance "TLS")]
        (.init kmf store chars)
        (.init tmf store)
        (.init server (.getKeyManagers kmf) (.getTrustManagers tmf) nil)
        (.init client nil (.getTrustManagers tmf) nil)
        (.init mutual-client (.getKeyManagers kmf) (.getTrustManagers tmf) nil)
        {:server server :client client :mutual-client mutual-client}))))

(defn- compile-kotoba-provider [source policy-path]
  (let [forms (kotoba-runtime/read-file source :kotoba)
        policy (edn/read-string (slurp policy-path))
        artifact (kotoba-runtime/wasm-binary forms policy)]
    (when-not (:kotoba.wasm/ok? artifact)
      (throw (ex-info "Kotoba component compilation failed" artifact)))
    (:kotoba.wasm/binary artifact)))

(def component-provider-wat
  "Provider has independent memory. http-read writes pong into provider memory;
  the linker must copy it back to the consumer instance."
  "(module
     (memory (export \"memory\") 1)
     (func (export \"http-open\") (param i32 i32 i32) (result i64)
       (i64.const 7))
     (func (export \"http-write\") (param i64 i32 i32) (result i32)
       (local.get 2))
     (func (export \"http-read\") (param i64 i32 i32) (result i32)
       (i32.store8 (local.get 1) (i32.const 112))
       (i32.store8 (i32.add (local.get 1) (i32.const 1)) (i32.const 111))
       (i32.store8 (i32.add (local.get 1) (i32.const 2)) (i32.const 110))
       (i32.store8 (i32.add (local.get 1) (i32.const 3)) (i32.const 103))
       (i32.const 4))
     (func (export \"http-close\") (param i64) (result i32) (i32.const 0))
     (func (export \"main\") (result i64) (i64.const 0)))")

(def component-consumer-wat
  "(module
     (import \"kotoba\" \"http_open\" (func $open (param i32 i32 i32) (result i64)))
     (import \"kotoba\" \"http_write\" (func $write (param i64 i32 i32) (result i32)))
     (import \"kotoba\" \"http_read\" (func $read (param i64 i32 i32) (result i32)))
     (import \"kotoba\" \"http_close\" (func $close (param i64) (result i32)))
     (memory (export \"memory\") 1)
     (data (i32.const 0) \"localhost\")
     (data (i32.const 32) \"ping\")
     (func (export \"main\") (result i64)
       (local $h i64) (local $n i32)
       (local.set $h (call $open (i32.const 0) (i32.const 9) (i32.const 443)))
       (drop (call $write (local.get $h) (i32.const 32) (i32.const 4)))
       (local.set $n (call $read (local.get $h) (i32.const 64) (i32.const 4)))
       (drop (call $close (local.get $h)))
       (i64.extend_i32_s (local.get $n))))")

(def component-overflow-consumer-wat
  "(module
     (import \"kotoba\" \"http_write\" (func $write (param i64 i32 i32) (result i32)))
     (memory (export \"memory\") 2)
     (func (export \"main\") (result i64)
       (i64.extend_i32_s
         (call $write (i64.const 7) (i32.const 0) (i32.const 65537)))))")

(def everything (constantly true))

(deftest transport-endpoint-canonicalization-is-unambiguous
  (is (= "localhost" (#'transport-provider/canonical-host "LOCALHOST.")))
  (is (= "xn--r8jz45g.xn--zckzah"
         (#'transport-provider/canonical-host "例え.テスト")))
  (is (= "[0:0:0:0:0:0:0:1]:443"
         (#'transport-provider/exact-endpoint "[::1]" 443)))
  (is (nil? (#'transport-provider/canonical-host " localhost")))
  (is (nil? (#'transport-provider/canonical-host "bad\u0000host"))))

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

(deftest kagi-sign-links-to-authorized-non-exportable-handle
  (let [wasm (wat->wasm kagi-sign-wat)
        caps (contract/host-caps {:grants [:kagi-sign]})
        seen (atom nil)
        signer (kagi-adapter/signer
                (fn [ref purpose msg]
                  (reset! seen [ref purpose (String. ^bytes msg "UTF-8")])
                  (.getBytes "sig!" "UTF-8")))
        decision {:decision :grant :capability :kagi/sign
                  :secret-ref "kagi://ops/key" :purpose :artifact-signing}
        session (tender/open-session wasm [:kagi-sign] caps
                                     {:kagi-signer signer :kagi-decisions [decision]})]
    (is (= 4 (tender/session-call-main session)))
    (is (= ["kagi://ops/key" :artifact-signing "hello"] @seen))
    (is (= "sig!" (tender/read-memory-string (:instance session) 100 4)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (tender/open-session wasm [:kagi-sign] caps {})))))

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

(deftest declared-transport-import-without-provider-binding-fails-before-wasm-parse
  (let [caps (contract/host-caps
              {:grants [:transport-connect]
               :limits {:max-transport-connections 1}})]
    (try
      (tender/open-session (byte-array 0) [:transport-connect] caps)
      (is false "missing provider must reject")
      (catch clojure.lang.ExceptionInfo error
        (is (= [{:error :runtime/provider-unavailable
                 :imports [:transport-connect]}]
               (:kototama.tender/errors (ex-data error))))))))

(deftest component-linker-bridges-independent-provider-memory
  (let [provider-session (tender/open-session
                          (wat->wasm component-provider-wat) []
                          (contract/host-caps {}))
        linked (linker/link-provider provider-session (vec (butlast linker/http-links)))
        requested [:http-open :http-write :http-read :http-close]
        consumer-caps (contract/host-caps
                       {:grants requested
                        :limits {:allow-write-imports? true}})
        consumer-session (tender/open-session
                          (wat->wasm component-consumer-wat)
                          requested consumer-caps
                          {:provider-host-functions linked})]
    (is (= 4 (tender/session-call-main consumer-session)))
    (is (= "pong"
           (tender/read-memory-string (:instance consumer-session) 64 4)))))

(deftest component-linker-serializes-shared-provider-scratch-across-consumers
  (let [provider-session (tender/open-session
                          (wat->wasm component-provider-wat) []
                          (contract/host-caps {}))
        linked (linker/link-provider provider-session (vec (butlast linker/http-links)))
        requested [:http-open :http-write :http-read :http-close]
        caps (contract/host-caps
              {:grants requested :limits {:allow-write-imports? true}})
        runs (doall
              (for [_ (range 16)]
                (future
                  (let [session (tender/open-session
                                 (wat->wasm component-consumer-wat) requested caps
                                 {:provider-host-functions linked})]
                    [(tender/session-call-main session)
                     (tender/read-memory-string (:instance session) 64 4)]))))]
    (is (every? #(= [4 "pong"] %) (map deref runs)))))

(deftest component-linker-rejects-scratch-overflow-before-provider-call
  (let [provider-session (tender/open-session
                          (wat->wasm component-provider-wat) []
                          (contract/host-caps {}))
        linked (linker/link-provider provider-session [(second linker/http-links)])
        caps (contract/host-caps
              {:grants [:http-write] :limits {:allow-write-imports? true}})
        consumer (tender/open-session
                  (wat->wasm component-overflow-consumer-wat) [:http-write] caps
                  {:provider-host-functions linked})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"scratch limit exceeded"
                          (tender/session-call-main consumer)))))

(deftest component-linker-runs-actual-compiled-kotoba-http-provider
  (let [forms (kotoba-runtime/read-file "../kotoba/providers/http_transport.kotoba"
                                        :kotoba)
        policy (edn/read-string
                (slurp "../kotoba/providers/transport_policy.edn"))
        artifact (kotoba-runtime/wasm-binary forms policy)
        _ (is (:kotoba.wasm/ok? artifact))
        lower [:transport-connect :tls-open :transport-write
               :transport-read :transport-close]
        lower-caps (contract/host-caps
                    {:grants lower
                     :limits {:max-transport-connections 1
                              :max-transport-read-bytes 4
                              :max-transport-write-bytes 4
                              :allow-write-imports? true
                              :transport-endpoint-allowlist #{"localhost:443"}}})
        lower-host-fns
        {:transport-connect
         (tender/host-fn "transport_connect"
                         [com.dylibso.chicory.wasm.types.ValType/I32
                          com.dylibso.chicory.wasm.types.ValType/I32
                          com.dylibso.chicory.wasm.types.ValType/I32]
                         com.dylibso.chicory.wasm.types.ValType/I64
                         (fn [_ _] 11))
         :tls-open
         (tender/host-fn "tls_open"
                         [com.dylibso.chicory.wasm.types.ValType/I64
                          com.dylibso.chicory.wasm.types.ValType/I32
                          com.dylibso.chicory.wasm.types.ValType/I32]
                         com.dylibso.chicory.wasm.types.ValType/I64
                         (fn [_ _] 12))
         :transport-write
         (tender/host-fn "transport_write"
                         [com.dylibso.chicory.wasm.types.ValType/I64
                          com.dylibso.chicory.wasm.types.ValType/I32
                          com.dylibso.chicory.wasm.types.ValType/I32]
                         com.dylibso.chicory.wasm.types.ValType/I32
                         (fn [_ args] (aget args 2)))
         :transport-read
         (tender/host-fn "transport_read"
                         [com.dylibso.chicory.wasm.types.ValType/I64
                          com.dylibso.chicory.wasm.types.ValType/I32
                          com.dylibso.chicory.wasm.types.ValType/I32]
                         com.dylibso.chicory.wasm.types.ValType/I32
                         (fn [instance args]
                           (.write (.memory instance) (int (aget args 1))
                                   (.getBytes "pong" "UTF-8") 0 4)
                           4))
         :transport-close
         (tender/host-fn "transport_close"
                         [com.dylibso.chicory.wasm.types.ValType/I64]
                         com.dylibso.chicory.wasm.types.ValType/I32
                         (fn [_ _] 0))}
        provider-session
        (tender/open-session (:kotoba.wasm/binary artifact) lower lower-caps
                             {:provider-host-functions lower-host-fns})
        high [:http-open :http-write :http-read :http-close]
        high-caps (contract/host-caps
                   {:grants high :limits {:allow-write-imports? true}})
        consumer-session
        (tender/open-session
         (wat->wasm component-consumer-wat) high high-caps
         {:provider-host-functions
          (linker/link-provider provider-session linker/http-links)})]
    (is (= 4 (tender/session-call-main consumer-session)))
    (is (= "pong"
           (tender/read-memory-string (:instance consumer-session) 64 4)))
    (let [instance (:instance provider-session)
          valid (.getBytes "HTTP/1.1 204 No Content\r\nX-Test: yes\r\n\r\n" "UTF-8")
          malformed (.getBytes "HTTP/1.1 200 OK\r\nX-Test: missing-end" "UTF-8")
          complete (.getBytes "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\npong" "UTF-8")
          partial (.getBytes "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\npo" "UTF-8")
          invalid-length (.getBytes "HTTP/1.1 200 OK\r\nContent-Length: x\r\n\r\n" "UTF-8")
          lowercase-length (.getBytes "HTTP/1.1 200 OK\r\ncontent-length: 4\r\n\r\npong" "UTF-8")
          duplicate-length (.getBytes "HTTP/1.1 200 OK\r\nContent-Length: 4\r\ncontent-length: 4\r\n\r\npong" "UTF-8")
          transfer-encoding (.getBytes "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\npong\r\n0\r\n\r\n" "UTF-8")
          lowercase-chunked (.getBytes "HTTP/1.1 200 OK\r\ntransfer-encoding: Chunked\r\n\r\n2\r\npo\r\n2\r\nng\r\n0\r\n\r\n" "UTF-8")
          cl-and-chunked (.getBytes "HTTP/1.1 200 OK\r\nContent-Length: 4\r\nTransfer-Encoding: chunked\r\n\r\n4\r\npong\r\n0\r\n\r\n" "UTF-8")
          chunk-extension (.getBytes "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4;x=y\r\npong\r\n0\r\n\r\n" "UTF-8")
          chunk-trailer (.getBytes "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\npong\r\n0\r\nX: y\r\n\r\n" "UTF-8")
          truncated-chunk (.getBytes "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\npo" "UTF-8")
          invoke (fn [export bytes]
                   (.write (.memory instance) 1024 bytes 0 (count bytes))
                   (aget ^longs (.apply (.export instance export)
                                       (long-array [1024 (count bytes)])) 0))]
      (is (= 204 (invoke "http-status-code" valid)))
      (is (= 1 (invoke "http-response-valid?" valid)))
      (is (= 0 (invoke "http-response-valid?" malformed)))
      (is (= 1 (invoke "http-response-valid?" complete)))
      (is (= 0 (invoke "http-response-valid?" partial)))
      (is (= 0 (invoke "http-response-valid?" invalid-length)))
      (is (= 1 (invoke "http-response-valid?" lowercase-length)))
      (is (= 0 (invoke "http-response-valid?" duplicate-length)))
      (is (= 1 (invoke "http-response-valid?" transfer-encoding)))
      (is (= 1 (invoke "http-response-valid?" lowercase-chunked)))
      (is (= 0 (invoke "http-response-valid?" cl-and-chunked)))
      (is (= 0 (invoke "http-response-valid?" chunk-extension)))
      (is (= 0 (invoke "http-response-valid?" chunk-trailer)))
      (is (= 0 (invoke "http-response-valid?" truncated-chunk)))
      (let [header-end (.indexOf (String. lowercase-chunked "UTF-8") "\r\n\r\n")
            normalized-len (invoke "http-normalize-response!" lowercase-chunked)]
        (is (= (+ header-end 8) normalized-len))
        (is (= "pong"
               (tender/read-memory-string instance (+ 1024 header-end 4) 4))))
      (is
       (every?
        true?
        (for [declared (range 0 17)
              actual (range 0 17)
              :let [response (.getBytes
                              (str "HTTP/1.1 200 OK\r\nContent-Length: " declared
                                   "\r\n\r\n" (apply str (repeat actual "x")))
                              "UTF-8")]]
          (= (if (>= actual declared) 1 0)
             (invoke "http-response-valid?" response))))
       "bounded Content-Length matrix rejects every partial body"))))

(deftest component-linker-rejects-invalid-or-missing-bindings
  (let [provider-session (tender/open-session
                          (wat->wasm component-provider-wat) []
                          (contract/host-caps {}))]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"manifest rejected"
         (linker/link-provider
          provider-session
          [(assoc (first linker/http-links) :component/copy-in [[0 9]])])))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"export unavailable"
         (linker/link-provider
          provider-session
          [(assoc (first linker/http-links) :component/export "missing")])))))

(deftest native-transport-provider-round-trips-through-real-local-socket
  (let [server (java.net.ServerSocket. 0 1 (java.net.InetAddress/getLoopbackAddress))
        port (.getLocalPort server)
        served (future
                 (with-open [socket (.accept server)]
                   (let [in (.getInputStream socket)
                         request (byte-array 4)]
                     (.readNBytes in request 0 4)
                     (when-not (= "ping" (String. request "UTF-8"))
                       (throw (ex-info "unexpected transport request" {})))
                     (doto (.getOutputStream socket)
                       (.write (.getBytes "pong" "UTF-8"))
                       (.flush))
                     nil)))
        requested [:transport-connect :transport-write :transport-read :transport-close]
        caps (contract/host-caps
              {:grants requested
               :limits {:max-transport-connections 1
                        :max-transport-connect-ms 1000
                        :max-transport-read-bytes 4
                        :max-transport-write-bytes 4
                        :allow-write-imports? true
                        :transport-endpoint-allowlist
                        #{(str "127.0.0.1:" port)}}})
        provider (transport-provider/native-provider caps)]
    (try
      (let [wasm (wat->wasm (transport-echo-wat port))
            session (tender/open-session
                     wasm requested caps
                     {:provider-host-functions (:host-functions provider)})]
        (is (= 4 (tender/session-call-main session)))
        (is (= "pong" (tender/read-memory-string (:instance session) 64 4)))
        (is (= {:connections 1 :read-bytes 4 :write-bytes 4}
               @(get-in provider [:state :usage])))
        (is (empty? @(get-in provider [:state :handles])))
        (is (nil? @served)))
      (finally
        ((:close! provider))
        (.close server)))))

(deftest native-transport-read-timeout-bounds-slow-peer
  (let [server (java.net.ServerSocket. 0 1 (java.net.InetAddress/getLoopbackAddress))
        port (.getLocalPort server)
        served (future
                 (with-open [socket (.accept server)]
                   (.readNBytes (.getInputStream socket) (byte-array 4) 0 4)
                   (Thread/sleep 200)
                   nil))
        requested [:transport-connect :transport-write :transport-read :transport-close]
        caps (contract/host-caps
              {:grants requested
               :limits {:max-transport-connections 1
                        :max-transport-connect-ms 1000
                        :max-transport-read-ms 50
                        :max-transport-read-bytes 4
                        :max-transport-write-bytes 4
                        :allow-write-imports? true
                        :transport-endpoint-allowlist
                        #{(str "127.0.0.1:" port)}}})
        provider (transport-provider/native-provider caps)]
    (try
      (let [session (tender/open-session
                     (wat->wasm (transport-echo-wat port)) requested caps
                     {:provider-host-functions (:host-functions provider)})
            started (System/nanoTime)
            result (tender/session-call-main session)
            elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)]
        (is (= -1 result))
        (is (< elapsed-ms 1000.0))
        (is (empty? @(get-in provider [:state :handles])))
        (is (nil? @served)))
      (finally
        ((:close! provider))
        (.close server)))))

(deftest native-transport-pins-resolved-address-before-connect
  (let [requested [:transport-connect]
        caps (contract/host-caps
              {:grants requested
               :limits {:max-transport-connections 1
                        :max-transport-connect-ms 1000
                        :transport-endpoint-allowlist #{"db.example.test:443"}
                        :transport-resolved-address-allowlist #{"192.0.2.10"}}})
        provider (transport-provider/native-provider
                  caps {:resolve-addresses
                        (fn [_] [(java.net.InetAddress/getLoopbackAddress)])})]
    (try
      (let [session (tender/open-session
                     (wat->wasm (transport-connect-only-wat "db.example.test" 443))
                     requested caps
                     {:provider-host-functions (:host-functions provider)})]
        (is (zero? (tender/session-call-main session)))
        (is (empty? @(get-in provider [:state :handles]))))
      (finally
        ((:close! provider))))))

(deftest affine-transport-handle-rejects-double-close
  (let [server (java.net.ServerSocket. 0 1 (java.net.InetAddress/getLoopbackAddress))
        port (.getLocalPort server)
        peer (future (with-open [socket (.accept server)]
                       (.read (.getInputStream socket))))
        requested [:transport-connect :transport-close]
        caps (contract/host-caps
              {:grants requested
               :limits {:max-transport-connections 1
                        :max-transport-connect-ms 1000
                        :allow-write-imports? true
                        :transport-endpoint-allowlist
                        #{(str "127.0.0.1:" port)}}})
        provider (transport-provider/native-provider caps)]
    (try
      (let [session (tender/open-session
                     (wat->wasm (transport-double-close-wat port)) requested caps
                     {:provider-host-functions (:host-functions provider)})]
        (is (= -1 (tender/session-call-main session)))
        (is (= -1 @peer))
        (is (empty? @(get-in provider [:state :handles]))))
      (finally
        ((:close! provider))
        (.close server)))))

(deftest native-transport-provider-performs-verified-tls-handshake
  (let [keystore (java.io.File/createTempFile "kototama-tls" ".p12")
        password "kototama-test-only"
        {:keys [server client]} (test-tls-contexts keystore password)
        listener (.createServerSocket (.getServerSocketFactory server) 0 1
                                      (java.net.InetAddress/getLoopbackAddress))
        port (.getLocalPort listener)
        served (future
                 (with-open [socket (.accept listener)]
                   (let [in (.getInputStream socket)
                         request (byte-array 4)]
                     (.readNBytes in request 0 4)
                     (when-not (= "ping" (String. request "UTF-8"))
                       (throw (ex-info "unexpected TLS request" {})))
                     (doto (.getOutputStream socket)
                       (.write (.getBytes "pong" "UTF-8"))
                       (.flush))
                     nil)))
        requested [:transport-connect :tls-open :transport-write
                   :transport-read :transport-close]
        caps (contract/host-caps
              {:grants requested
               :limits {:max-transport-connections 1
                        :max-transport-connect-ms 1000
                        :max-transport-read-bytes 4
                        :max-transport-write-bytes 4
                        :allow-write-imports? true
                        :transport-endpoint-allowlist
                        #{(str "localhost:" port)}}})
        provider (transport-provider/native-provider
                  caps {:ssl-socket-factory (.getSocketFactory client)})]
    (try
      (let [session (tender/open-session
                     (wat->wasm (tls-echo-wat port "localhost")) requested caps
                     {:provider-host-functions (:host-functions provider)})]
        (is (= 4 (tender/session-call-main session)))
        (is (= "pong" (tender/read-memory-string (:instance session) 64 4)))
        (is (= {:connections 1 :read-bytes 4 :write-bytes 4}
               @(get-in provider [:state :usage])))
        (is (empty? @(get-in provider [:state :handles])))
        (is (nil? @served)))
      (finally
        ((:close! provider))
        (.close listener)
        (.delete keystore)))))

(deftest native-transport-mtls-requires-present-client-certificate
  (doseq [[label factory-key expected-positive?]
          [[:mutual-client :mutual-client true]
           [:trust-only-client :client false]]]
    (let [keystore (java.io.File/createTempFile "kototama-mtls" ".p12")
          contexts (test-tls-contexts keystore "mtls-test-only")
          server (:server contexts)
          listener (.createServerSocket (.getServerSocketFactory server) 0 1
                                        (java.net.InetAddress/getLoopbackAddress))
          _ (.setNeedClientAuth ^javax.net.ssl.SSLServerSocket listener true)
          port (.getLocalPort listener)
          peer (future
                 (try
                   (with-open [socket (.accept listener)]
                     (.startHandshake ^javax.net.ssl.SSLSocket socket)
                     true)
                   (catch Exception _ false)))
          requested [:transport-connect :tls-open]
          caps (contract/host-caps
                {:grants requested
                 :limits {:max-transport-connections 1
                          :max-transport-connect-ms 1000
                          :transport-endpoint-allowlist
                          #{(str "localhost:" port)}}})
          provider (transport-provider/native-provider
                    caps {:ssl-socket-factory
                          (.getSocketFactory ^javax.net.ssl.SSLContext
                                             (get contexts factory-key))
                          :require-client-certificate? true})]
      (try
        (let [session (tender/open-session
                       (wat->wasm (tls-open-only-wat port "localhost"))
                       requested caps
                       {:provider-host-functions (:host-functions provider)})
              handle (tender/session-call-main session)
              decision @(get-in provider [:state :last-tls-decision])]
          (is (= expected-positive? (pos? handle)) (name label))
          (is (= expected-positive? (:tls/accepted? decision)) (name label))
          (is (= expected-positive? @peer) (name label)))
        (finally
          ((:close! provider))
          (.close listener)
          (.delete keystore))))))

(deftest tls-server-name-mismatch-consumes-and-closes-plain-handle
  (let [listener (java.net.ServerSocket. 0 1
                                         (java.net.InetAddress/getLoopbackAddress))
        port (.getLocalPort listener)
        peer-result (future
                      (with-open [socket (.accept listener)]
                        (.read (.getInputStream socket))))
        requested [:transport-connect :tls-open]
        caps (contract/host-caps
              {:grants requested
               :limits {:max-transport-connections 1
                        :max-transport-connect-ms 1000
                        :transport-endpoint-allowlist
                        #{(str "localhost:" port)}}})
        provider (transport-provider/native-provider caps)]
    (try
      (let [session (tender/open-session
                     (wat->wasm (tls-open-only-wat port "not-localhost"))
                     requested caps
                     {:provider-host-functions (:host-functions provider)})]
        (is (zero? (tender/session-call-main session)))
        (is (empty? @(get-in provider [:state :handles])))
        (is (= -1 @peer-result) "peer observes EOF; plaintext fallback is impossible"))
      (finally
        ((:close! provider))
        (.close listener)))))

(deftest actual-kotoba-consumer-links-through-kotoba-provider-to-native-tls
  (let [keystore (java.io.File/createTempFile "kototama-component-tls" ".p12")
        {:keys [server client]} (test-tls-contexts keystore "component-test-only")
        listener (.createServerSocket (.getServerSocketFactory server) 0 1
                                      (java.net.InetAddress/getLoopbackAddress))
        port (.getLocalPort listener)
        expected-request "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
        response "HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\npong"
        served (future
                 (with-open [socket (.accept listener)]
                   (let [request (byte-array (count (.getBytes expected-request "UTF-8")))]
                     (.readNBytes (.getInputStream socket) request 0 (count request))
                     (when-not (= expected-request (String. request "UTF-8"))
                       (throw (ex-info "unexpected linked HTTP payload"
                                       {:expected expected-request
                                        :actual (String. request "UTF-8")})))
                     (doto (.getOutputStream socket)
                       (.write (.getBytes response "UTF-8"))
                       (.flush))
                     nil)))
        lower [:transport-connect :tls-open :transport-write
               :transport-read :transport-close]
        lower-caps (contract/host-caps
                    {:grants lower
                     :limits {:max-transport-connections 1
                              :max-transport-connect-ms 1000
                              :max-transport-read-bytes 256
                              :max-transport-write-bytes 128
                              :allow-write-imports? true
                              :transport-endpoint-allowlist
                              #{(str "localhost:" port)}}})
        native (transport-provider/native-provider
                lower-caps {:ssl-socket-factory (.getSocketFactory client)})]
    (try
      (let [provider-wasm
            (compile-kotoba-provider
             "../kotoba/providers/http_transport.kotoba"
             "../kotoba/providers/transport_policy.edn")
            provider-session
            (tender/open-session provider-wasm lower lower-caps
                                 {:provider-host-functions (:host-functions native)})
            high [:http-get]
            high-caps (contract/host-caps
                       {:grants high
                        :limits {:max-http-gets 1
                                 :allow-write-imports? true}})
            consumer-wasm
            (compile-kotoba-provider
             "../kotoba/providers/http_consumer.kotoba"
             "../kotoba/providers/http_component_policy.edn")
            consumer-session
            (tender/open-session
             consumer-wasm high high-caps
             {:provider-host-functions
              (linker/link-provider provider-session linker/http-links)})
            result (aget ^longs
                         (.apply (.export (:instance consumer-session) "run")
                                 (long-array [port])) 0)]
        (is (= (count (.getBytes response "UTF-8")) result))
        (is (nil? @served))
        (is (empty? @(get-in native [:state :handles]))))
      (finally
        ((:close! native))
        (.close listener)
        (.delete keystore)))))

(deftest actual-kotoba-db-component-exchanges-bounded-frame-over-native-tls
  (let [keystore (java.io.File/createTempFile "kototama-db-component-tls" ".p12")
        {:keys [server client]} (test-tls-contexts keystore "db-component-test")
        listener (.createServerSocket (.getServerSocketFactory server) 0 1
                                      (java.net.InetAddress/getLoopbackAddress))
        port (.getLocalPort listener)
        request-bytes (byte-array [0 0 0 4 112 105 110 103])
        response-bytes (byte-array [0 0 0 4 112 111 110 103])
        served (future
                 (with-open [socket (.accept listener)]
                   (let [actual (byte-array 8)]
                     (.readNBytes (.getInputStream socket) actual 0 8)
                     (when-not (java.util.Arrays/equals request-bytes actual)
                       (throw (ex-info "unexpected linked DB frame" {})))
                     (doto (.getOutputStream socket)
                       (.write response-bytes)
                       (.flush))
                     nil)))
        lower [:transport-connect :tls-open :transport-write
               :transport-read :transport-close]
        lower-caps (contract/host-caps
                    {:grants lower
                     :limits {:max-transport-connections 1
                              :max-transport-connect-ms 1000
                              :max-transport-read-ms 1000
                              :max-transport-read-bytes 256
                              :max-transport-write-bytes 256
                              :allow-write-imports? true
                              :transport-endpoint-allowlist
                              #{(str "localhost:" port)}}})
        native (transport-provider/native-provider
                lower-caps {:ssl-socket-factory (.getSocketFactory client)})]
    (try
      (let [provider-session
            (tender/open-session
             (compile-kotoba-provider
              "../kotoba/providers/db_transport.kotoba"
              "../kotoba/providers/transport_policy.edn")
             lower lower-caps
             {:provider-host-functions (:host-functions native)})
            high [:db-exchange]
            consumer-session
            (tender/open-session
             (compile-kotoba-provider
              "../kotoba/providers/db_consumer.kotoba"
              "../kotoba/providers/db_component_policy.edn")
             high
             (contract/host-caps
              {:grants high :limits {:allow-write-imports? true}})
             {:provider-host-functions
              (linker/link-provider provider-session linker/db-links)})
            result (aget ^longs
                         (.apply (.export (:instance consumer-session) "run")
                                 (long-array [port])) 0)]
        (is (= 8 result))
        (is (nil? @served))
        (is (empty? @(get-in native [:state :handles]))))
      (finally
        ((:close! native))
        (.close listener)
        (.delete keystore)))))

(deftest actual-kotoba-postgresql-simple-query-frame-over-native-tls
  (let [keystore (java.io.File/createTempFile "kototama-pg-component-tls" ".p12")
        {:keys [server client]} (test-tls-contexts keystore "pg-component-test")
        listener (java.net.ServerSocket. 0 1 (java.net.InetAddress/getLoopbackAddress))
        port (.getLocalPort listener)
        request-bytes (byte-array [81 0 0 0 13 115 101 108 101 99 116 32 49 0])
        response-bytes (byte-array [82 0 0 0 8 0 0 0 0
                                    90 0 0 0 5 73])
        ssl-request (byte-array [0 0 0 8 4 -46 22 47])
        served (future
                 (with-open [plain (.accept listener)]
                   (let [actual-ssl (.readNBytes (.getInputStream plain) 8)]
                     (when-not (java.util.Arrays/equals ssl-request actual-ssl)
                       (throw (ex-info "unexpected PostgreSQL SSLRequest" {})))
                     (doto (.getOutputStream plain) (.write (byte-array [83])) (.flush))
                     (with-open [socket (.createSocket (.getSocketFactory server)
                                                       plain nil port true)]
                       (.setUseClientMode ^javax.net.ssl.SSLSocket socket false)
                       (.startHandshake ^javax.net.ssl.SSLSocket socket)
                       (let [actual (byte-array (count request-bytes))]
                         (.readNBytes (.getInputStream socket) actual 0 (count actual))
                         (when-not (java.util.Arrays/equals request-bytes actual)
                           (throw (ex-info "unexpected PostgreSQL Simple Query frame" {})))
                         (doto (.getOutputStream socket)
                           (.write response-bytes)
                           (.flush))))
                     nil)))
        lower [:transport-connect :tls-open :transport-write
               :transport-read :transport-close]
        lower-caps (contract/host-caps
                    {:grants lower
                     :limits {:max-transport-connections 1
                              :max-transport-connect-ms 1000
                              :max-transport-read-ms 1000
                              :max-transport-read-bytes 256
                              :max-transport-write-bytes 256
                              :allow-write-imports? true
                              :transport-endpoint-allowlist
                              #{(str "localhost:" port)}}})
        native (transport-provider/native-provider
                lower-caps {:ssl-socket-factory (.getSocketFactory client)})]
    (try
      (let [provider-session
            (tender/open-session
             (compile-kotoba-provider
              "../kotoba/providers/db_transport.kotoba"
              "../kotoba/providers/transport_policy.edn")
             lower lower-caps
             {:provider-host-functions (:host-functions native)})
            high [:pg-simple-query]
            consumer-session
            (tender/open-session
             (compile-kotoba-provider
              "../kotoba/providers/pg_consumer.kotoba"
              "../kotoba/providers/db_component_policy.edn")
             high
             (contract/host-caps
              {:grants high :limits {:allow-write-imports? true}})
             {:provider-host-functions
              (linker/link-provider provider-session linker/db-links)})
            result (aget ^longs
                         (.apply (.export (:instance consumer-session) "run")
                                 (long-array [port])) 0)]
        (is (= 15 result))
        (let [instance (:instance provider-session)
              valid (.getBytes "kotoba" "UTF-8")
              embedded-nul (byte-array [107 111 0 116 111 98 97])
              error-payload (byte-array
                             (concat [83] (.getBytes "ERROR" "UTF-8") [0]
                                     [67] (.getBytes "22012" "UTF-8") [0]
                                     [77] (.getBytes "division by zero" "UTF-8") [0 0]))
              error-response (byte-array
                              (concat [69 0 0 0 (+ 4 (count error-payload))]
                                      error-payload))
              notice-response (byte-array
                               (concat [78 0 0 0 (+ 4 (count error-payload))]
                                       error-payload))
              malformed-error (byte-array (butlast error-response))
              extended-ok (byte-array [50 0 0 0 4 90 0 0 0 5 73])
              extended-missing-ready (byte-array [50 0 0 0 4])
              bind-binary (byte-array [0 1 0 1 0 1 0 0 0 4 0 0 0 42 0 0])
              bind-null (byte-array [0 1 0 0 0 1 -1 -1 -1 -1 0 0])
              bind-bad-format (byte-array [0 1 0 2 0 1 -1 -1 -1 -1 0 0])
              bind-count-mismatch (byte-array [0 1 0 0 0 2 -1 -1 -1 -1 0 0])
              copy-out-header (byte-array [72 0 0 0 9 0 0 1 0 0])
              copy-out-bad-format (byte-array [72 0 0 0 9 2 0 1 0 0])
              copy-data (byte-array [100 0 0 0 6 49 10])
              copy-done (byte-array [99 0 0 0 4])
              copy-command (byte-array [67 0 0 0 11 67 79 80 89 32 49 0])
              ready-idle (byte-array [90 0 0 0 5 73])
              copy-out-valid (byte-array
                              (concat copy-out-header copy-data copy-done
                                      copy-command ready-idle))
              copy-out-missing-done (byte-array
                                     (concat copy-out-header copy-data
                                             copy-command ready-idle))
              batch-one-empty (byte-array [0 1 120 0 6 0 0 0 0 0 0])
              duplicate-sqlstate-payload
              (byte-array
               (concat [67] (.getBytes "22012" "UTF-8") [0]
                       [67] (.getBytes "XX000" "UTF-8") [0 0]))
              duplicate-sqlstate
              (byte-array
               (concat [69 0 0 0 (+ 4 (count duplicate-sqlstate-payload))]
                       duplicate-sqlstate-payload))
              invoke (fn [export bytes]
                       (.write (.memory instance) 2048 bytes 0 (count bytes))
                       (aget ^longs (.apply (.export instance export)
                                           (long-array [2048 (count bytes)])) 0))]
          (is (= 1 (invoke "pg-startup-field-valid?" valid)))
          (is (= 0 (invoke "pg-startup-field-valid?" embedded-nul)))
          (is (= 0 (invoke "pg-query-text-valid?" (byte-array 0))))
          (is (= 1 (invoke "pg-statement-name-valid?"
                           (.getBytes "sum2" "UTF-8"))))
          (is (= 0 (invoke "pg-statement-name-valid?" (byte-array 0))))
          (is (= 0 (invoke "pg-statement-name-valid?" embedded-nul)))
          (is (= 0 (invoke "pg-parameter-valid?" embedded-nul)))
          (is (= 0 (invoke "pg-parameter-valid?" (byte-array 1025))))
          (is (= 1 (aget ^longs
                         (.apply (.export instance "pg-extended-fixed-message-valid?")
                                 (long-array [115 5])) 0)))
          (is (= 0 (aget ^longs
                         (.apply (.export instance "pg-extended-fixed-message-valid?")
                                 (long-array [115 6])) 0)))
          (is (= 1 (invoke "pg-bind-params-valid?" bind-binary)))
          (is (= 1 (invoke "pg-bind-params-valid?" bind-null)))
          (is (= 0 (invoke "pg-bind-params-valid?" bind-bad-format)))
          (is (= 0 (invoke "pg-bind-params-valid?" bind-count-mismatch)))
          (is (= 0 (invoke "pg-bind-params-valid?"
                           (byte-array (butlast bind-binary)))))
          (.write (.memory instance) 2048 copy-out-header 0
                  (count copy-out-header))
          (is (= 1 (aget ^longs
                         (.apply (.export instance "pg-copy-response-valid?")
                                 (long-array [2048 (count copy-out-header) 72])) 0)))
          (.write (.memory instance) 2048 copy-out-bad-format 0
                  (count copy-out-bad-format))
          (is (= 0 (aget ^longs
                         (.apply (.export instance "pg-copy-response-valid?")
                                 (long-array [2048 (count copy-out-bad-format)
                                              72])) 0)))
          (.write (.memory instance) 2048 copy-out-valid 0
                  (count copy-out-valid))
          (is (= 1 (aget ^longs
                         (.apply (.export instance "pg-copy-out-result-valid-from?")
                                 (long-array [2048 (count copy-out-valid) 0 0])) 0)))
          (.write (.memory instance) 2048 copy-out-missing-done 0
                  (count copy-out-missing-done))
          (is (= 0 (aget ^longs
                         (.apply (.export instance "pg-copy-out-result-valid-from?")
                                 (long-array [2048 (count copy-out-missing-done)
                                              0 0])) 0)))
          (.write (.memory instance) 2048 batch-one-empty 0
                  (count batch-one-empty))
          (is (= 1 (aget ^longs
                         (.apply (.export instance "pg-batch-valid?")
                                 (long-array [2048 (count batch-one-empty) 1])) 0)))
          (is (= 0 (aget ^longs
                         (.apply (.export instance "pg-batch-valid?")
                                 (long-array [2048 (count batch-one-empty) 2])) 0)))
          (is (= 0 (aget ^longs
                         (.apply (.export instance "pg-batch-valid?")
                                 (long-array [2048 (count batch-one-empty) 9])) 0)))
          (.write (.memory instance) 2048 (byte-array 12) 0 12)
          (is (= 1 (aget ^longs
                         (.apply (.export instance "pg-oid-list-valid?")
                                 (long-array [2048 12 3])) 0)))
          (is (= 0 (aget ^longs
                         (.apply (.export instance "pg-oid-list-valid?")
                                 (long-array [2048 12 4])) 0)))
          (is (= 1 (invoke "pg-error-response-valid?" error-response)))
          (is (= 1 (invoke "pg-notice-response-valid?" notice-response)))
          (is (= 0 (invoke "pg-error-response-valid?" malformed-error)))
          (.write (.memory instance) 2048 extended-ok 0 (count extended-ok))
          (is (= 1 (aget ^longs
                         (.apply (.export instance "pg-extended-result-valid-from?")
                                 (long-array [2048 (count extended-ok) 0 0])) 0)))
          (.write (.memory instance) 2048 extended-missing-ready 0
                  (count extended-missing-ready))
          (is (= 0 (aget ^longs
                         (.apply (.export instance "pg-extended-result-valid-from?")
                                 (long-array [2048 (count extended-missing-ready)
                                              0 0])) 0)))
          (.write (.memory instance) 2048 duplicate-sqlstate 0
                  (count duplicate-sqlstate))
          (is (= 2 (aget ^longs
                         (.apply (.export instance "pg-error-count-field")
                                 (long-array [2048 (count duplicate-sqlstate)
                                              5 67 0])) 0))))
        (is (nil? @served))
        (is (empty? @(get-in native [:state :handles]))))
      (finally
        ((:close! native))
        (.close listener)
        (.delete keystore)))))

(deftest actual-kotoba-postgresql-sslrequest-startup-and-query-session
  (let [keystore (java.io.File/createTempFile "kototama-pg-session-tls" ".p12")
        {:keys [server client]} (test-tls-contexts keystore "pg-session-test")
        listener (java.net.ServerSocket. 0 1 (java.net.InetAddress/getLoopbackAddress))
        port (.getLocalPort listener)
        ssl-request (byte-array [0 0 0 8 4 -46 22 47])
        startup (byte-array
                 (concat [0 0 0 37 0 3 0 0]
                         (.getBytes "user" "UTF-8") [0]
                         (.getBytes "kotoba" "UTF-8") [0]
                         (.getBytes "database" "UTF-8") [0]
                         (.getBytes "kotoba" "UTF-8") [0 0]))
        query (byte-array [81 0 0 0 13 115 101 108 101 99 116 32 49 0])
        auth-ready (byte-array [82 0 0 0 8 0 0 0 0
                                83 0 0 0 8 107 0 118 0
                                75 0 0 0 12 0 0 0 1 0 0 0 2
                                90 0 0 0 5 73])
        query-response (byte-array
                        (concat [67 0 0 0 13]
                                (.getBytes "SELECT 1" "UTF-8") [0]
                                [90 0 0 0 5 73]))
        served
        (future
          (with-open [plain (.accept listener)]
            (let [actual-ssl (.readNBytes (.getInputStream plain) 8)]
              (when-not (java.util.Arrays/equals ssl-request actual-ssl)
                (throw (ex-info "unexpected PostgreSQL SSLRequest" {})))
              (doto (.getOutputStream plain) (.write (byte-array [83])) (.flush))
              (with-open [tls (.createSocket (.getSocketFactory server)
                                             plain nil port true)]
                (.setUseClientMode ^javax.net.ssl.SSLSocket tls false)
                (.startHandshake ^javax.net.ssl.SSLSocket tls)
                (let [actual-startup (.readNBytes (.getInputStream tls) (count startup))]
                  (when-not (java.util.Arrays/equals startup actual-startup)
                    (throw (ex-info "unexpected PostgreSQL StartupMessage" {}))))
                (doto (.getOutputStream tls) (.write auth-ready) (.flush))
                (let [actual-query (.readNBytes (.getInputStream tls) (count query))]
                  (when-not (java.util.Arrays/equals query actual-query)
                    (throw (ex-info "unexpected PostgreSQL Query" {}))))
                (doto (.getOutputStream tls) (.write query-response) (.flush))))
          nil))
        lower [:transport-connect :tls-open :transport-write
               :transport-read :transport-close]
        lower-caps (contract/host-caps
                    {:grants lower
                     :limits {:max-transport-connections 1
                              :max-transport-connect-ms 1000
                              :max-transport-read-ms 1000
                              :max-transport-read-bytes 512
                              :max-transport-write-bytes 512
                              :allow-write-imports? true
                              :transport-endpoint-allowlist
                              #{(str "localhost:" port)}}})
        native (transport-provider/native-provider
                lower-caps {:ssl-socket-factory (.getSocketFactory client)})]
    (try
      (let [provider-session
            (tender/open-session
             (compile-kotoba-provider
              "../kotoba/providers/db_transport.kotoba"
              "../kotoba/providers/transport_policy.edn")
             lower lower-caps
             {:provider-host-functions (:host-functions native)})
            high [:pg-open :pg-query :db-close]
            consumer-session
            (tender/open-session
             (compile-kotoba-provider
              "../kotoba/providers/pg_session_consumer.kotoba"
              "../kotoba/providers/db_component_policy.edn")
             high
             (contract/host-caps
              {:grants high :limits {:allow-write-imports? true}})
             {:provider-host-functions
              (linker/link-provider provider-session linker/db-links)})
            result (aget ^longs
                         (.apply (.export (:instance consumer-session) "run")
                                 (long-array [port])) 0)]
        (is (= (count query-response) result))
        (let [instance (:instance provider-session)
              invoke (fn [bytes]
                       (.write (.memory instance) 4096 bytes 0 (count bytes))
                       (aget ^longs
                             (.apply (.export instance "pg-startup-response-valid?")
                                     (long-array [4096 (count bytes)])) 0))]
          (is (= 1 (invoke auth-ready)))
          (is (= 0 (invoke (byte-array [69 0 0 0 5 0]))))
          (is (= 0 (invoke (byte-array [82 0 0 0 8 0 0 0 0
                                        82 0 0 0 8 0 0 0 0
                                        90 0 0 0 5 73])))))
        (is (nil? @served))
        (is (empty? @(get-in native [:state :handles]))))
      (finally
        ((:close! native))
        (.close listener)
        (.delete keystore)))))

(deftest purpose-bound-scram-sha256-keeps-password-out-of-guest-memory
  (let [caps (contract/host-caps
              {:grants [:scram-sha256]
               :limits {:max-scram-proofs 1
                        :max-transport-connections 1
                        :allow-secret-imports? true
                        :allow-write-imports? true
                        :scram-credential-allowlist #{"db/primary"}}})
        session (tender/open-session
                 (wat->wasm scram-sha256-wat) [:scram-sha256] caps
                 {:scram-credentials {"db/primary" (.toCharArray "pencil")}})]
    (is (= 64 (tender/session-call-main session)))
    (is (= (str "9213ee9db10dd5b4ba651d747907231006195be4fd692071ef2fd43184e35dd9"
                "3d4674feeaf15fce9e19687fa5a0d5e57c4d2526b5fc84858f7882036071693d")
           (apply str
                  (map #(format "%02x" (bit-and 255 %))
                       (.readBytes (.memory (:instance session)) 512 64)))))
    (is (= -1 (tender/session-call-main session)) "proof quota is call-time enforced")))

(deftest actual-kotoba-scram-component-validates-sasl-nonce-and-base64
  (let [requested [:transport-connect :tls-open :tls-server-end-point
                   :transport-write :transport-read
                   :transport-close :scram-sha256 :pg-cancel-register
                   :pg-cancel :random-bytes]
        caps (contract/host-caps
              {:grants requested
               :limits {:max-scram-proofs 1
                        :max-pg-cancel-handles 1
                        :max-pg-cancel-requests 1
                        :max-transport-connections 1
                        :allow-secret-imports? true
                        :allow-write-imports? true
                        :scram-credential-allowlist #{"db/primary"}}})
        native (transport-provider/native-provider caps)
        session (tender/open-session
                 (compile-kotoba-provider
                  "../kotoba/providers/pg_scram.kotoba"
                  "../kotoba/providers/pg_scram_policy.edn")
                 requested caps
                 {:scram-credentials {"db/primary" (.toCharArray "pencil")}
                  :provider-host-functions (:host-functions native)})
        instance (:instance session)
        sasl (byte-array
              (concat [82 0 0 0 23 0 0 0 10]
                      (.getBytes "SCRAM-SHA-256" "UTF-8") [0 0]))
        wrong-mechanism (aclone sasl)
        sasl-plus (byte-array
                   (concat [82 0 0 0 42 0 0 0 10]
                           (.getBytes "SCRAM-SHA-256-PLUS" "UTF-8") [0]
                           (.getBytes "SCRAM-SHA-256" "UTF-8") [0 0]))
        nonce (.getBytes "fyko+d2lbbFgONRv9qkxdawL" "UTF-8")
        server-nonce (.getBytes "fyko+d2lbbFgONRv9qkxdawLserver" "UTF-8")
        salt-b64 (.getBytes "W22ZaJ0SNY7soEsUEjb6gQ==" "UTF-8")
        noncanonical-b64 (.getBytes "W22ZaJ0SNY7soEsUEjb6gR==" "UTF-8")
        server-first-text (.getBytes
                           "r=fyko+d2lbbFgONRv9qkxdawLserver,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096"
                           "UTF-8")
        server-first (byte-array
                      (concat [82 0 0 0 (+ 8 (count server-first-text))
                               0 0 0 11]
                              server-first-text))
        server-signature (byte-array
                          (map #(unchecked-byte (Integer/parseInt % 16))
                               (re-seq #".." "3d4674feeaf15fce9e19687fa5a0d5e57c4d2526b5fc84858f7882036071693d")))
        client-proof (byte-array
                      (map #(unchecked-byte (Integer/parseInt % 16))
                           (re-seq #".." "9213ee9db10dd5b4ba651d747907231006195be4fd692071ef2fd43184e35dd9")))
        channel-binding (byte-array (map unchecked-byte (range 32)))
        server-final-text (.getBytes
                           "v=PUZ0/urxX86eGWh/paDV5XxNJSa1/ISFj3iCA2BxaT0=" "UTF-8")
        server-final (byte-array
                      (concat [82 0 0 0 (+ 8 (count server-final-text))
                               0 0 0 12]
                              server-final-text))
        invoke (fn [export args]
                 (aget ^longs (.apply (.export instance export)
                                     (long-array args)) 0))]
    (aset-byte wrong-mechanism 9 (byte 88))
    (.write (.memory instance) 10000 sasl 0 (count sasl))
    (is (= 1 (invoke "pg-authentication-sasl?" [10000 (count sasl)])))
    (is (= 1 (invoke "pg-authentication-sasl-mechanism" [10000 (count sasl)])))
    (.write (.memory instance) 10000 sasl-plus 0 (count sasl-plus))
    (is (= 1 (invoke "pg-authentication-sasl?" [10000 (count sasl-plus)])))
    (is (= 2 (invoke "pg-authentication-sasl-mechanism"
                     [10000 (count sasl-plus)])))
    (.write (.memory instance) 10000 wrong-mechanism 0 (count wrong-mechanism))
    (is (= 0 (invoke "pg-authentication-sasl?" [10000 (count wrong-mechanism)])))
    (.write (.memory instance) 2048 nonce 0 (count nonce))
    (.write (.memory instance) 2200 server-nonce 0 (count server-nonce))
    (.write (.memory instance) 2600 channel-binding 0 (count channel-binding))
    (is (= 1 (invoke "pg-scram-client-nonce-valid?" [2048 (count nonce)])))
    (is (= 1 (invoke "pg-scram-server-nonce-valid?"
                     [2048 (count nonce) 2200 (count server-nonce) 0])))
    (let [n (invoke "pg-scram-client-first-for!"
                    [2 2048 (count nonce) 3600 512])]
      (is (= (str "p=tls-server-end-point,,n=,r=" (String. nonce "UTF-8"))
             (String. (.readBytes (.memory instance) 3600 n) "UTF-8"))))
    (let [n (invoke "pg-scram-client-final-without-proof-for!"
                    [2 2600 32 2200 (count server-nonce) 3600 512])
          encoded (.encodeToString (java.util.Base64/getEncoder)
                                   (byte-array
                                    (concat (.getBytes "p=tls-server-end-point,," "UTF-8")
                                            channel-binding)))]
      (is (= (str "c=" encoded ",r=" (String. server-nonce "UTF-8"))
             (String. (.readBytes (.memory instance) 3600 n) "UTF-8"))))
    (.write (.memory instance) 2300 salt-b64 0 (count salt-b64))
    (is (= 16 (invoke "pg-scram-base64-decode!" [2300 (count salt-b64) 2400 16])))
    (is (= "5b6d99689d12358eeca04b141236fa81"
           (apply str
                  (map #(format "%02x" (bit-and 255 %))
                       (.readBytes (.memory instance) 2400 16)))))
    (is (= -1 (invoke "pg-scram-base64-decode!" [2300 (count salt-b64) 2400 15])))
    (.write (.memory instance) 2300 noncanonical-b64 0 (count noncanonical-b64))
    (is (= -1 (invoke "pg-scram-base64-decode!"
                      [2300 (count noncanonical-b64) 2400 16])))
    (.write (.memory instance) 2048 nonce 0 (count nonce))
    (.write (.memory instance) 2600 server-first 0 (count server-first))
    (is (= 4096 (invoke "pg-scram-server-first-valid?"
                        [2600 (count server-first) 2048 (count nonce) 2800 16])))
    (aset-byte server-first 9 (byte 120))
    (.write (.memory instance) 2600 server-first 0 (count server-first))
    (is (= -1 (invoke "pg-scram-server-first-valid?"
                      [2600 (count server-first) 2048 (count nonce) 2800 16])))
    (.write (.memory instance) 3000 server-final 0 (count server-final))
    (.write (.memory instance) 3200 server-signature 0 32)
    (is (= 1 (invoke "pg-scram-server-final-valid?"
                     [3000 (count server-final) 3200])))
    (aset-byte server-signature 0 (byte 0))
    (.write (.memory instance) 3200 server-signature 0 32)
    (is (= 0 (invoke "pg-scram-server-final-valid?"
                     [3000 (count server-final) 3200])))
    (.write (.memory instance) 3400 client-proof 0 32)
    (is (= 44 (invoke "pg-scram-base64-encode!" [3400 32 3500 44])))
    (is (= "khPunbEN1bS6ZR10eQcjEAYZW+T9aSBx7y/UMYTjXdk="
           (tender/read-memory-string instance 3500 44)))
    (.write (.memory instance) 2048 nonce 0 (count nonce))
    (.write (.memory instance) 2200 server-nonce 0 (count server-nonce))
    (.write (.memory instance) 3400 client-proof 0 32)
    (is (= (+ 8 (count nonce))
           (invoke "pg-scram-client-first!" [2048 (count nonce) 3600 256])))
    (is (= (str "n,,n=,r=" (String. nonce "UTF-8"))
           (tender/read-memory-string instance 3600 (+ 8 (count nonce)))))
    (let [final-len (invoke "pg-scram-client-final!"
                            [2200 (count server-nonce) 3400 32 4000 256])]
      (is (= (str "c=biws,r=" (String. server-nonce "UTF-8")
                  ",p=khPunbEN1bS6ZR10eQcjEAYZW+T9aSBx7y/UMYTjXdk=")
             (tender/read-memory-string instance 4000 final-len))))
    (let [client-first (.getBytes
                        (str "n,,n=,r=" (String. nonce "UTF-8")) "UTF-8")
          initial-len (invoke "pg-scram-sasl-initial!"
                              [2048 (count nonce) 4300 256])
          declared (- (+ 23 (count client-first)) 1)
          expected (byte-array
                    (concat [112 0 0 0 declared]
                            (.getBytes "SCRAM-SHA-256" "UTF-8") [0]
                            [0 0 0 (count client-first)] client-first))]
      (is (= (count expected) initial-len))
      (let [actual (.readBytes (.memory instance) 4300 initial-len)]
        (is (java.util.Arrays/equals expected actual)
            (str "expected=" (vec expected) " actual=" (vec actual)))))
    (let [client-final (.getBytes
                        (str "c=biws,r=" (String. server-nonce "UTF-8")
                             ",p=khPunbEN1bS6ZR10eQcjEAYZW+T9aSBx7y/UMYTjXdk=")
                        "UTF-8")
          response-len (invoke "pg-scram-sasl-response!"
                               [2200 (count server-nonce) 3400 32 4600 256])
          expected (byte-array
                    (concat [112 0 0 0 (+ 4 (count client-final))]
                            client-final))]
      (is (= (count expected) response-len))
      (is (java.util.Arrays/equals
           expected (.readBytes (.memory instance) 4600 response-len))))
    ((:close! native))))

(deftest actual-kotoba-postgresql-scram-session-over-native-tls
  (let [keystore (java.io.File/createTempFile "kototama-pg-scram-tls" ".p12")
        {:keys [server client]} (test-tls-contexts keystore "pg-scram-test")
        listener (java.net.ServerSocket. 0 1 (java.net.InetAddress/getLoopbackAddress))
        port (.getLocalPort listener)
        nonce "AAECAwQFBgcICQoLDA0ODxAR"
        server-nonce (str nonce "server")
        server-first-text (str "r=" server-nonce
                               ",s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096")
        client-first (str "n,,n=,r=" nonce)
        client-final (str "c=biws,r=" server-nonce
                          ",p=HgZabYoMCj6U4Gg0Rb2mXOjN1ZEwkJwDNV8RJ88IPNo=")
        server-final-text "v=QAdQmJ+lRK7pdc/DBEeFAbUmtBhFLhQXsQNpv3KJgJQ="
        ssl-request (byte-array [0 0 0 8 4 -46 22 47])
        startup (byte-array
                 (concat [0 0 0 37 0 3 0 0]
                         (.getBytes "user" "UTF-8") [0]
                         (.getBytes "kotoba" "UTF-8") [0]
                         (.getBytes "database" "UTF-8") [0]
                         (.getBytes "kotoba" "UTF-8") [0 0]))
        auth-sasl (byte-array
                   (concat [82 0 0 0 23 0 0 0 10]
                           (.getBytes "SCRAM-SHA-256" "UTF-8") [0 0]))
        initial (byte-array
                 (concat [112 0 0 0 (+ 22 (count client-first))]
                         (.getBytes "SCRAM-SHA-256" "UTF-8") [0]
                         [0 0 0 (count client-first)]
                         (.getBytes client-first "UTF-8")))
        server-first-bytes (.getBytes server-first-text "UTF-8")
        auth-continue (byte-array
                       (concat [82 0 0 0 (+ 8 (count server-first-bytes))
                                0 0 0 11]
                               server-first-bytes))
        client-final-bytes (.getBytes client-final "UTF-8")
        response (byte-array
                  (concat [112 0 0 0 (+ 4 (count client-final-bytes))]
                          client-final-bytes))
        server-final-bytes (.getBytes server-final-text "UTF-8")
        auth-final (byte-array
                    (concat [82 0 0 0 (+ 8 (count server-final-bytes))
                             0 0 0 12]
                            server-final-bytes))
        startup-tail (byte-array [82 0 0 0 8 0 0 0 0
                                  83 0 0 0 8 107 0 118 0
                                  83 0 0 0 22
                                  97 112 112 108 105 99 97 116 105 111 110 95
                                  110 97 109 101 0 0
                                  75 0 0 0 12 0 0 0 1 0 0 0 2
                                  90 0 0 0 5 73])
        query (byte-array [81 0 0 0 13 115 101 108 101 99 116 32 49 0])
        query-response (byte-array
                        (concat [67 0 0 0 13]
                                (.getBytes "SELECT 1" "UTF-8") [0]
                                [90 0 0 0 5 73]))
        served
        (future
          (with-open [plain (.accept listener)]
            (let [actual (.readNBytes (.getInputStream plain) 8)]
              (when-not (java.util.Arrays/equals ssl-request actual)
                (throw (ex-info "unexpected SCRAM SSLRequest" {})))
              (doto (.getOutputStream plain) (.write (byte-array [83])) (.flush))
              (with-open [tls (.createSocket (.getSocketFactory server)
                                             plain nil port true)]
                (.setUseClientMode ^javax.net.ssl.SSLSocket tls false)
                (.startHandshake ^javax.net.ssl.SSLSocket tls)
                (doseq [[expected reply]
                        [[startup auth-sasl]
                         [initial auth-continue]
                         [response auth-final]]]
                  (let [actual (.readNBytes (.getInputStream tls) (count expected))]
                    (when-not (java.util.Arrays/equals expected actual)
                      (throw (ex-info "unexpected SCRAM client frame"
                                      {:expected (vec expected) :actual (vec actual)}))))
                  (doto (.getOutputStream tls) (.write reply) (.flush)))
                (doto (.getOutputStream tls) (.write startup-tail) (.flush))
                (let [actual-query (.readNBytes (.getInputStream tls) (count query))]
                  (when-not (java.util.Arrays/equals query actual-query)
                    (throw (ex-info "unexpected SCRAM session Query" {}))))
                (doto (.getOutputStream tls) (.write query-response) (.flush)))))
          nil)
        secure-random (proxy [java.security.SecureRandom] []
                        (nextBytes [bytes]
                          (dotimes [i (alength ^bytes bytes)]
                            (aset-byte ^bytes bytes i (unchecked-byte i)))))
        lower [:transport-connect :tls-open :tls-server-end-point
               :transport-write :transport-read
               :transport-close :scram-sha256 :pg-cancel-register
               :pg-cancel :random-bytes]
        lower-caps (contract/host-caps
                    {:grants lower
                     :limits {:max-transport-connections 1
                              :max-transport-connect-ms 1000
                              :max-transport-read-ms 1000
                              :max-transport-read-bytes 8192
                              :max-transport-write-bytes 2048
                              :max-scram-proofs 1
                              :max-pg-cancel-handles 1
                              :max-pg-cancel-requests 1
                              :max-random-bytes 18
                              :allow-secret-imports? true
                              :allow-write-imports? true
                              :scram-credential-allowlist #{"db/primary"}
                              :transport-endpoint-allowlist
                              #{(str "localhost:" port)}}})
        native (transport-provider/native-provider
                lower-caps {:ssl-socket-factory (.getSocketFactory client)})]
    (try
      (let [provider-session
            (tender/open-session
             (compile-kotoba-provider
              "../kotoba/providers/pg_scram.kotoba"
              "../kotoba/providers/pg_scram_policy.edn")
             lower lower-caps
             {:scram-credentials {"db/primary" (.toCharArray "pencil")}
              :secure-random secure-random
              :provider-host-functions (:host-functions native)})
            query-provider-session
            (tender/open-session
             (compile-kotoba-provider
              "../kotoba/providers/db_transport.kotoba"
              "../kotoba/providers/transport_policy.edn")
             [:transport-connect :tls-open :transport-write :transport-read
              :transport-close]
             lower-caps
             {:provider-host-functions (:host-functions native)})
            high [:pg-query :pg-open-scram-random :pg-close-scram]
            consumer-session
            (tender/open-session
             (compile-kotoba-provider
              "../kotoba/providers/pg_scram_consumer.kotoba"
              "../kotoba/providers/db_component_policy.edn")
             high
             (contract/host-caps
             {:grants high
               :limits {:allow-secret-imports? true :allow-write-imports? true}})
             {:provider-host-functions
              (merge (linker/link-provider query-provider-session linker/db-links)
                     (linker/link-provider provider-session linker/scram-links))})]
        (let [result (aget ^longs
                           (.apply (.export (:instance consumer-session) "run")
                                   (long-array [port])) 0)]
          (is (= (count query-response) result)
              (str "result=" result
                   " handles=" @(get-in native [:state :handles])
                   " usage=" @(get-in native [:state :usage])
                   " server=" (deref served 1000 :pending))))
        (is (nil? @served))
        (is (empty? @(get-in native [:state :handles]))))
      (finally
        ((:close! native))
        (.close listener)
        (.delete keystore)))))

(deftest http-url-allowlist-least-privilege
  (testing "nil allowlist is unrestricted (legacy); empty set denies; prefix matches"
    (let [allowed? @#'tender/http-url-allowed?]
      (is (true? (allowed? nil "http://evil.example/")))
      (is (false? (allowed? #{} "http://evil.example/")))
      (is (true? (allowed? #{"http://127.0.0.1:9/"} "http://127.0.0.1:9/x")))
      (is (false? (allowed? #{"http://127.0.0.1:9/"} "http://evil.example/"))))))

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
        ok? (tender/run-main wasm [:gen-keypair :sign :verify] caps
                             {:allow-legacy-raw-crypto? true})]
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
          written (tender/run-main wasm [:gen-keypair] caps
                                   {:allow-legacy-raw-crypto? true})]
      (is (= 64 written) "32-byte seed + 32-byte derived pubkey"))))

(deftest raw-guest-private-key-abi-is-legacy-only
  (let [caps (contract/host-caps {:grants [:gen-keypair]
                                  :limits {:allow-secret-imports? true}})]
    (try
      (tender/open-session (byte-array 0) [:gen-keypair] caps)
      (is false "raw guest private-key generation must fail closed")
      (catch clojure.lang.ExceptionInfo e
        (is (= :kagi/non-exportable-key-required
               (:kototama.tender/reason (ex-data e))))))))
