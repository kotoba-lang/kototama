(ns kototama.contract-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [kototama.contract :as contract]))

(deftest import-surface-data-is-canonical
  (is (= "actor:host" (:abi/namespace contract/import-surface)))
  (is (= 0 (:abi/version contract/import-surface)))
  (is (= :kototama.contract/HostCaps (:model/name contract/HostCaps)))
  (is (= :kototama.contract/RuntimeLimits (:model/name contract/RuntimeLimits)))
  (is (= #{:gen-keypair :sign :verify :sha256-hex :http-post :log-read :log-append! :now}
         (set (keys contract/import-by-id))))
  (is (= :log-append! (contract/import-id "log-append!"))))

(deftest host-caps-normalize-grants-and-limits
  (let [caps (contract/host-caps {:grants ["verify" :now {:fn "log-read"}]
                                  :limits {:max-imports 3}})]
    (is (= #{:verify :now :log-read} (:grants caps)))
    (is (= 3 (get-in caps [:limits :max-imports])))
    (is (= 0 (get-in caps [:limits :max-http-posts])))
    (is (false? (get-in caps [:limits :allow-write-imports?])))))

(deftest granted-imports-pass
  (let [result (contract/validate-import-surface
                 {:abi/namespace "actor:host"
                  :abi/version 0
                  :abi/imports [{:fn "verify"} {:import/name "now"}]}
                 {:grants [:verify :now]
                  :limits {:max-imports 2}})]
    (is (:ok? result))
    (is (= [:verify :now] (:requested result)))
    (is (empty? (:errors result)))))

(deftest missing-and-unknown-imports-fail-with-data
  (let [result (contract/validate-import-surface
                 ["verify" "http-delete" "log-append!"]
                 {:grants [:verify]
                  :limits {:max-imports 8
                           :allow-secret-imports? true
                           :allow-write-imports? true}})]
    (is (false? (:ok? result)))
    (is (= [{:error :imports/unknown
             :imports ["http-delete"]}
            {:error :grants/missing
             :imports [:log-append!]}]
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

(deftest write-imports-require-explicit-host-opt-in
  (let [closed (contract/validate-import-surface
                 ["log-append!"]
                 {:grants [:log-append!]
                  :limits {:max-imports 1}})
        open (contract/validate-import-surface
               ["log-append!"]
               {:grants [:log-append!]
                :limits {:max-imports 1
                         :allow-write-imports? true}})]
    (is (false? (:ok? closed)))
    (is (= [{:error :limit/write-imports
             :imports [:log-append!]}]
           (:errors closed)))
    (is (:ok? open))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'kototama.contract-test)]
    (when (pos? (+ (or fail 0) (or error 0)))
      #?(:clj (System/exit 1)
         :cljs (throw (ex-info "contract tests failed" {:fail fail :error error}))))))
