(ns kototama.linear-journal-test
  (:require [clojure.string]
            [clojure.test :refer [deftest is testing]]
            [kototama.linear-journal :as journal]
            [multiformats.core])
  (:import [java.nio.file Files Path]))

(defn- temp-path [prefix]
  (.toString (Files/createTempFile prefix ".edn"
                                   (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest concurrent-claims-are-linear
  (let [path (temp-path "linear-authority-")
        claims (doall (map deref
                           (repeatedly 32
                                       #(future (journal/claim!
                                                 (journal/open! path)
                                                 "lease-1" :clock/now 3)))))]
    (is (= 3 (count (filter true? claims))))
    (is (= 3 (journal/consumed (journal/open! path) "lease-1" :clock/now)))
    (Files/deleteIfExists (Path/of path (make-array String 0)))))

(deftest recovered-journal-never-reissues-consumed-authority
  (let [path (temp-path "linear-recovery-")
        before (journal/open! path)]
    (is (journal/claim! before "lease-crash" :refund/execute 1))
    (let [after (journal/open! path)]
      (is (false? (journal/claim! after "lease-crash" :refund/execute 1)))
      (is (= 1 (journal/consumed after "lease-crash" :refund/execute))))
    (Files/deleteIfExists (Path/of path (make-array String 0)))))

(deftest multiple-capabilities-have-independent-linear-budgets
  (let [path (temp-path "linear-multi-cap-")
        journal (journal/open! path)]
    (is (journal/claim! journal "lease-multi" :customer/read 1))
    (is (journal/claim! journal "lease-multi" :refund/execute 1))
    (is (false? (journal/claim! journal "lease-multi" :customer/read 1)))
    (is (false? (journal/claim! journal "lease-multi" :refund/execute 1)))
    (is (= 1 (journal/consumed journal "lease-multi" :customer/read)))
    (is (= 1 (journal/consumed journal "lease-multi" :refund/execute)))
    (Files/deleteIfExists (Path/of path (make-array String 0)))))

;; ── the entries are values, and they are linked ─────────────────────────────
;; Root ADR-2608160200. What a chain buys over an append log is not
;; idempotence — the lock and the fsync still own that — it is that a
;; consumption can be CITED by CID, and that an edit in the middle stops
;; being invisible.

(defn- read-lines [path]
  (->> (clojure.string/split (slurp path) #"\n") (remove empty?) vec))

(defn- write-lines! [path lines]
  (spit path (str (clojure.string/join "\n" lines) "\n")))

(deftest each-entry-names-the-one-before-it
  (let [path (temp-path "linear-chain-")
        j (journal/open! path)]
    (dotimes [_ 3] (is (journal/claim! j "lease-chain" :clock/now 3)))
    (let [es (journal/entries j)]
      (is (= 3 (count es)))
      (is (nil? (:prev (first es))) "genesis has nothing to point at")
      (is (= (journal/entry-cid (first es)) (:prev (second es))))
      (is (= (journal/entry-cid (second es)) (:prev (nth es 2))))
      (is (apply distinct? (map journal/entry-cid es))
          "three consumptions are three different facts")
      (is (:ok? (journal/verify-chain j))))
    (Files/deleteIfExists (Path/of path (make-array String 0)))))

(deftest a-cid-is-over-the-bytes-that-are-on-disk
  (let [path (temp-path "linear-bytes-")
        j (journal/open! path)]
    (is (journal/claim! j "lease-bytes" :clock/now 1))
    (let [line (first (read-lines path))
          entry (first (journal/entries j))]
      (is (= line (journal/canonical-line entry))
          "the addressed form and the stored form are the same string")
      (is (= (journal/entry-cid entry)
             (multiformats.core/cidv1-raw
              (.getBytes ^String line java.nio.charset.StandardCharsets/UTF_8)))
          "so anyone can recompute it from the file alone"))
    (Files/deleteIfExists (Path/of path (make-array String 0)))))

(deftest an-edited-middle-entry-is-found
  (let [path (temp-path "linear-tamper-")
        j (journal/open! path)]
    (dotimes [_ 3] (journal/claim! j "lease-tamper" :refund/execute 3))
    (is (:ok? (journal/verify-chain j)))
    (let [lines (read-lines path)
          forged (clojure.string/replace (nth lines 1) ":ordinal 2" ":ordinal 9")]
      (is (not= forged (nth lines 1)) "the fixture really changed a line")
      (write-lines! path (assoc lines 1 forged)))
    (let [{:keys [ok? broken-at]} (journal/verify-chain j)]
      (is (false? ok?) "an append log cannot see this; the chain can")
      (is (= 2 broken-at)
          "the break shows at the entry whose :prev no longer matches"))
    (Files/deleteIfExists (Path/of path (make-array String 0)))))

(deftest a-removed-entry-is-found
  (let [path (temp-path "linear-truncate-")
        j (journal/open! path)]
    (dotimes [_ 3] (journal/claim! j "lease-cut" :refund/execute 3))
    (let [lines (read-lines path)]
      (write-lines! path [(nth lines 0) (nth lines 2)]))
    (is (false? (:ok? (journal/verify-chain j))))
    (Files/deleteIfExists (Path/of path (make-array String 0)))))

(deftest a-journal-written-before-the-chain-is-not-corruption
  (let [path (temp-path "linear-legacy-")]
    ;; exactly what the old writer produced: no :prev
    (write-lines! path [(pr-str {:op :consume :lease-id "old" :import :clock/now :ordinal 1})
                        (pr-str {:op :consume :lease-id "old" :import :clock/now :ordinal 2})])
    (let [j (journal/open! path)]
      (is (= 2 (journal/consumed j "old" :clock/now)) "still counted for the budget")
      (is (journal/claim! j "old" :clock/now 3) "and still extendable")
      (let [{:keys [ok? unchained count]} (journal/verify-chain j)]
        (is (true? ok?) "a journal that predates the chain is not a broken chain")
        (is (= 2 unchained) "and says how much of it is unlinked")
        (is (= 3 count))))
    (Files/deleteIfExists (Path/of path (make-array String 0)))))

(deftest dropping-prev-from-a-chained-entry-is-not-accepted-as-legacy
  (testing "otherwise the escape hatch for old journals would be the forgery route"
    (let [path (temp-path "linear-strip-")
          j (journal/open! path)]
      (dotimes [_ 3] (journal/claim! j "lease-strip" :refund/execute 3))
      (let [lines (read-lines path)
            stripped (clojure.string/replace (nth lines 2) #", :prev \"[^\"]+\"" "")]
        (is (not= stripped (nth lines 2)) "the fixture really removed :prev")
        (write-lines! path (assoc lines 2 stripped)))
      (is (false? (:ok? (journal/verify-chain j))))
      (Files/deleteIfExists (Path/of path (make-array String 0))))))
