(ns kototama.fleet-fence-test
  (:require [clojure.test :refer [deftest is testing]]
            [kototama.fleet :as fleet]
            [kototama.fleet-fence :as fence]))

(deftest higher-epoch-beats
  (let [a (fleet/make-lease "t" "g" :owner "a" :epoch 1)
        b (fleet/make-lease "t" "g" :owner "b" :epoch 2)]
    (is (true? (fence/lease-beats? b a)))
    (is (false? (fence/lease-beats? a b)))))

(defn- claim [reg lease node]
  (fence/claim-lease reg lease node))

(deftest claim-free-and-refuse-other
  (binding [fleet/*now-ms* 1000]
    (let [la (fleet/make-lease "t" "g" :owner "a" :ttl-ms 60000 :epoch 1)
          ca (claim (fleet/empty-registry) la "a")
          lb (fleet/make-lease "t" "g" :owner "b" :ttl-ms 60000 :epoch 1)
          refuse (claim (:registry ca) lb "b")]
      (is (true? (:ok? ca)))
      (is (= :free (:reason ca)))
      (is (false? (:ok? refuse)))
      (is (= :held-by-other (:reason refuse))))))

(deftest claim-steal-with-higher-epoch
  (binding [fleet/*now-ms* 1000]
    (let [la (fleet/make-lease "t" "g" :owner "a" :ttl-ms 60000 :epoch 1)
          ca (claim (fleet/empty-registry) la "a")
          lb (fleet/make-lease "t" "g" :owner "b" :ttl-ms 60000 :epoch 2)
          steal (claim (:registry ca) lb "b")]
      (is (true? (:ok? steal)))
      (is (= :higher-epoch (:reason steal)))
      (is (= "b" (:kototama.fleet/owner (:lease steal))))
      (is (= 1 (count (fleet/all-leases (:registry steal))))))))

(deftest claim-steal-expired
  (binding [fleet/*now-ms* 0]
    (let [la (fleet/make-lease "t" "g" :owner "a" :ttl-ms 10 :epoch 1)
          ca (claim (fleet/empty-registry) la "a")]
      (binding [fleet/*now-ms* 100]
        (let [lb (fleet/make-lease "t" "g" :owner "b" :ttl-ms 60000 :epoch 1)
              steal (claim (:registry ca) lb "b")]
          (is (true? (:ok? steal)))
          (is (= :steal-expired (:reason steal)))
          (is (= "b" (:kototama.fleet/owner (:lease steal)))))))))

(deftest merge-registries-keeps-winner
  (binding [fleet/*now-ms* 50]
    (let [la (fleet/make-lease "t" "g" :owner "a" :epoch 1)
          lb (fleet/make-lease "t" "g" :owner "b" :epoch 3)
          ra (fleet/register-lease (fleet/empty-registry) la)
          rb (fleet/register-lease (fleet/empty-registry) lb)
          m (fence/merge-registries ra rb)
          owners (mapv :kototama.fleet/owner (fleet/all-leases m))]
      (is (= ["b"] owners))
      (is (= 3 (fence/epoch (first (fleet/all-leases m))))))))

(deftest same-owner-renews
  (binding [fleet/*now-ms* 100]
    (let [la (fleet/make-lease "t" "g" :owner "a" :ttl-ms 1000 :epoch 1)
          ca (claim (fleet/empty-registry) la "a")
          renew (claim (:registry ca) la "a")]
      (is (true? (:ok? renew)))
      (is (= :renew (:reason renew)))
      (is (= (:kototama.fleet/lease-id (:lease ca))
             (:kototama.fleet/lease-id (:lease renew)))))))
