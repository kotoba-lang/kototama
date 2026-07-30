(ns kototama.host-parity-live
  "T8.4 first slice — live host runners for host-parity critical imports.

  `kotoba.lang.host-parity/run-conformance` is pure matrix scoring. This ns
  **actually links and calls** selected imports on the JVM tender (Chicory)
  and reports which host-parity case ids are live-proven.

  Browser coverage remains `web/verify-actor-host.mjs` (sha256 + clock +
  log-write + session revoke). This slice does not replace that path.

  ADR: docs/grade-a-host-parity-live-runner.md"
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [kototama.contract :as contract]
            [kototama.tender :as tender]))

(defn- wat->wasm
  "Assemble WAT → Wasm via wasm-tools (same path as tender-test)."
  [wat]
  (let [in (java.io.File/createTempFile "host-parity-live" ".wat")
        out (java.io.File/createTempFile "host-parity-live" ".wasm")]
    (try
      (spit in wat)
      (let [{:keys [exit err]}
            (shell/sh "wasm-tools" "parse" (.getPath in) "-o" (.getPath out))]
        (when-not (zero? exit)
          (throw (ex-info "wasm-tools parse failed"
                          {:stderr err :wat wat}))))
      (with-open [is (io/input-stream out)]
        (.readAllBytes is))
      (finally
        (.delete in)
        (.delete out)))))

;; Minimal guests — prove import linkage, not algorithm correctness
;; (correctness already covered by tender_test + actor-host smokes).

(def ^:private sha256-hex-wat
  "(module
     (import \"kotoba\" \"sha256_hex\" (func $sha256_hex (param i32 i32 i32 i32) (result i32)))
     (memory (export \"memory\") 1)
     (data (i32.const 0) \"hello\")
     (func (export \"main\") (result i64)
       (i64.extend_i32_s (call $sha256_hex (i32.const 0) (i32.const 5) (i32.const 100) (i32.const 64)))))")

(def ^:private clock-monotonic-wat
  "(module
     (import \"kotoba\" \"clock_monotonic\" (func $clock_monotonic (result i64)))
     (func (export \"main\") (result i64) (call $clock_monotonic)))")

(def ^:private log-write-wat
  "(module
     (import \"kotoba\" \"log_write\" (func $log_write (param i32 i32) (result i32)))
     (memory (export \"memory\") 1)
     (data (i32.const 0) \"ok\")
     (func (export \"main\") (result i64)
       (i64.extend_i32_s (call $log_write (i32.const 0) (i32.const 2)))))")

(def ^:private gen-keypair-wat
  "Writes 32-byte seed + 32-byte pubkey (64 bytes total)."
  "(module
     (import \"kotoba\" \"gen_keypair\" (func $gen_keypair (param i32 i32) (result i32)))
     (memory (export \"memory\") 1)
     (func (export \"main\") (result i64)
       (i64.extend_i32_s (call $gen_keypair (i32.const 0) (i32.const 64)))))")

(def jvm-live-corpus
  "Host-parity case ids that this runner can live-prove on JVM tender.
  Ids match lang/host-parity.edn :conformance :cases."
  [{:id :sha256-hex-all-available
    :import :sha256-hex
    :host :jvm
    :wat sha256-hex-wat
    :check (fn [n] (= 64 n))}
   {:id :clock-monotonic-all
    :import :clock-monotonic
    :host :jvm
    :wat clock-monotonic-wat
    :check (fn [n] (pos? n))}
   {:id :log-write-all-available
    :import :log-write
    :host :jvm
    :wat log-write-wat
    :check (fn [n] (not (neg? n)))}
   {:id :gen-keypair-all-available
    :import :gen-keypair
    :host :jvm
    :wat gen-keypair-wat
    :check (fn [n] (= 64 n))}])

(defn prove-import
  "Live-run one corpus entry on JVM tender. Returns
  {:ok? bool :id ... :import ... :result ... :error? ...}."
  [{:keys [id import wat check] :as entry}]
  (try
    (let [wasm (wat->wasm wat)
          caps (contract/host-caps
                {:grants #{import}
                 :limits {:allow-write-imports? true
                          :allow-secret-imports? true
                          :max-log-write-bytes 1024}})
          result (tender/run-main wasm [import] caps)
          ok? (boolean (and (number? result) (check result)))]
      {:ok? ok?
       :id id
       :import import
       :host :jvm
       :result result
       :live? true})
    (catch Exception e
      {:ok? false
       :id id
       :import import
       :host :jvm
       :live? true
       :error (.getMessage e)
       :error-class (.getName (class e))})))

(defn run-jvm-live
  "Run the JVM live corpus. Returns
  {:ok? bool :total N :passed N :failed [...] :results [...] :host :jvm
   :note ...}."
  []
  (let [results (mapv prove-import jvm-live-corpus)
        failed (filterv (complement :ok?) results)]
    {:ok? (empty? failed)
     :host :jvm
     :total (count results)
     :passed (- (count results) (count failed))
     :failed failed
     :results results
     :case-ids (mapv :id jvm-live-corpus)
     :note "T8.4 first slice: JVM tender live proofs for critical crypto/clock/log imports. Browser remains web/verify-actor-host.mjs."}))

(defn report
  "Compact snapshot for doctor/CLI."
  []
  (let [live (run-jvm-live)]
    {:t84-slice :jvm-live-first
     :live live
     :browser-runner "web/verify-actor-host.mjs"
     :matrix-runner "kotoba.lang.host-parity/run-conformance (pure data)"}))
