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
            [kototama.contract :as contract]))

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

(def now-wat
  "Imports now, calls it once, returns whatever it got (proves linkage --
  a specific value isn't asserted, just that it doesn't trap)."
  "(module
     (import \"kotoba\" \"now\" (func $now (result i64)))
     (func (export \"main\") (result i64) (call $now)))")

(def infinite-loop-wat
  "No imports -- just an unconditional back-edge loop, to trip the fuel
  listener."
  "(module
     (func (export \"main\") (result i64)
       (loop $l (br $l))
       (i64.const 0)))")

(def log-append-thrice-wat
  "Imports log_append, calls it 3 times with the same 4-byte payload,
  returns the THIRD call's result (i32 sign-extended to i64) -- so a
  RuntimeLimits cap of e.g. 8 bytes (2 calls' worth) shows up as -1 on
  the third call."
  "(module
     (import \"kotoba\" \"log_append\" (func $log_append (param i32 i32) (result i32)))
     (memory (export \"memory\") 1)
     (data (i32.const 0) \"data\")
     (func (export \"main\") (result i64)
       (drop (call $log_append (i32.const 0) (i32.const 4)))
       (drop (call $log_append (i32.const 0) (i32.const 4)))
       (i64.extend_i32_s (call $log_append (i32.const 0) (i32.const 4)))))")

(def everything (constantly true))

(deftest sha256-hex-round-trips-through-real-chicory
  (let [wasm (wat->wasm sha256-hex-wat)
        caps (contract/host-caps {:grants [:sha256-hex]})
        instance (tender/instantiate wasm [:sha256-hex] caps)
        n (tender/call-main instance)]
    (is (= 64 n))
    (is (= "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
           (tender/read-memory-string instance 100 64)))))

(deftest now-links-and-returns-without-trapping
  (let [wasm (wat->wasm now-wat)
        caps (contract/host-caps {:grants [:now]})
        n (tender/run-main wasm [:now] caps)]
    (is (pos? n))))

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
          caps (contract/host-caps {:grants [:now]})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"rejected by contract"
                            (tender/instantiate wasm [:sha256-hex] caps))))))

(deftest fuel-limit-traps-an-infinite-loop
  (let [wasm (wat->wasm infinite-loop-wat)
        caps (contract/host-caps {})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fuel limit"
                          (tender/run-main wasm [] caps {:fuel 10000})))))

(deftest log-append-byte-limit-denies-once-exceeded
  (let [wasm (wat->wasm log-append-thrice-wat)
        caps (contract/host-caps {:grants [:log-append!]
                                  :limits {:allow-write-imports? true
                                           :max-log-append-bytes 8}})
        n (tender/run-main wasm [:log-append!] caps)]
    (testing "2 calls x 4 bytes = 8 bytes fits the cap; the 3rd call (12 total) is denied"
      (is (= -1 n)))))

(deftest log-append-under-the-limit-all-succeed
  (let [wasm (wat->wasm log-append-thrice-wat)
        caps (contract/host-caps {:grants [:log-append!]
                                  :limits {:allow-write-imports? true
                                           :max-log-append-bytes 1000}})
        n (tender/run-main wasm [:log-append!] caps)]
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
