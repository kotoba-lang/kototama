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
    (is (contains? yes :llm-infer) "llm-infer is real via the same Worker-hosted SAB+Atomics bridge, through a caller-supplied proxy URL")))

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
    ;; ADR-2607230943's 4 new imports (http-fetch/cbor-encode/json-encode/
    ;; json-extract-field) plus this wave's http-post-headers are JVM-only
    ;; so far -- an honest gap, not yet ported to wasm-webcomponent's
    ;; actor-host.js -- so total grows to 14 while browser-yes stays at the
    ;; pre-existing 9.
    (is (= 14 (:total s)))
    (is (= 9 (:browser-yes s)))
    (is (= 5 (:browser-no s)))
    (is (= (/ 9.0 14) (:ratio s)))))

(deftest r2-report-shape
  (let [r (browser/r2-report)]
    (is (= :r2 (:level r)))
    (is (= :advanced-partial (:status r)))
    (is (seq (:verify r)))))
