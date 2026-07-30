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
            [kototama.tender :as tender]
            [kototama.transport-provider :as transport]))

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


(def ^:private random-bytes-wat
  "Fill 32 random bytes; returns 32 on success."
  "(module
     (import \"kotoba\" \"random_bytes\" (func $random_bytes (param i32 i32) (result i32)))
     (memory (export \"memory\") 1)
     (func (export \"main\") (result i64)
       (i64.extend_i32_s (call $random_bytes (i32.const 0) (i32.const 32)))))")

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

(defn- http-post-wat
  "http_post guest; URL baked into data segment (ASCII, no quotes)."
  [url]
  (str "(module
          (import \"kotoba\" \"http_post\" (func $http_post (param i32 i32 i32 i32 i32 i32) (result i32)))
          (memory (export \"memory\") 1)
          (data (i32.const 0) \"" url "\")
          (data (i32.const 50) \"body\")
          (func (export \"main\") (result i64)
            (i64.extend_i32_s
              (call $http_post (i32.const 0) (i32.const " (count url) ")
                               (i32.const 50) (i32.const 4)
                               (i32.const 200) (i32.const 256)))))"))

(def ^:private llm-infer-wat
  "(module
     (import \"kotoba\" \"llm_infer\" (func $llm_infer (param i32 i32 i32 i32) (result i32)))
     (memory (export \"memory\") 1)
     (data (i32.const 0) \"hi\")
     (func (export \"main\") (result i64)
       (i64.extend_i32_s
         (call $llm_infer (i32.const 0) (i32.const 2) (i32.const 100) (i32.const 64)))))")

(defn- prove-http-post-jvm
  "Live-prove :http-post links and SSRF gate runs (loopback fail-closed -1).
  Completing a real public POST is not required for host-parity availability."
  []
  (let [url "http://127.0.0.1:9/"
        wasm (wat->wasm (http-post-wat url))
        caps (contract/host-caps {:grants [:http-post]
                                  :limits {:max-http-posts 1}})
        started (System/currentTimeMillis)
        n (tender/run-main wasm [:http-post] caps)
        elapsed (- (System/currentTimeMillis) started)]
    {:ok? (and (= -1 n) (< elapsed 2000))
     :id :http-post-jvm-available
     :import :http-post
     :imports [:http-post]
     :host :jvm
     :result n
     :elapsed-ms elapsed
     :live? true
     :note "fail-closed loopback proves host linked + SSRF gate"}))

(defn- prove-llm-infer-jvm
  "Live-prove :llm-infer with injected :infer-fn (no network)."
  []
  (let [wasm (wat->wasm llm-infer-wat)
        caps (contract/host-caps {:grants [:llm-infer]
                                  :limits {:max-llm-infers 1}})
        n (tender/run-main wasm [:llm-infer] caps
                           {:llm-client {:infer-fn (fn [_] "pong")}})]
    {:ok? (= 4 n) ;; "pong" length
     :id :llm-infer-jvm-available
     :import :llm-infer
     :imports [:llm-infer]
     :host :jvm
     :result n
     :live? true
     :note "injected infer-fn — no Anthropic network"}))

(defn- transport-connect-wat
  "transport_connect guest; host ASCII baked into data segment."
  [host port]
  (str "(module
          (import \"kotoba\" \"transport_connect\" (func $tc (param i32 i32 i32) (result i64)))
          (memory (export \"memory\") 1)
          (data (i32.const 0) \"" host "\")
          (func (export \"main\") (result i64)
            (call $tc (i32.const 0) (i32.const " (count host) ")
                      (i32.const " port "))))"))

(defn- prove-transport-connect-jvm
  "Live-prove :transport-connect via inject (provider-host-functions).
  Empty endpoint allowlist fails closed with handle 0 — no real socket."
  []
  (let [host "example.com"
        port 443
        wasm (wat->wasm (transport-connect-wat host port))
        caps {:grants #{:transport-connect}
              :limits {:max-transport-connections 1
                       :max-transport-connect-ms 100
                       :max-transport-read-ms 100
                       :max-transport-read-bytes 1024
                       :max-transport-write-bytes 1024
                       ;; set but empty → endpoint-allowed? false for all
                       :transport-endpoint-allowlist #{}}}
        provider (transport/native-provider caps {})
        started (System/currentTimeMillis)]
    (try
      (let [n (tender/run-main wasm [:transport-connect] caps
                               {:provider-host-functions
                                (:host-functions provider)})
            elapsed (- (System/currentTimeMillis) started)]
        {:ok? (and (zero? n) (< elapsed 2000))
         :id :transport-connect-jvm-inject-available
         :import :transport-connect
         :imports [:transport-connect]
         :host :jvm
         :result n
         :elapsed-ms elapsed
         :live? true
         :note "inject transport-provider; empty allowlist fail-closed (0)"})
      (finally
        ((:close! provider))))))

(defn- kagi-sign-wat
  "kagi_sign guest; key-ref and message baked into data segments."
  [key-ref msg]
  (str "(module
          (import \"kotoba\" \"kagi_sign\" (func $ks (param i32 i32 i32 i32 i32 i32) (result i32)))
          (memory (export \"memory\") 1)
          (data (i32.const 0) \"" key-ref "\")
          (data (i32.const 64) \"" msg "\")
          (func (export \"main\") (result i64)
            (i64.extend_i32_s
              (call $ks (i32.const 0) (i32.const " (count key-ref) ")
                        (i32.const 64) (i32.const " (count msg) ")
                        (i32.const 128) (i32.const 64)))))"))

(defn- prove-kagi-sign-jvm
  "Live-prove :kagi-sign with injected signer + grant decision (no real kagi)."
  []
  (let [key-ref "kagi://live/test"
        msg "ok"
        wasm (wat->wasm (kagi-sign-wat key-ref msg))
        seed (byte-array 32)
        _ (dotimes [i 32] (aset-byte seed i (unchecked-byte (inc i))))
        signer (fn [ref purpose message]
                 (when-not (and (= key-ref ref) (= :live-test purpose))
                   (throw (ex-info "unexpected kagi ref/purpose"
                                   {:ref ref :purpose purpose})))
                 ;; 64-byte deterministic stand-in signature (not Ed25519 verify)
                 (byte-array (concat (take 32 message)
                                     (repeat (- 64 (min 32 (count message))) (byte 7)))))
        decisions [{:decision :grant :capability :kagi/sign
                    :secret-ref key-ref :purpose :live-test}]
        caps (contract/host-caps
              {:grants #{:kagi-sign}
               :limits {:max-kagi-signs 1
                        :allow-secret-imports? true}})
        n (tender/run-main wasm [:kagi-sign] caps
                           {:kagi-signer signer
                            :kagi-decisions decisions})]
    {:ok? (= 64 n)
     :id :kagi-sign-jvm-available
     :import :kagi-sign
     :imports [:kagi-sign]
     :host :jvm
     :result n
     :live? true
     :note "injected kagi-signer + grant decision — no Keychain/OS kagi"}))

(def jvm-live-corpus
  "Host-parity case ids that this runner can live-prove on JVM tender.
  Ids match lang/host-parity.edn :conformance :cases where listed;
  pure allowlist host imports (cbor/json) are T8.4 expand extras.
  Entries may use :prove (0-arity) instead of :wat/:check."
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
   {:id :random-bytes-all-available
    :import :random-bytes
    :imports [:random-bytes]
    :host :jvm
    :wat random-bytes-wat
    :check (fn [n] (= 32 n))}

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
    :check (fn [n] (pos? n))}
   {:id :http-post-jvm-available
    :import :http-post
    :prove prove-http-post-jvm}
   {:id :llm-infer-jvm-available
    :import :llm-infer
    :prove prove-llm-infer-jvm}
   {:id :transport-connect-jvm-inject-available
    :import :transport-connect
    :prove prove-transport-connect-jvm}
   {:id :kagi-sign-jvm-available
    :import :kagi-sign
    :prove prove-kagi-sign-jvm}])

(defn prove-import
  "Live-run one corpus entry on JVM tender. Returns
  {:ok? bool :id ... :import ... :result ... :error? ...}."
  [{:keys [id import imports wat check prove] :as entry}]
  (if prove
    (try
      (prove)
      (catch Exception e
        {:ok? false
         :id id
         :import import
         :host :jvm
         :live? true
         :error (.getMessage e)
         :error-class (.getName (class e))}))
    (try
      (let [req (or imports [import])
            wasm (wat->wasm wat)
            caps (contract/host-caps
                  {:grants (set req)
                   :limits {:allow-write-imports? true
                            :allow-secret-imports? true
                            :max-log-write-bytes 1024
                            :max-log-read-bytes 1024
                            :max-random-bytes 4096}})
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
         :error-class (.getName (class e))}))))

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
     :note "T8.4: JVM tender live proofs (crypto/clock/log/cbor/json/http/llm/transport/kagi)."}))

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
