(ns kototama.component-platform
  "Fail-closed admission for compiler-produced WIT/Component Model worlds."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def contract
  (edn/read-string (slurp (io/resource "kototama/component-platform.edn"))))

(defn- reject [code message]
  (throw (ex-info message {:phase :component-platform :kototama.component/code code})))

(defn validate-world!
  "Validate a decoded component admission envelope before engine instantiation."
  [world]
  (let [expected (set (:admission-keys contract))]
    (when-not (and (map? world) (= expected (set (keys world))))
      (reject :invalid-envelope "component admission envelope is not exact"))
    (when-not (= (:target contract) (:target world))
      (reject :target-mismatch "component target is unsupported"))
    (when-not (= (get-in contract [:wasi :default]) (:wasi-version world))
      (reject :wasi-mismatch "WASI version requires an explicit compatibility tender"))
    (when-not (contains? #{:sync :async} (:profile world))
      (reject :invalid-profile "component profile is unsupported"))
    (doseq [field [:imports :exports :grants]]
      (when-not (and (set? (field world)) (<= (count (field world)) 256))
        (reject :invalid-envelope (str (name field) " must be a bounded set"))))
    (when-not (and (map? (:provider-bindings world))
                   (<= (count (:provider-bindings world)) 256)
                   (= (:imports world) (set (keys (:provider-bindings world)))))
      (reject :unbound-import "every declared import requires one exact provider binding"))
    (when-not (every? (:grants world) (:imports world))
      (reject :capability-denied "component import is not granted"))
    (when-not (false? (:ambient-wasi world))
      (reject :ambient-authority "ambient WASI is forbidden"))
    (let [budgets (:budgets world)]
      (when-not (map? budgets) (reject :invalid-budgets "component budgets must be a map"))
      (when (= :async (:profile world))
        (when-not (and (= true (:cancellation budgets))
                       (every? #(let [n (get budgets %)] (and (integer? n) (pos? n)))
                               (get-in contract [:profiles :async :required-budgets])))
          (reject :invalid-budgets "async components require cancellation and positive bounds"))))
    world))
