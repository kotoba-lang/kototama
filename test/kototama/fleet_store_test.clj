(ns kototama.fleet-store-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kototama.fleet :as fleet]
            [kototama.fleet-store :as store]
            [kototama.fleet-exec :as exec]))

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
