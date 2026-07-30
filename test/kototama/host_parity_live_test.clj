(ns kototama.host-parity-live-test
  "T8.4 live host runner — JVM tender proofs for host-parity critical imports."
  (:require [clojure.test :refer [deftest is testing]]
            [kototama.host-parity-live :as live]))

(deftest jvm-live-corpus-covers-critical-ids
  (let [ids (set (map :id live/jvm-live-corpus))]
    (doseq [id [:sha256-hex-all-available
                :clock-monotonic-all
                :log-write-all-available
                :log-read-all-available
                :gen-keypair-all-available
                :sign-all-available
                :verify-all-available
                :cbor-encode-jvm-live
                :json-encode-jvm-live
                :random-bytes-all-available
                :http-post-jvm-available
                :llm-infer-jvm-available
                :transport-connect-jvm-inject-available]]
      (is (contains? ids id) (str id)))))

(deftest run-jvm-live-proves-all-corpus-entries
  (let [r (live/run-jvm-live)]
    (is (true? (:ok? r))
        (str "live failures: " (pr-str (:failed r))))
    (is (= 13 (:total r)))
    (is (= 13 (:passed r)))
    (is (empty? (:failed r)))
    (doseq [row (:results r)]
      (testing (str (:id row))
        (is (true? (:ok? row)))
        (is (true? (:live? row)))
        (is (= :jvm (:host row)))))))

(deftest report-shape
  (let [rep (live/report {:node? false})]
    (is (= :jvm-and-node-live (:t84-slice rep)))
    (is (true? (get-in rep [:jvm :ok?])))))

(deftest run-node-live-proves-host-parity-cases
  (let [r (live/run-node-live)]
    (is (true? (:ok? r))
        (str "node live failures: " (pr-str (or (:failed r) (:error r)))))
    (is (= 10 (:total r)))
    (is (= 10 (:passed r)))
    (is (empty? (:failed r)))
    (is (string? (:source r)))))
