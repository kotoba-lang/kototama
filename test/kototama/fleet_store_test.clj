(ns kototama.fleet-store-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kototama.fleet :as fleet]
            [kototama.fleet-store :as store]
            [kototama.fleet-exec :as exec]
            [kototama.guest :as guest]))

(deftest disk-checkpoint-roundtrip
  (let [dir (str "tmp/kototama-fleet-test-" (System/currentTimeMillis))
        s (store/disk-store dir)
        lease (fleet/make-lease "t" "g" :budget {:fuel 1000 :ticks 5})
        reg (fleet/register-lease (fleet/empty-registry) lease)
        {:keys [path key checkpoint]} (store/save-checkpoint! reg s {:key "t1"})
        reg' (store/load-checkpoint! key s)]
    (is (.exists (io/file path)))
    (is (= 1 (:kototama.fleet/checkpoint-schema checkpoint)))
    (is (= 1 (count (fleet/tenant-leases reg' "t"))))
    ;; cleanup
    (doseq [f (file-seq (io/file dir))]
      (when (.isFile f) (.delete f)))
    (.delete (io/file dir))))

(deftest memory-store-roundtrip
  (let [s (store/memory-store)
        reg (fleet/register-lease (fleet/empty-registry)
                                  (fleet/make-lease "a" "b"))
        _ (store/save-checkpoint! reg s {:key "k"})
        reg' (store/load-checkpoint! "k" s)]
    (is (= 1 (count (fleet/tenant-leases reg' "a"))))))

(deftest tender-execute-host-free-fact
  (let [wasm "kototama/fixtures/kotoba-compiled-fact.wasm"
        exec (exec/make-execute {:wasm wasm :grants []})
        lease (fleet/make-lease "demo" "fact"
                                :budget {:fuel 5000000 :ticks 2}
                                :grants [])
        reg (fleet/register-lease (fleet/empty-registry) lease)
        id (:kototama.fleet/lease-id lease)
        result (exec/run-lease! reg id {:wasm wasm :max-ticks 2 :execute exec})
        last (:last result)]
    (is (true? (get-in last [:result :ok?])))
    (is (= 120 (get-in last [:result :result])))
    (is (pos? (get-in last [:result :fuel-used])))))

(deftest bootstrap-and-run-persists
  (let [dir (str "tmp/fleet-boot-" (System/currentTimeMillis))
        s (store/disk-store dir)
        out (exec/bootstrap-and-run!
             "tenant-x" "fact"
             "kototama/fixtures/kotoba-compiled-fact.wasm"
             :store s
             :max-ticks 2
             :budget {:fuel 5000000 :ticks 5})]
    (is (string? (:checkpoint-path out)))
    (is (.exists (io/file (:checkpoint-path out))))
    (is (= 120 (get-in out [:last :result :result])))
    (let [reg' (store/load-checkpoint! (:checkpoint-key out) s)]
      (is (seq (fleet/tenant-leases reg' "tenant-x"))))
    (doseq [f (reverse (file-seq (io/file dir)))]
      (.delete f))))

(deftest resume-from-checkpoint-continues-lease
  (let [dir (str "tmp/fleet-resume-" (System/currentTimeMillis))
        s (store/disk-store dir)
        wasm "kototama/fixtures/kotoba-compiled-fact.wasm"
        boot (exec/bootstrap-and-run!
              "tenant-r" "fact" wasm
              :store s
              :max-ticks 1
              :budget {:fuel 5000000 :ticks 4})
        key (:checkpoint-key boot)
        keys (store/list-checkpoint-keys s)
        ;; list uses filesystem basenames (__ for /); load accepts original key
        _ (is (seq keys) (str "keys=" keys))
        _ (is (some? (store/load-checkpoint! key s)))
        resume (exec/resume-from-checkpoint! key
                                             :store s
                                             :wasm wasm
                                             :max-ticks 2)]
    (is (pos? (:active-before resume)))
    (is (true? (get-in (first (:resumes resume)) [:last :result :ok?])))
    (is (= 120 (get-in (first (:resumes resume)) [:last :result :result])))
    (is (true? (:resumed? (first (:resumes resume)))))
    (doseq [f (reverse (file-seq (io/file dir)))]
      (.delete f))))

(deftest recovery-pass-scans-disk
  (let [dir (str "tmp/fleet-recover-" (System/currentTimeMillis))
        s (store/disk-store dir)
        wasm "kototama/fixtures/kotoba-compiled-fact.wasm"
        _ (exec/bootstrap-and-run! "tenant-c" "fact" wasm
                                   :store s :max-ticks 1
                                   :budget {:fuel 5000000 :ticks 3})
        pass (exec/recovery-pass! :store s :wasm wasm :max-keys 5 :max-ticks 1)]
    (is (seq (:keys pass)))
    (is (pos? (:ok-count pass)))
    (doseq [f (reverse (file-seq (io/file dir)))]
      (.delete f))))

(deftest run-daemon-bounded-passes
  (let [sleeps (atom [])
        passes (atom 0)
        out (exec/run-daemon!
             :interval-ms 10
             :max-passes 3
             :sleep-fn (fn [ms] (swap! sleeps conj ms))
             :pass-fn (fn []
                        (swap! passes inc)
                        {:ok-count 1 :fail-count 0 :keys ["k"] :results []}))]
    (is (= 3 (:pass-count out)))
    (is (= :max-passes (:stopped out)))
    (is (= 3 @passes))
    (is (= 2 (count @sleeps)) "sleep between passes only, not after last")
    (is (= 3 (:ok-count out)))))

(deftest run-daemon-stop-predicate
  (let [out (exec/run-daemon!
             :interval-ms 1
             :max-passes 10
             :sleep-fn (fn [_])
             :pass-fn (fn [] {:ok-count 1 :fail-count 0})
             :stop? (fn [i _last] (>= i 2)))]
    (is (= :stop? (:stopped out)))
    (is (= 2 (:pass-count out)))))

(deftest resolve-grants-explicit
  (let [r (exec/resolve-grants [:log-write :clock-monotonic]
                               {:use-aiueos? false :grants [:log-write]})]
    (is (= :explicit (:source r)))
    (is (= [:log-write] (:grants r)))))

(deftest resolve-grants-aiueos-fail-closed-on-deny
  ;; Real aiueos deny path (same as aiueos-adapter-test require-signed),
  ;; not a trust-label heuristic — :untrusted alone may still GRANT under
  ;; default kernel caps. Fail-closed: empty grants for the translatable subset.
  (let [r (exec/resolve-grants [:log-write]
                               {:use-aiueos? true
                                :grants [:log-write]
                                :trust :verified
                                :policy-overlay {:aiueos/require-signed true}
                                :limits {:allow-write-imports? true}})]
    (is (= :aiueos (:source r)))
    (is (some? (:decision r)) "decision must come from aiueos adapter")
    (is (= :deny (get-in r [:decision :aiueos/decision]))
        (str "require-signed must deny unsigned guest; got " (:decision r)))
    (is (empty? (:grants r)) "fail-closed on deny — no invented grants")))

(deftest resolve-grants-aiueos-verified-grant
  ;; verified trust + default kernel caps → grant for log-write/clock
  (let [r (exec/resolve-grants [:log-write :clock-monotonic]
                               {:use-aiueos? true
                                :grants [:log-write :clock-monotonic]
                                :trust :verified
                                :limits {:allow-write-imports? true}})]
    (is (= :aiueos (:source r)))
    (is (= :grant (get-in r [:decision :aiueos/decision]))
        (str "expected grant under verified trust; got " (:decision r)))
    (is (= #{:log-write :clock-monotonic} (set (:grants r))))))

(deftest fenced-resume-skips-when-held-by-other
  "Shared disk store: node-a runs fact→120; node-b skipped; node-a renews→120."
  (let [dir (str "tmp/fleet-fence-hold-" (System/currentTimeMillis))
        s (store/disk-store dir)
        wasm "kototama/fixtures/kotoba-compiled-fact.wasm"
        boot (exec/bootstrap-and-run!
              "tenant-hold" "fact" wasm
              :store s
              :node-id "node-a"
              :max-ticks 1
              :budget {:fuel 5000000 :ticks 5}
              :fence? true)
        key (:checkpoint-key boot)
        ;; node-b tries same checkpoint without higher epoch → skip (no tender)
        resume (exec/resume-from-checkpoint! key
                                             :store s
                                             :wasm wasm
                                             :node-id "node-b"
                                             :max-ticks 1
                                             :fence? true
                                             :skip-if-held? true)]
    (is (true? (:fenced? boot)))
    (is (= 120 (get-in boot [:last :result :result]))
        "holding node must run real tender fact fixture")
    (is (pos? (get-in boot [:last :result :fuel-used])))
    (is (true? (:fenced? resume)))
    (is (pos? (:skipped-count resume)))
    (is (zero? (:ran-count resume)) "second node must not run tender")
    (is (true? (:skipped? (first (:resumes resume)))))
    (is (= :held-by-other (:skip-reason (first (:resumes resume)))))
    (is (nil? (get-in (first (:resumes resume)) [:last :result :result]))
        "skipped resume has no successful guest main result")
    ;; same owner renews and runs
    (let [renew (exec/resume-from-checkpoint! key
                                              :store s
                                              :wasm wasm
                                              :node-id "node-a"
                                              :max-ticks 1
                                              :fence? true)]
      (is (pos? (:ran-count renew)))
      (is (= 120 (get-in (first (:resumes renew)) [:last :result :result]))))
    (doseq [f (reverse (file-seq (io/file dir)))]
      (.delete f))))

(deftest bootstrap-fence-second-node-refused
  "Shared disk: first node runs fact→120; second bootstrap refuses without tender."
  (let [dir (str "tmp/fleet-fence-boot-" (System/currentTimeMillis))
        s (store/disk-store dir)
        wasm "kototama/fixtures/kotoba-compiled-fact.wasm"
        first (exec/bootstrap-and-run! "tenant-x" "same-guest" wasm
                                       :store s :node-id "node-a" :max-ticks 1
                                       :budget {:fuel 5000000 :ticks 3})
        ex (try
             (exec/bootstrap-and-run! "tenant-x" "same-guest" wasm
                                      :store s :node-id "node-b" :max-ticks 1
                                      :budget {:fuel 5000000 :ticks 3})
             nil
             (catch Exception e e))]
    (is (= 120 (get-in first [:last :result :result])))
    (is (some? ex))
    (is (= :held-by-other (:reason (ex-data ex))))
    (doseq [f (reverse (file-seq (io/file dir)))]
      (.delete f))))

(deftest tick-audit-written-on-run-lease
  (let [dir (str "tmp/fleet-audit-" (System/currentTimeMillis))
        s (store/disk-store dir)
        wasm "kototama/fixtures/kotoba-compiled-fact.wasm"
        boot (exec/bootstrap-and-run!
              "audit-t" "fact" wasm
              :store s :max-ticks 2
              :budget {:fuel 5000000 :ticks 5}
              :node-id "audit-node")
        audits (store/list-audit-keys s)]
    (is (= 120 (get-in boot [:last :result :result])))
    (is (seq audits) (str "expected audit keys, got " audits))
    (doseq [f (reverse (file-seq (io/file dir)))]
      (.delete f))))

(deftest r3-gate-passes-end-to-end
  (let [dir (str "tmp/r3-gate-test-" (System/currentTimeMillis))
        out (exec/run-r3-gate!
             :wasm "kototama/fixtures/kotoba-compiled-fact.wasm"
             :dir dir)]
    (is (true? (:ok? out)) (pr-str (remove :ok? (:checks out))))
    (is (= :advanced-partial (:status out)))
    (is (= 9 (:pass-count out)))
    (is (zero? (:fail-count out)))
    (is (= :advanced-partial (get-in guest/maturity-levels [:r3 :status])))
    (is (= :advanced-partial (:status (fleet/r3-report))))
    (doseq [f (reverse (file-seq (io/file dir)))]
      (.delete f))))
