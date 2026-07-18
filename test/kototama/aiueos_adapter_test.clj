(ns kototama.aiueos-adapter-test
  "The 'aiueos decides, kototama enforces' loop, closed for real:
  kototama.aiueos-adapter calls aiueos's OWN decision code (`aiueos.cli/
  command-result`, real, not test-hardcoded) and the resulting HostCaps
  actually gates a real Chicory-hosted WASM guest through
  kototama.tender -- proving the ADR-2607062330 follow-up end to end, not
  just that the adapter's translation logic is internally consistent."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]
            [kototama.aiueos-adapter :as adapter]
            [kototama.kagi-adapter :as kagi-adapter]
            [kototama.tender :as tender]))

(defn- wat->wasm
  "Same `wasm-tools parse` helper `tender_test.clj` uses -- duplicated
  rather than shared since it's `defn-` there and trivial either way."
  [wat]
  (let [in (java.io.File/createTempFile "aiueos-adapter-test" ".wat")
        out (java.io.File/createTempFile "aiueos-adapter-test" ".wasm")]
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

(def clock-monotonic-wat
  "(module
     (import \"kotoba\" \"clock_monotonic\" (func $cm (result i64)))
     (func (export \"main\") (result i64) (call $cm)))")

(def kagi-sign-wat
  "(module
     (import \"kotoba\" \"kagi_sign\" (func $s (param i32 i32 i32 i32 i32 i32) (result i32)))
     (memory (export \"memory\") 1)
     (data (i32.const 0) \"kagi://ops/key\")
     (data (i32.const 32) \"hello\")
     (func (export \"main\") (result i64)
       (i64.extend_i32_s (call $s (i32.const 0) (i32.const 14)
                                    (i32.const 32) (i32.const 5)
                                    (i32.const 100) (i32.const 64)))))")

(deftest manifest-for-imports-shapes-a-real-aiueos-manifest
  (is (= #:aiueos{:component :kototama/guest :kind :service :trust :verified
                  :imports #{:log/write :clock/monotonic} :exports #{}}
         (adapter/manifest-for-imports #{:log-write :clock-monotonic}))))

(deftest aiueos-grants-its-own-default-kernel-capabilities-with-no-overlay
  (testing "a real aiueos.cli/command-result :verify call, not a test-hardcoded decision"
    (let [decision (adapter/decide (adapter/manifest-for-imports #{:log-write :clock-monotonic}))]
      (is (= :grant (:aiueos/decision decision)))
      (is (= #{:log/write :clock/monotonic} (:aiueos/capabilities decision)))
      (is (seq (:aiueos.broker/audit-entries decision))
          "aiueos's own broker really audited this grant"))))

(deftest aiueos-denies-an-unsigned-component-under-a-require-signed-policy
  (testing "a real denial, not just the grant path"
    (let [decision (adapter/decide (adapter/manifest-for-imports #{:log-write})
                                   {:aiueos/require-signed true})]
      (is (= :deny (:aiueos/decision decision)))
      (is (= :bad-signature (:aiueos/kind (first (:aiueos/violations decision))))))))

(deftest granted-host-caps-actually-run-a-real-wasm-guest-through-kototama-tender
  (testing "the full loop: aiueos decides for real -> kototama.tender enforces for real"
    (let [{:keys [host-caps decision]} (adapter/host-caps-for-imports #{:clock-monotonic})
          wasm (wat->wasm clock-monotonic-wat)]
      (is (= :grant (:aiueos/decision decision)))
      (is (= #{:clock-monotonic} (:grants host-caps)))
      (is (pos? (tender/run-main wasm [:clock-monotonic] host-caps))
          "a real Chicory-hosted guest actually ran under HostCaps built from aiueos's real decision"))))

(deftest q4-q5-session-binds-only-broker-grants-and-receipts-every-host-call
  (let [{:keys [host-caps decision]} (adapter/host-caps-for-imports #{:clock-monotonic})
        wasm (wat->wasm clock-monotonic-wat)
        session (tender/open-session wasm [:clock-monotonic] host-caps)
        result (tender/call-main (:instance session))]
    (is (= :grant (:aiueos/decision decision)))
    (is (pos? result))
    (is (= #{:clock-monotonic} (set (:requested session))))
    (is (= 1 (count @(:receipts session))))
    (is (= {:receipt/import :clock-monotonic :receipt/outcome :ok}
           (select-keys (first @(:receipts session))
                        [:receipt/import :receipt/outcome])))))

(deftest denied-host-caps-actually-reject-the-same-guest-pre-flight
  (testing "the SAME shape of guest, denied this time, never even gets an Instance built"
    (let [{:keys [host-caps decision]} (adapter/host-caps-for-imports
                                        #{:clock-monotonic}
                                        {:policy-overlay {:aiueos/require-signed true}})
          wasm (wat->wasm clock-monotonic-wat)]
      (is (= :deny (:aiueos/decision decision)))
      (is (= #{} (:grants host-caps)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"rejected by contract"
                            (tender/instantiate wasm [:clock-monotonic] host-caps))
          "no test-hardcoded HostCaps here -- aiueos's real denial is what kototama.tender's pre-flight actually rejects"))))

(deftest aiueos-kagi-sign-decision-is-threaded-into-tender
  (let [request {:component :kototama/guest :key-ref "kagi://ops/key"
                 :purpose :artifact-signing}
        denied (adapter/kagi-sign-context request)
        allowed (adapter/kagi-sign-context
                 (assoc request :policy-overlay
                        {:aiueos/kagi-grants
                         {:kototama/guest #{[:kagi/sign :artifact-signing]}}}))
        signer (kagi-adapter/signer
                (fn [_ _ _] (.getBytes "sig!" "UTF-8")))
        wasm (wat->wasm kagi-sign-wat)]
    (is (= :deny (get-in denied [:decision :aiueos/decision]))
        "the adapter never self-grants a requested key")
    (is (= :grant (get-in allowed [:decision :aiueos/decision])))
    (is (= :kagi/sign (get-in allowed [:kagi-decisions 0 :capability])))
    (is (= 4 (tender/run-main wasm [:kagi-sign] (:host-caps allowed)
                              {:kagi-signer signer
                               :kagi-decisions (:kagi-decisions allowed)})))))
