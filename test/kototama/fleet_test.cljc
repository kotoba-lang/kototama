(ns kototama.fleet-test
  (:require [clojure.test :refer [deftest is testing]]
            [kototama.fleet :as fleet]))

(deftest lease-lifecycle
  (binding [fleet/*now-ms* 1000]
    (let [lease (fleet/make-lease "t1" "g1" :ttl-ms 5000 :budget {:fuel 1000 :ticks 2})]
      (is (= :active (:kototama.fleet/status lease)))
      (is (false? (fleet/lease-expired? lease 1000)))
      (is (true? (fleet/lease-expired? lease 7000)))
      (let [renewed (binding [fleet/*now-ms* 2000]
                      (fleet/renew-lease lease 10000))]
        (is (= :active (:kototama.fleet/status renewed)))
        (is (= 12000 (:kototama.fleet/expires-at renewed)))))))

(deftest budget-charge-and-exhaust
  (let [b (fleet/budget {:fuel 100 :ticks 2 :llm-infers 1 :http-posts 0})
        b1 (fleet/charge-budget b {:fuel 40 :ticks 1})
        b2 (fleet/charge-budget b1 {:fuel 60 :ticks 1})]
    (is (true? (fleet/budget-remaining? b1)))
    (is (false? (fleet/budget-remaining? b2)))
    (is (zero? (:fuel b2)))
    (is (zero? (:ticks b2)))))

(deftest governor-denies-expired
  (binding [fleet/*now-ms* 0]
    (let [lease (fleet/make-lease "t" "g" :ttl-ms 1)
          gov (fleet/governor-allow? lease {:now 10})]
      (is (false? (:allow? gov)))
      (is (= :lease-expired (:reason gov))))))

(deftest governor-denies-tenant
  (binding [fleet/*now-ms* 0]
    (let [lease (fleet/make-lease "bad" "g" :ttl-ms 99999)
          gov (fleet/governor-allow? lease {:deny-tenants #{"bad"}})]
      (is (false? (:allow? gov)))
      (is (= :tenant-denied (:reason gov))))))

(deftest run-loop-step-with-inject-execute
  (binding [fleet/*now-ms* 100]
    (let [lease (fleet/make-lease "t1" "fact"
                                  :ttl-ms 60000
                                  :budget {:fuel 10000 :ticks 2})
          reg (fleet/register-lease (fleet/empty-registry) lease)
          id (:kototama.fleet/lease-id lease)
          exec (fn [tick]
                 (is (= "t1" (:kototama.fleet/tenant tick)))
                 {:ok? true :result 120 :fuel-used 59 :limits {}})
          s1 (fleet/run-loop-step reg id exec)
          s2 (fleet/run-loop-step (:registry s1) id exec)
          s3 (fleet/run-loop-step (:registry s2) id exec)]
      (is (true? (:ok? s1)))
      (is (true? (:ok? s2)))
      (is (false? (:ok? s3)) "ticks exhausted")
      (is (= :budget-exhausted
             (get-in s3 [:governor :reason]
                     (get-in s3 [:planned :reason])))))))

(deftest checkpoint-restore-roundtrip
  (binding [fleet/*now-ms* 50]
    (let [lease (fleet/make-lease "t9" "g9")
          reg (fleet/register-lease (fleet/empty-registry) lease)
          cp (fleet/checkpoint reg {:note "test"})
          reg' (fleet/restore cp)
          leases (fleet/tenant-leases reg' "t9")]
      (is (= 1 (:kototama.fleet/checkpoint-schema cp)))
      (is (= 1 (count leases)))
      (is (= "g9" (:kototama.fleet/guest (first leases)))))))

(deftest multi-tenant-isolation
  (binding [fleet/*now-ms* 1]
    (let [a (fleet/make-lease "alice" "g")
          b (fleet/make-lease "bob" "g")
          reg (-> (fleet/empty-registry)
                  (fleet/register-lease a)
                  (fleet/register-lease b))]
      (is (= 1 (count (fleet/tenant-leases reg "alice"))))
      (is (= 1 (count (fleet/tenant-leases reg "bob"))))
      (is (not= (:kototama.fleet/lease-id a)
                (:kototama.fleet/lease-id b))))))

(deftest r3-report-skeleton
  (let [r (fleet/r3-report)]
    (is (= :r3 (:level r)))
    (is (#{:skeleton :skeleton+persist :advanced-partial} (:status r)))
    (is (= :advanced-partial (:status r)))
    (is (seq (:landed r)))
    (is (seq (:not-yet r)))
    (is (some #(re-find #"fleet-gate|tick audit" %) (:landed r)))))
