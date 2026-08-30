(ns kototama.vm-spec-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(def spec
  (-> "spec/kototama-vm-v1.edn" io/file slurp edn/read-string))

(deftest definition-is-an-implementation-independent-machine-contract
  (is (= :kototama.vm/spec-v1 (:schema spec)))
  (is (= :content-addressed-abstract-machine
         (get-in spec [:definition :kind])))
  (is (true? (get-in spec [:definition :implementation-independent])))
  (is (= #{:reduction :state :logic :authority :evidence}
         (set (keys (:planes spec))))))

(deftest lisp-is-closed-data-not-host-eval
  (is (= :lisp-list (get-in spec [:program :source-form])))
  (is (= :tagged-dag-cbor-vector
         (get-in spec [:program :canonical-form])))
  (is (false? (get-in spec [:planes :reduction :host-eval])))
  (is (false? (get-in spec [:planes :reduction :reflection])))
  (is (false? (get-in spec [:planes :reduction :ambient-ffi]))))

(deftest capability-needs-all-five-trusted-origins
  (is (= [:intersection
          :amu-static-effect
          :vm-requested-intent
          :biscuit-delegated-grant
          :local-policy-allow
          :runtime-availability]
         (get-in spec [:authority :effective-capability])))
  (is (= :deny (get-in spec [:authority :missing-origin])))
  (is (= #{"vm:runs" "amu:requires" "amu:world" "vm:requests"
           "grant:right" "policy:allows" "runtime:world"
           "runtime:available"}
         (->> (get-in spec [:authority :eligibility-rule :body])
              (map first)
              set)))
  (is (false? (get-in spec
                      [:planes :logic
                       :token-facts-may-impersonate-trusted-origins])))
  (is (false? (get-in spec [:planes :authority :raw-bearer-in-car])))
  (is (false? (get-in spec [:planes :authority :raw-bearer-in-receipt]))))

(deftest state-transitions-commit-or-discard-one-overlay
  (is (= :commit-overlay (get-in spec [:transition :success :state])))
  (is (= :discard-overlay (get-in spec [:transition :revert :state])))
  (is (= :discard-overlay
         (get-in spec [:transition :resource-exhaustion :state])))
  (is (= :value
         (get-in spec [:transition :resource-exhaustion :outcome])))
  (is (true? (get-in spec [:transition :attempt-always-receipted]))))

(deftest compatibility-is-versioned-and-evidenced
  (is (= #{:core/v1 :fvm-actor/v1 :evm/v1 :fevm/v1}
         (set (keys (:profiles spec)))))
  (is (= #{:fvm-actor/v1 :evm/v1}
         (->> (get-in spec [:profiles :fevm/v1 :composes])
              (map :profile)
              set)))
  (is (= #{:message :semantic :bytecode :state}
         (set (keys (get-in spec [:profiles :evm/v1 :levels])))))
  (is (true? (get-in spec [:compatibility :floating-evm-claim-forbidden])))
  (is (true? (get-in spec [:compatibility :floating-fvm-claim-forbidden])))
  (is (false? (get-in spec
                      [:profiles :fevm/v1 :gas :ethereum-gas-equality])))
  (is (= [:profile :level :version :implementation :evidence]
         (get-in spec [:compatibility :claim-must-name]))))

(deftest receipt-cites-authority-but-never-bearers
  (is (= :kototama.vm/receipt-v1 (get-in spec [:receipt :schema])))
  (is (= #{:raw-biscuit :private-key :ambient-host-handle}
         (set (get-in spec [:receipt :forbidden]))))
  (is (= [:manifest-cid :grant-ids :policy-id :world :epoch]
         (get-in spec [:receipt :authority-decision]))))

(deftest implementation-declarations-cannot-hide-partial-conformance
  (is (= [:spec :implementation :profiles :non-claims]
         (get-in spec
                 [:conformance :implementation-declaration :required])))
  (is (= [:status :omissions]
         (get-in spec
                 [:conformance :implementation-declaration
                  :profile-required])))
  (is (= [:conformant :partial :shape-only]
         (get-in spec
                 [:conformance :implementation-declaration
                  :evidence-required-for])))
  (is (true? (get-in spec
                     [:conformance
                      :partial-conformance-must-list-omissions]))))
