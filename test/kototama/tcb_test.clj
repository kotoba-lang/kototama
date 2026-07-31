(ns kototama.tcb-test
  (:require [clojure.test :refer [deftest is]]
            [kototama.tcb :as tcb]))

(def required-boundaries
  #{"src/kototama/contract.cljc"
    "src/kototama/tender.clj"
    "src/kototama/browser.cljc"
    "src/kototama/component_platform.clj"
    "src/kototama/component_provider.cljc"
    "src/kototama/workerd_core.clj"
    "workerd/kototama-core-host.mjs"
    "src/kototama/compatibility.clj"
    "src/kototama/aiueos_adapter.clj"
    "src/kototama/linear_journal.clj"
    "src/kototama/component_authority.clj"
    "src/kototama/component_authority_http.clj"
    "src/kototama/component_authority_daemon.clj"
    "src/kototama/network_authority.clj"
    "src/kototama/release_evidence.clj"
    "src/kototama/signer_lifecycle.clj"
    "src/kototama/tcb.clj"})

(deftest checked-in-tcb-has-no-drift
  (is (= {:valid? true :files 23 :external 8 :errors []}
         (tcb/validate))))

(deftest authority-and-runtime-boundaries-cannot-disappear-silently
  (let [inventory (tcb/read-inventory)
        paths (set (map :path (:tcb/files inventory)))]
    (is (every? paths required-boundaries))
    (is (every? (comp keyword? :role) (:tcb/files inventory)))
    (is (every? :coordinate (:tcb/external inventory)))))

(deftest digest-and-external-version-drift-fail-closed
  (let [inventory (tcb/read-inventory)
        digest-drift (assoc-in inventory [:tcb/files 0 :sha256]
                               (apply str (repeat 64 "0")))
        unpinned (update-in inventory [:tcb/external 0]
                            dissoc :version :git-sha :minimum-version)]
    ;; Membership, not `first`. Each assertion's claim is "this mutation
    ;; produces this error kind", not "this is the only error in the
    ;; inventory" -- and both mutations start from the LIVE inventory, so any
    ;; unrelated finding in it lands in the same list.
    ;;
    ;; Measured 2026-07-31: with four real :digest-drift entries present
    ;; (kotoba-lang/kototama#117), the `unpinned` assertion failed with
    ;;   expected :unversioned-external-boundary, got :digest-drift
    ;; while the unversioned-boundary check itself was working exactly as
    ;; intended. A test that reports someone else's failure under its own name
    ;; costs a reader the time it takes to rule it out.
    (let [kinds #(set (map :kind (:errors (tcb/validate %))))]
      (is (contains? (kinds digest-drift) :digest-drift))
      (is (contains? (kinds unpinned) :unversioned-external-boundary)))))
