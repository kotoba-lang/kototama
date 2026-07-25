(ns kototama.component-platform-test
  (:require [clojure.test :refer [deftest is testing]]
            [kototama.component-platform :as platform]
            [multiformats.core :as mf]))

(defn cid [value]
  (mf/cidv1-raw (.getBytes ^String value "UTF-8")))

(defn execution-identity [component-cid]
  {:format :kotoba.execution-identity/v1
   :plan-cid (cid "plan") :code-closure-cid (cid "closure")
   :artifact-cid (cid "artifact") :compiler-contract (cid "compiler-contract")
   :component-cid component-cid :wit-world-cid (cid "wit-world")
   :package-lock-cid (cid "package-lock") :policy-cid (cid "policy")
   :policy-decision-cid (cid "decision") :db-basis (cid "basis")
   :grant-cids [(cid "grant")] :approval-cids [(cid "approval")]
   :runtime-identity (cid "runtime") :input-cid (cid "input")
   :outcome-cid (cid "outcome") :host-receipt-cids [(cid "receipt")]})

(def valid
  {:target :wasm-component-kotoba-v1 :wasi-version "0.3.0" :profile :sync
   :imports #{:kotoba/http-post} :exports #{:app/run}
   :grants #{:kotoba/http-post}
   :provider-bindings {:kotoba/http-post :provider/http}
   :abilities {:kotoba/http-post {:target "https://api.example.test/submit"
                                  :operation :http/post :max-bytes 1024 :max-items 1
                                  :deadline-ms 1000 :audit-id "component-test"}}
   :runtime-bindings {:component-host-sha256
                      "0000000000000000000000000000000000000000000000000000000000000000"}
   :ambient-wasi false :budgets {:fuel 1000000 :memory-pages 4}
   :identity {:component-cid (cid "component") :package-lock-cid (cid "lock")
              :definition-cids #{(cid "definition")}}})

(defn code [value]
  (try (platform/validate-world! value) nil
       (catch clojure.lang.ExceptionInfo e (:kototama.component/code (ex-data e)))))

(deftest component-world-admission-is-closed
  (is (= valid (platform/validate-world! valid)))
  (is (= :invalid-envelope (code (assoc valid :invented true))))
  (is (= :wasi-mismatch (code (assoc valid :wasi-version "0.2.11"))))
  (is (= :ambient-authority (code (assoc valid :ambient-wasi true))))
  (is (= :capability-denied (code (assoc valid :grants #{}))))
  (is (= :unbound-import (code (assoc valid :provider-bindings {}))))
  (is (= :ability-mismatch (code (assoc valid :abilities {}))))
  (is (= :invalid-runtime-binding (code (assoc valid :runtime-bindings {}))))
  (is (= :invalid-ability
         (code (assoc-in valid [:abilities :kotoba/http-post :audit-id] ""))))
  (is (= :invalid-identity (code (assoc valid :identity {}))))
  (is (= :invalid-identity
         (code (assoc-in valid [:identity :component-cid] "bafycomponent"))))
  (is (= :invalid-budgets (code (assoc valid :budgets {})))))

(deftest async-world-requires-cancellation-and-bounds
  (testing "WASI 0.3 does not imply unbounded async authority"
    (is (= :invalid-budgets (code (assoc valid :profile :async))))
    (let [async (assoc valid :profile :async
                       :budgets {:fuel 1000000 :memory-pages 4 :cancellation true :deadline-ms 1000
                                 :max-items 32 :max-bytes 65536})]
      (is (= async (platform/validate-world! async))))))

(deftest provider-free-components-carry-no-native-host-authority
  (let [pure (assoc valid :imports #{} :grants #{} :provider-bindings {}
                    :abilities {} :runtime-bindings {})]
    (is (= pure (platform/validate-world! pure)))
    (is (= :invalid-runtime-binding
           (code (assoc pure :runtime-bindings (:runtime-bindings valid)))))))

(deftest component-bytes-are-verified-before-the-linker-receives-them
  (let [bytes (.getBytes "component" "UTF-8")
        world (assoc-in valid [:identity :component-cid] (mf/cidv1-raw bytes))
        linked (atom nil)]
    (is (= :linked
           (platform/admit-and-link! world bytes
                                     (fn [request] (reset! linked request) :linked))))
    (is (= bytes (:component-bytes @linked)))
    (is (= (:abilities world) (:abilities @linked)))
    (is (= :component-cid-mismatch
           (try (platform/admit-and-link!
                 (assoc-in world [:identity :component-cid] (cid "other")) bytes identity)
                nil
                (catch clojure.lang.ExceptionInfo e
                  (:kototama.component/code (ex-data e))))))))

(deftest execution-identity-is-verified-before-component-linking
  (let [bytes (.getBytes "component" "UTF-8")
        component-cid (mf/cidv1-raw bytes)
        world (assoc-in valid [:identity :component-cid] component-cid)
        identity (execution-identity component-cid)]
    (is (= identity (platform/validate-execution-identity! identity)))
    (is (= :linked
           (platform/admit-and-link! world identity bytes (constantly :linked))))
    (is (= :execution-component-mismatch
           (try (platform/admit-and-link! world (assoc identity :component-cid (cid "other"))
                                         bytes (fn [_] :linked))
                nil
                (catch clojure.lang.ExceptionInfo e
                  (:kototama.component/code (ex-data e))))))
    (is (= :invalid-execution-identity
           (try (platform/validate-execution-identity! (assoc identity :extra true)) nil
                (catch clojure.lang.ExceptionInfo e
                  (:kototama.component/code (ex-data e))))))))
