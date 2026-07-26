(ns kototama.linear-journal-test
  (:require [clojure.test :refer [deftest is]]
            [kototama.linear-journal :as journal])
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
