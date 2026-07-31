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
                :kagi-sign-jvm-available
                :transport-connect-jvm-inject-available
                :tls-open-jvm-inject-available
                :transport-close-jvm-inject-available
                :transport-write-jvm-inject-available
                :transport-read-jvm-inject-available
                :transport-rw-jvm-loopback-success
                :tls-server-end-point-jvm-available
                :pg-pool-open-jvm-inject-available
                :pg-pool-acquire-jvm-inject-available
                :pg-pool-health-jvm-inject-available
                :pg-pool-close-jvm-inject-available
                :pg-pool-query-jvm-inject-available
                :pg-pool-release-jvm-inject-available
                :pg-pool-stats-jvm-inject-available
                :pg-pool-drain-jvm-inject-available
                :pg-cancel-register-jvm-inject-available
                :pg-cancel-jvm-inject-available
                :pg-open-jvm-inject-available
                :pg-query-jvm-inject-available
                :pg-simple-query-jvm-inject-available
                :pg-prepare-jvm-inject-available
                :pg-session-reset-jvm-inject-available
                :pg-close-statement-jvm-inject-available
                :http-fetch-jvm-available
                :pg-query-state-jvm-inject-available
                :pg-prepare-typed-jvm-inject-available
                :pg-execute-params2-jvm-inject-available
                :pg-execute-params-jvm-inject-available
                :pg-bind-portal-jvm-inject-available
                :pg-fetch-portal-jvm-inject-available
                :pg-close-portal-jvm-inject-available
                :pg-copy-out-jvm-inject-available
                :pg-copy-in-jvm-inject-available
                :pg-execute-batch-jvm-inject-available
                :pg-open-scram-jvm-inject-available
                :pg-open-scram-random-jvm-inject-available
                :pg-open-scram-cancellable-random-jvm-inject-available
                :pg-cancel-authority-use-jvm-inject-available
                :pg-close-scram-jvm-inject-available
                :scram-sha256-jvm-available
                :scram-sha256-jvm-deny-available
                :http-post-headers-jvm-available
                :json-extract-field-jvm-live]]
      (is (contains? ids id) (str id)))))

(deftest run-jvm-live-proves-all-corpus-entries
  (let [r (live/run-jvm-live)]
    (is (true? (:ok? r))
        (str "live failures: " (pr-str (:failed r))))
    (is (= 56 (:total r)))
    (is (= 56 (:passed r)))
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
    (is (= 21 (:total r)))
    (is (= 21 (:passed r)))
    (is (empty? (:failed r)))
    (is (string? (:source r)))))
