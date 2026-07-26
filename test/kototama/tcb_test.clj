(ns kototama.tcb-test
  (:require [clojure.test :refer [deftest is]]
            [kototama.tcb :as tcb]))

(def required-boundaries
  #{"src/kototama/contract.cljc"
    "src/kototama/tender.clj"
    "src/kototama/browser.cljc"
    "src/kototama/component_platform.clj"
    "src/kototama/component_provider.cljc"
    "src/kototama/wasmtime_component.clj"
    "native/wasmtime-component-host/src/main.rs"
    "native/jco-component-host.mjs"
    "src/kototama/workerd_core.clj"
    "workerd/kototama-core-host.mjs"
    "src/kototama/compatibility.clj"
    "src/kototama/aiueos_adapter.clj"
    "src/kototama/linear_journal.clj"
    "src/kototama/component_authority.clj"
    "src/kototama/component_authority_http.clj"
    "src/kototama/component_authority_daemon.clj"
    "src/kototama/fleet_exec.clj"
    "src/kototama/network_authority.clj"
    "src/kototama/release_evidence.clj"
    "src/kototama/signer_lifecycle.clj"
    "src/kototama/transport_provider.clj"
    "src/kototama/tcb.clj"})

(deftest checked-in-tcb-has-no-drift
  (is (= {:valid? true :files 32 :external 10 :errors []}
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
    (is (= :digest-drift
           (-> (tcb/validate digest-drift) :errors first :kind)))
    (is (= :unversioned-external-boundary
           (-> (tcb/validate unpinned) :errors first :kind)))))
