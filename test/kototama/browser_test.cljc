(ns kototama.browser-test
  (:require [clojure.test :refer [deftest is testing]]
            [kototama.browser :as browser]
            [kototama.contract :as contract]))

(deftest matrix-covers-full-import-surface
  (let [ids (set (map :import/id (:abi/imports contract/import-surface)))
        mids (set (map :import (browser/parity-matrix)))]
    (is (= ids mids))))

(deftest browser-yes-includes-crypto-and-log
  (let [yes (set (browser/browser-available-ids))]
    (is (contains? yes :sha256-hex))
    (is (contains? yes :gen-keypair))
    (is (contains? yes :sign))
    (is (contains? yes :clock-monotonic))
    (is (not (contains? yes :http-post)))))

(deftest parity-score-ratio
  (let [s (browser/parity-score)]
    (is (= 9 (:total s)))
    (is (= 7 (:browser-yes s)))
    (is (= 2 (:browser-no s)))
    (is (< 0.7 (:ratio s) 0.8))))

(deftest r2-report-shape
  (let [r (browser/r2-report)]
    (is (= :r2 (:level r)))
    (is (= :advanced-partial (:status r)))
    (is (seq (:verify r)))))
