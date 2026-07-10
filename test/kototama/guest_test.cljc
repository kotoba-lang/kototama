(ns kototama.guest-test
  (:require [clojure.test :refer [deftest is testing]]
            [kototama.guest :as guest]
            [kototama.contract :as contract]))

(deftest wasm-field-names-cover-import-surface
  (doseq [id (map :import/id (:abi/imports contract/import-surface))]
    (is (string? (guest/wasm-field-name id))
        (str "missing wasm field for " id))))

(deftest lint-rejects-defn-docstring
  (let [bad "(defn main \"docstring here is 54 chars padded........\" [] 1)"
        r (guest/lint-kotoba-source bad)]
    (is (false? (:ok? r)))
    (is (some #(= :emit/defn-docstring (:code %)) (:problems r)))))

(deftest lint-accepts-clean-main
  (let [ok "(defn add1 [x] (+ x 1))\n(defn main [] (add1 41))"
        r (guest/lint-kotoba-source ok)]
    (is (true? (:ok? r)) (pr-str r))
    (is (true? (:has-main? r)))))

(deftest lint-flags-missing-main
  (let [r (guest/lint-kotoba-source "(defn foo [] 1)")]
    (is (false? (:ok? r)))
    (is (some #(= :emit/missing-main (:code %)) (:problems r)))))

(deftest host-free-profile
  (let [p (guest/profile [])]
    (is (true? (:host-free? p)))
    (is (= :r1 (:maturity p)))
    (is (false? (:network? p)))))

(deftest network-profile
  (let [p (guest/profile [:http-post :llm-infer]
                         {:grants [:http-post :llm-infer]
                          :limits {:max-http-posts 1 :max-llm-infers 1}})]
    (is (false? (:host-free? p)))
    (is (true? (:network? p)))))

(deftest maturity-report-shape
  (let [m (guest/maturity-report)
        r3-note (get-in m [:levels :r3 :note] "")]
    (is (= :r3 (:current m)))
    (is (= :advanced-partial (get-in m [:levels :r3 :status])))
    (is (contains? (:levels m) :r1))
    (is (contains? (:levels m) :r2))
    (is (contains? (:levels m) :r3))
    (is (seq (:import-surface m)))
    ;; Criterion honesty: fence-gated multi-node is landed — do not claim
    ;; "not cross-node" while doctor surfaces this note.
    (is (not (re-find #"(?i)not cross-node" r3-note))
        (str "stale not-cross-node claim in r3 note: " r3-note))
    (is (re-find #"(?i)fence" r3-note)
        (str "r3 note should mention fence-gated tender: " r3-note))))
