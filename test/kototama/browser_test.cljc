(ns kototama.browser-test
  (:require [clojure.test :refer [deftest is testing]]
            [kototama.browser :as browser]
            [kototama.contract :as contract]))

(deftest matrix-covers-full-import-surface
  (let [ids (set (map :import/id (:abi/imports contract/import-surface)))
        mids (set (map :import (browser/parity-matrix)))]
    (is (= ids mids))))

(deftest browser-yes-includes-crypto-log-http-post-and-llm-infer
  (let [yes (set (browser/browser-available-ids))]
    (is (contains? yes :sha256-hex))
    (is (contains? yes :gen-keypair))
    (is (contains? yes :sign))
    (is (contains? yes :clock-monotonic))
    ;; Linkable as of wasm-webcomponent PR #8 (2026-07-16): a real
    ;; SharedArrayBuffer+Atomics.wait bridge, verified end-to-end in real
    ;; headless Chromium (test/browser/verify_http_post_browser.cljs
    ;; there), inside a cross-origin-isolated page with the guest
    ;; instantiated in a dedicated Worker.
    (is (contains? yes :http-post) "http-post is real via the Worker-hosted SAB+Atomics bridge")
    ;; Linkable as of wasm-webcomponent PR #11 (2026-07-16): reuses the SAME
    ;; bridge as http-post, through a caller-supplied proxy URL (see
    ;; test/browser/verify_llm_infer_browser.cljs there).
    (is (contains? yes :llm-infer) "llm-infer is real via the same Worker-hosted SAB+Atomics bridge, through a caller-supplied proxy URL")
    (is (contains? yes :http-fetch))
    (is (contains? yes :http-post-headers))
    (is (contains? yes :random-bytes) "bounded CSPRNG fill; deny-by-default via max-random-bytes 0")
    (is (= 19 (count yes)))))

(deftest every-import-declares-explicit-parity-and-gaps-carry-notes
  ;; ADR-0009 Decision 1 (docs/0009-stack-topology-parity-gate-capability-
  ;; schema.md): a change adding an actor:host import must declare that
  ;; import's runtime parity in host-impl in the SAME change -- either a
  ;; real browser wiring or an explicit :no with a :note (the recorded
  ;; waiver). matrix-covers-full-import-surface alone cannot catch an
  ;; undeclared import, because parity-matrix backfills a nil-status row
  ;; for any contract id missing from host-impl.
  (let [ids (map :import/id (:abi/imports contract/import-surface))
        allowed #{:yes :no :inject :coop-or-inject}]
    (doseq [id ids]
      (let [row (get browser/host-impl id)]
        (testing (str id " has an explicit host-impl row with allowed statuses")
          (is (map? row))
          (is (contains? allowed (:jvm row)))
          (is (contains? allowed (:browser row)))
          (is (contains? allowed (:node row))))
        (testing (str id " documents its browser gap when not linkable")
          (when (= :no (:browser row))
            (is (string? (:note row))
                "an unported import must carry a :note recording the gap")))))
    (testing "host-impl has no orphan rows outside the contract surface"
      (is (= (set ids) (set (keys browser/host-impl)))))))

(deftest parity-score-ratio
  (let [s (browser/parity-score)]
    ;; Browser-linkable stays at 19 (crypto/log/http/codec/llm/stream).
    ;; Transport/TLS + kagi-sign + pg-pool + pg-cancel + pg wire + scram-sha256
    ;; intentional browser :no (39). Total 58 after scram-sha256 host-impl row.
    (is (= 58 (:total s)))
    (is (= 19 (:browser-yes s)))
    (is (= 39 (:browser-no s)))
    (is (== (/ 19 58) (:ratio s)))))

(deftest node-inject-honesty-matches-actor-host
  "T8.4 / kotoba-lang#356: Node :inject only where wasm-webcomponent actor-host
  actually implements a provider. transport/tls/pg/scram are :no."
  (is (= :inject (get-in browser/host-impl [:kagi-sign :node])))
  (is (= :inject (get-in browser/host-impl [:http-post :node])))
  (is (= :inject (get-in browser/host-impl [:llm-infer :node])))
  (is (= :inject (get-in browser/host-impl [:http-fetch :node])))
  (is (= :no (get-in browser/host-impl [:transport-connect :node])))
  (is (= :no (get-in browser/host-impl [:tls-open :node])))
  (is (= :no (get-in browser/host-impl [:pg-open :node])))
  (is (= :no (get-in browser/host-impl [:scram-sha256 :node])))
  (is (= :no (get-in browser/host-impl [:pg-pool-open :node])))
  (is (= :yes (get-in browser/host-impl [:scram-sha256 :jvm]))))

(deftest r2-report-shape
  (let [r (browser/r2-report)]
    (is (= :r2 (:level r)))
    (is (= :qualified (:status r)))
    (is (seq (:verify r)))))

(deftest production-host-admission-fails-closed
  (testing "browser conditional imports require concrete runtime evidence"
    (let [denied (browser/host-admission
                  :browser [:http-post :llm-infer]
                  {:profile :production
                   :configured #{:cross-origin-isolated :worker :http-bridge}})]
      (is (false? (:ok? denied)))
      (is (= #{:llm-proxy} (-> denied :errors first :missing))))
    (is (:ok? (browser/host-admission
               :browser [:http-post :llm-infer]
               {:profile :production
                :configured #{:cross-origin-isolated :worker
                              :http-bridge :llm-proxy}}))))
  (testing "node injection is never treated as a production fallback"
    (is (false? (:ok? (browser/host-admission
                       :node [:http-post] {:profile :production}))))
    (is (:ok? (browser/host-admission
               :node [:http-post]
               {:profile :production
                :configured #{:http-post-provider}}))))
  (testing "unknown hosts and imports fail closed"
    (is (false? (:ok? (browser/host-admission
                       :invented [:sha256-hex] {:profile :production}))))
    (is (false? (:ok? (browser/host-admission
                       :jvm [:invented] {:profile :production}))))))
