(ns kototama.component-platform
  "Fail-closed admission for compiler-produced WIT/Component Model worlds."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def contract
  (edn/read-string (slurp (io/resource "kototama/component-platform.edn"))))

(defn- reject [code message]
  (throw (ex-info message {:phase :component-platform :kototama.component/code code})))

(defn- cid-looking?
  "The component platform receives identities only after the artifact/package
  verifier has structurally decoded their CIDs. This envelope gate still
  rejects blank or non-CID-shaped substitutions before linking; it deliberately
  does not duplicate the artifact verifier's multibase implementation."
  [x]
  (and (string? x) (.startsWith ^String x "b") (> (count x) 1)))

(defn- validate-identity! [identity]
  (let [required (set (get-in contract [:identity :required]))]
    (when-not (and (map? identity) (= required (set (keys identity))))
      (reject :invalid-identity "component identity binding is not exact"))
    (when-not (every? cid-looking?
                      [(:component-cid identity) (:package-lock-cid identity)])
      (reject :invalid-identity "component and package-lock identities must be CIDs"))
    (when-not (and (set? (:definition-cids identity))
                   (seq (:definition-cids identity))
                   (<= (count (:definition-cids identity)) 1024)
                   (every? cid-looking? (:definition-cids identity)))
      (reject :invalid-identity "definition identities must be a bounded non-empty CID set"))))

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
      (let [required (get-in contract [:profiles (:profile world) :required-budgets])]
        (when-not (every? #(let [n (get budgets %)] (and (integer? n) (pos? n))) required)
          (reject :invalid-budgets "components require positive resource bounds")))
      (when (and (= :async (:profile world))
                 (not= true (:cancellation budgets)))
        (reject :invalid-budgets "async components require cancellation")))
    (validate-identity! (:identity world))
    world))
