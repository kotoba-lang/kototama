(ns kototama.browser-test
  (:require [clojure.test :refer [deftest is testing]]
            [kototama.browser :as browser]
            [kototama.contract :as contract]))

(deftest matrix-covers-full-import-surface
  (let [ids (set (map :import/id (:abi/imports contract/import-surface)))
        mids (set (map :import (browser/parity-matrix)))]
    (is (= ids mids))))

(deftest browser-yes-includes-crypto-log-and-http-path
  (let [yes (set (browser/browser-available-ids))]
    (is (contains? yes :sha256-hex))
    (is (contains? yes :gen-keypair))
    (is (contains? yes :sign))
    (is (contains? yes :clock-monotonic))
    (is (contains? yes :http-post) "http-post linkable via inject/COOP")
    (is (not (contains? yes :llm-infer)))))

(deftest parity-score-ratio
  (let [s (browser/parity-score)]
    (is (= 9 (:total s)))
    (is (= 8 (:browser-yes s)))
    (is (= 1 (:browser-no s)))
    (is (< 0.85 (:ratio s) 0.95))))

(deftest r2-report-shape
  (let [r (browser/r2-report)]
    (is (= :r2 (:level r)))
    (is (= :advanced-partial (:status r)))
    (is (seq (:verify r)))))
