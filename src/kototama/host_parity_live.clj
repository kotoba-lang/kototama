(ns kototama.host-parity-live
  "T8.4 first slice — live host runners for host-parity critical imports.

  `kotoba.lang.host-parity/run-conformance` is pure matrix scoring. This ns
  **actually links and calls** selected imports on the JVM tender (Chicory)
  and reports which host-parity case ids are live-proven.

  Browser/Node live coverage: `web/verify-host-parity-live.mjs` (actor-host
  under Node WebAssembly; maps host-parity case ids). Legacy smoke:
  `web/verify-actor-host.mjs`.

  ADR: docs/grade-a-host-parity-live-runner.md"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
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

(def ^:private sign-wat
  "gen_keypair → sign fixed message \"ok\"; returns signature byte count (64)."
  "(module
     (import \"kotoba\" \"gen_keypair\" (func $gen_keypair (param i32 i32) (result i32)))
     (import \"kotoba\" \"sign\" (func $sign (param i32 i32 i32 i32 i32) (result i32)))
     (memory (export \"memory\") 1)
     (data (i32.const 64) \"ok\")
     (func (export \"main\") (result i64)
       (drop (call $gen_keypair (i32.const 0) (i32.const 64)))
       (i64.extend_i32_s
         (call $sign
           (i32.const 0)   ;; seed
           (i32.const 64)  ;; msg
           (i32.const 2)
           (i32.const 128) ;; sig out
           (i32.const 64)))))")

(def ^:private verify-wat
  "gen_keypair → sign → verify; returns 1 on success."
  "(module
     (import \"kotoba\" \"gen_keypair\" (func $gen_keypair (param i32 i32) (result i32)))
     (import \"kotoba\" \"sign\" (func $sign (param i32 i32 i32 i32 i32) (result i32)))
     (import \"kotoba\" \"verify\" (func $verify (param i32 i32 i32 i32 i32 i32) (result i32)))
     (memory (export \"memory\") 1)
     (data (i32.const 64) \"ok\")
     (func (export \"main\") (result i64)
       (drop (call $gen_keypair (i32.const 0) (i32.const 64)))
       (drop (call $sign
               (i32.const 0) (i32.const 64) (i32.const 2)
               (i32.const 128) (i32.const 64)))
       (i64.extend_i32_s
         (call $verify
           (i32.const 32)  ;; pub (second half of gen_keypair)
           (i32.const 32)
           (i32.const 64)  ;; msg
           (i32.const 2)
           (i32.const 128) ;; sig
           (i32.const 64)))))")

(def ^:private log-read-wat
  "Read log store into buffer; empty default store returns 0."
  "(module
     (import \"kotoba\" \"log_read\" (func $log_read (param i32 i32) (result i32)))
     (memory (export \"memory\") 1)
     (func (export \"main\") (result i64)
       (i64.extend_i32_s (call $log_read (i32.const 0) (i32.const 256)))))")

(def ^:private cbor-encode-wat
  "Pure allowlist host import: encode empty map-ish bytes (T8.4 expand)."
  "(module
     (import \"kotoba\" \"cbor_encode\" (func $cbor_encode (param i32 i32 i32 i32) (result i32)))
     (memory (export \"memory\") 1)
     (data (i32.const 0) \"{}\")
     (func (export \"main\") (result i64)
       (i64.extend_i32_s
         (call $cbor_encode (i32.const 0) (i32.const 2) (i32.const 64) (i32.const 256)))))")

(def ^:private json-encode-wat
  "Pure allowlist host import: encode \"{}\" (T8.4 expand)."
  "(module
     (import \"kotoba\" \"json_encode\" (func $json_encode (param i32 i32 i32 i32) (result i32)))
     (memory (export \"memory\") 1)
     (data (i32.const 0) \"{}\")
     (func (export \"main\") (result i64)
       (i64.extend_i32_s
         (call $json_encode (i32.const 0) (i32.const 2) (i32.const 64) (i32.const 256)))))")

(def jvm-live-corpus
  "Host-parity case ids that this runner can live-prove on JVM tender.
  Ids match lang/host-parity.edn :conformance :cases where listed;
  pure allowlist host imports (cbor/json) are T8.4 expand extras."
  [{:id :sha256-hex-all-available
    :import :sha256-hex
    :imports [:sha256-hex]
    :host :jvm
    :wat sha256-hex-wat
    :check (fn [n] (= 64 n))}
   {:id :clock-monotonic-all
    :import :clock-monotonic
    :imports [:clock-monotonic]
    :host :jvm
    :wat clock-monotonic-wat
    :check (fn [n] (pos? n))}
   {:id :log-write-all-available
    :import :log-write
    :imports [:log-write]
    :host :jvm
    :wat log-write-wat
    :check (fn [n] (not (neg? n)))}
   {:id :log-read-all-available
    :import :log-read
    :imports [:log-read]
    :host :jvm
    :wat log-read-wat
    :check (fn [n] (not (neg? n)))}
   {:id :gen-keypair-all-available
    :import :gen-keypair
    :imports [:gen-keypair]
    :host :jvm
    :wat gen-keypair-wat
    :check (fn [n] (= 64 n))}
   {:id :sign-all-available
    :import :sign
    :imports [:gen-keypair :sign]
    :host :jvm
    :wat sign-wat
    :check (fn [n] (= 64 n))}
   {:id :verify-all-available
    :import :verify
    :imports [:gen-keypair :sign :verify]
    :host :jvm
    :wat verify-wat
    :check (fn [n] (= 1 n))}
   {:id :cbor-encode-jvm-live
    :import :cbor-encode
    :imports [:cbor-encode]
    :host :jvm
    :wat cbor-encode-wat
    :check (fn [n] (pos? n))}
   {:id :json-encode-jvm-live
    :import :json-encode
    :imports [:json-encode]
    :host :jvm
    :wat json-encode-wat
    :check (fn [n] (pos? n))}])

(defn prove-import
  "Live-run one corpus entry on JVM tender. Returns
  {:ok? bool :id ... :import ... :result ... :error? ...}."
  [{:keys [id import imports wat check] :as entry}]
  (try
    (let [req (or imports [import])
          wasm (wat->wasm wat)
          caps (contract/host-caps
                {:grants (set req)
                 :limits {:allow-write-imports? true
                          :allow-secret-imports? true
                          :max-log-write-bytes 1024
                          :max-log-read-bytes 1024}})
          result (tender/run-main wasm req caps)
          ok? (boolean (and (number? result) (check result)))]
      {:ok? ok?
       :id id
       :import import
       :imports req
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
     :note "T8.4: JVM tender live proofs (crypto/clock/log/cbor/json)."}))

(defn run-node-live
  "Shell out to web/verify-host-parity-live.mjs (Node WebAssembly + actor-host).
  Returns map with :ok? / :total / :passed / :failed / :results, or
  {:ok? false :skipped? true ...} when node/wasm-tools unavailable."
  []
  (let [script (io/file "web/verify-host-parity-live.mjs")]
    (if-not (.exists script)
      {:ok? false :skipped? true :host :node
       :error "web/verify-host-parity-live.mjs not found (run from kototama root)"}
      (let [{:keys [exit out err]}
            (shell/sh "node" (.getPath script)
                      :dir (.getAbsolutePath (io/file ".")))]
        (if-let [line (->> (str/split-lines (str out))
                           (filter #(str/starts-with? % "HOST_PARITY_LIVE_JSON:"))
                           first)]
          (let [json-str (subs line (count "HOST_PARITY_LIVE_JSON:"))
                m (json/read-str json-str :key-fn keyword)
                failed (vec (or (:failed m) []))]
            {:ok? (and (zero? exit) (boolean (:ok m)))
             :host :node
             :total (or (:total m) 0)
             :passed (or (:passed m) 0)
             :failed failed
             :results (vec (or (:results m) []))
             :case-ids (mapv keyword (or (:case_ids m) []))
             :source (:wasm_webcomponent_source m)
             :note (:note m)
             :stderr (when-not (str/blank? (str err)) (str/trim err))})
          {:ok? false
           :host :node
           :error (str "node runner failed exit=" exit
                       " out=" (str/trim (str out))
                       " err=" (str/trim (str err)))})))))

(defn report
  "Compact snapshot for doctor/CLI (JVM + optional Node live)."
  ([] (report {:node? true}))
  ([{:keys [node?] :or {node? true}}]
   (let [jvm (run-jvm-live)
         node (when node? (run-node-live))]
     {:t84-slice :jvm-and-node-live
      :jvm jvm
      :node node
      :ok? (and (:ok? jvm) (or (nil? node) (:ok? node) (:skipped? node)))
      :browser-runner "web/verify-actor-host.mjs"
      :node-runner "web/verify-host-parity-live.mjs"
      :matrix-runner "kotoba.lang.host-parity/run-conformance (pure data)"})))
