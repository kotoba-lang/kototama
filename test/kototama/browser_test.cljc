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

(deftest parity-score-ratio
  (let [s (browser/parity-score)]
    (is (= 9 (:total s)))
    (is (= 9 (:browser-yes s)))
    (is (= 0 (:browser-no s)))
    (is (= 1.0 (:ratio s)))))

(deftest r2-report-shape
  (let [r (browser/r2-report)]
    (is (= :r2 (:level r)))
    (is (= :advanced-partial (:status r)))
    (is (seq (:verify r)))))
