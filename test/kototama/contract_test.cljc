(ns kototama.contract-test
  (:require [clojure.test :refer [deftest is]]
            [kototama.contract :as contract]))

(deftest import-surface-data-is-canonical
  (is (= "actor:host" (:abi/namespace contract/import-surface)))
  (is (= 0 (:abi/version contract/import-surface)))
  (is (= :kototama.contract/HostCaps (:model/name contract/HostCaps)))
  (is (= :kototama.contract/RuntimeLimits (:model/name contract/RuntimeLimits)))
  (is (= #{:gen-keypair :sign :verify :sha256-hex :http-post :llm-infer
           :log-read :log-write :clock-monotonic}
         (set (keys contract/import-by-id))))
  (is (= :log-write (contract/import-id "log-write"))))

(deftest host-caps-normalize-grants-and-limits
  (let [caps (contract/host-caps {:grants ["verify" :clock-monotonic {:fn "log-read"}]
                                  :limits {:max-imports 3}})]
    (is (= #{:verify :clock-monotonic :log-read} (:grants caps)))
    (is (= 3 (get-in caps [:limits :max-imports])))
    (is (= 0 (get-in caps [:limits :max-http-posts])))
    (is (= 0 (get-in caps [:limits :max-llm-infers])))
    (is (= 16 (get-in caps [:limits :max-memory-pages])))
    (is (false? (get-in caps [:limits :allow-write-imports?])))))

(deftest granted-imports-pass
  (let [result (contract/validate-import-surface
                 {:abi/namespace "actor:host"
                  :abi/version 0
                  :abi/imports [{:fn "verify"} {:import/name "clock-monotonic"}]}
                 {:grants [:verify :clock-monotonic]
                  :limits {:max-imports 2}})]
    (is (:ok? result))
    (is (= [:verify :clock-monotonic] (:requested result)))
    (is (empty? (:errors result)))))

(deftest missing-and-unknown-imports-fail-with-data
  (let [result (contract/validate-import-surface
                 ["verify" "http-delete" "log-write"]
                 {:grants [:verify]
                  :limits {:max-imports 8
                           :allow-secret-imports? true
                           :allow-write-imports? true}})]
    (is (false? (:ok? result)))
    (is (= [{:error :imports/unknown
             :imports ["http-delete"]}
            {:error :grants/missing
             :imports [:log-write]}]
           (:errors result)))))

(deftest wrong-abi-surface-fails-with-data
  (let [result (contract/validate-import-surface
                 {:abi/namespace "kami:engine"
                  :abi/version 1
                  :abi/imports ["verify"]}
                 {:grants [:verify]
                  :limits {:max-imports 1}})]
    (is (false? (:ok? result)))
    (is (= [{:error :abi/namespace
             :expected "actor:host"
             :actual "kami:engine"}
            {:error :abi/version
             :expected 0
             :actual 1}]
           (:errors result)))))

(deftest limits-reject-excess-http-and-secret-imports
  (let [result (contract/validate-import-surface
                 ["gen-keypair" "http-post"]
                 {:grants [:gen-keypair :http-post]
                  :limits {:max-imports 1
                           :max-http-posts 0
                           :allow-secret-imports? false}})]
    (is (false? (:ok? result)))
    (is (= #{:limit/max-imports :limit/max-http-posts :limit/secret-imports}
           (set (map :error (:errors result)))))))

(deftest limits-reject-excess-llm-infers
  (let [result (contract/validate-import-surface
                 ["llm-infer"]
                 {:grants [:llm-infer]
                  :limits {:max-imports 1
                           :max-llm-infers 0}})]
    (is (false? (:ok? result)))
    (is (= [{:error :limit/max-llm-infers :limit 0 :actual 1}]
           (:errors result)))))

(deftest write-imports-require-explicit-host-opt-in
  (let [closed (contract/validate-import-surface
                 ["log-write"]
                 {:grants [:log-write]
                  :limits {:max-imports 1}})
        open (contract/validate-import-surface
               ["log-write"]
               {:grants [:log-write]
                :limits {:max-imports 1
                         :allow-write-imports? true}})]
    (is (false? (:ok? closed)))
    (is (= [{:error :limit/write-imports
             :imports [:log-write]}]
           (:errors closed)))
    (is (:ok? open))))
