(ns kototama.q9-migration-policy-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]))

(def policy
  (edn/read-string (slurp "qualification/q9-whole-component-build.edn")))

(def required-builds #{:kotoba-cli-build :amu-compile})
(def required-equivalence
  #{:payload-cid :definition-cids :exports :imports :effects :resource-bounds})

(deftest migration-unit-is-a-whole-component
  (is (= :whole-component (get-in policy [:scope :migration-unit])))
  (is (false? (get-in policy [:scope :decision-only-slices-accepted])))
  (is (false? (get-in policy [:scope :function-parity-is-migration-proof])))
  (is (true? (get-in policy [:scope :complete-public-surface-required])))
  (is (true? (get-in policy [:scope :transitive-source-closure-required])))
  (is (= :declared-capability-provider-import
         (get-in policy [:scope :native-behavior])))
  (is (false? (get-in policy [:scope :ambient-host-fallback]))))

(deftest both-public-build-paths-are-required
  (is (= required-builds
         (set (get-in policy [:build-contract :required-paths]))))
  (is (= "kotoba check --safe <entry.cljk>"
         (get-in policy [:build-contract :kotoba-cli :check])))
  (is (= "kotoba compile <entry.cljk> --target <target> --output <cli-artifact>"
         (get-in policy [:build-contract :kotoba-cli :compile])))
  (is (= "kotoba rad build --project <repository> --profile release"
         (get-in policy [:build-contract :kotoba-cli :package])))
  (is (= "amu check <entry.cljk>"
         (get-in policy [:build-contract :amu :check])))
  (is (= "amu compile <entry.cljk> --target <target> --output <amu-artifact>"
         (get-in policy [:build-contract :amu :compile])))
  (is (= required-equivalence
         (set (get-in policy [:build-contract :artifact-equivalence]))))
  (is (false? (get-in policy [:build-contract
                              :internal-compiler-entry-accepted]))))

(deftest candidates-cover-the-current-public-components
  (let [components (into {} (map (juxt :component identity) (:components policy)))]
    (testing "leash"
      (is (= #{'leash 'valid? 'write-author 'revoked?}
             (set (map :name (get-in components
                                     [:kototama.leash :public-surface])))))
      (is (= :not-started (get-in components [:kototama.leash :status]))))
    (testing "UNSPSC life"
      (is (= #{'base-joucho 'event-vocab 'event-deltas
               'joucho-from-events 'mood-label 'cadence-secs
               'heartbeat-due? 'unknown-event-kinds
               'prior-consensus 'prior-shortcut?}
             (set (map :name (get-in components
                                     [:kototama.unspsc.life :public-surface])))))
      (is (= #{'clamp 'cadences 'status-frequencies 'input-matches?}
             (set (get-in components
                          [:kototama.unspsc.life :transitive-private-surface]))))
      (is (= :not-started
             (get-in components [:kototama.unspsc.life :status]))))))

(deftest historical-decision-slice-cannot-be-a-cutover
  (let [legacy (first (:legacy-decision-slices policy))]
    (is (= :historical-compiler-evidence-only (:status legacy)))
    (is (false? (:new-consumer-cutover legacy)))
    (is (false? (:expansion legacy)))
    (is (= :kototama.unspsc.life (:successor legacy))))
  (is (= {:consumer-cutover false
          :legacy-source-deletion false
          :production-deploy false}
         (:authorization policy))))
