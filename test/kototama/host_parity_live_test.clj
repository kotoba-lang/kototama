(ns kototama.host-parity-live-test
  "T8.4 live host runner — JVM tender proofs for host-parity critical imports."
  (:require [clojure.test :refer [deftest is testing]]
            [kototama.host-parity-live :as live]))

(deftest jvm-live-corpus-covers-critical-ids
  (let [ids (set (map :id live/jvm-live-corpus))]
    (doseq [id [:sha256-hex-all-available
                :clock-monotonic-all
                :log-write-all-available
                :gen-keypair-all-available]]
      (is (contains? ids id) (str id)))))

(deftest run-jvm-live-proves-all-corpus-entries
  (let [r (live/run-jvm-live)]
    (is (true? (:ok? r))
        (str "live failures: " (pr-str (:failed r))))
    (is (= 4 (:total r)))
    (is (= 4 (:passed r)))
    (is (empty? (:failed r)))
    (doseq [row (:results r)]
      (testing (str (:id row))
        (is (true? (:ok? row)))
        (is (true? (:live? row)))
        (is (= :jvm (:host row)))))))

(deftest report-shape
  (let [rep (live/report)]
    (is (= :jvm-live-first (:t84-slice rep)))
    (is (true? (get-in rep [:live :ok?])))))
