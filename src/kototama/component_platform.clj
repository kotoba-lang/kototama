(ns kototama.component-platform
  "Fail-closed admission for compiler-produced WIT/Component Model worlds."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [kotoba.abi.contract :as abi]
            [multiformats.core :as mf]))

(def contract
  (edn/read-string (slurp (io/resource "kototama/component-platform.edn"))))

(defn- reject [code message]
  (throw (ex-info message {:phase :component-platform :kototama.component/code code})))

(defn- read-varint [bs offset]
  (loop [offset offset value 0 shift 0]
    (when (>= offset (count bs))
      (throw (ex-info "truncated CID varint" {:offset offset})))
    (let [b (bit-and (nth bs offset) 0xff)]
      (if (< b 0x80)
        [(bit-or value (bit-shift-left b shift)) (inc offset)]
        (recur (inc offset) (bit-or value (bit-shift-left (bit-and b 0x7f) shift))
               (+ shift 7))))))

(defn- cid?
  "True only for a fully decodable CIDv1 with a well-formed multihash.
  Component admission is a security boundary, so it must not treat a
  multibase-looking string as an artifact identity."
  [x]
  (and (string? x)
       (.startsWith ^String x "b")
       (try
         (let [bs (mf/cid->bytes x)
               [version off1] (read-varint bs 0)
               [codec off2] (read-varint bs off1)
               [hash-fn off3] (read-varint bs off2)
               [hash-len off4] (read-varint bs off3)]
           (and (= 1 version) (pos? codec) (pos? hash-fn) (pos? hash-len)
                (= hash-len (- (count bs) off4))))
         (catch Exception _ false))))

(defn- validate-identity! [identity]
  (let [required (set (get-in contract [:identity :required]))]
    (when-not (and (map? identity) (= required (set (keys identity))))
      (reject :invalid-identity "component identity binding is not exact"))
    (when-not (every? cid?
                      [(:component-cid identity) (:package-lock-cid identity)])
      (reject :invalid-identity "component and package-lock identities must be CIDs"))
    (when-not (and (set? (:definition-cids identity))
                   (seq (:definition-cids identity))
                   (<= (count (:definition-cids identity)) 1024)
                   (every? cid? (:definition-cids identity)))
      (reject :invalid-identity "definition identities must be a bounded non-empty CID set"))))

(defn- validate-abilities! [imports abilities]
  (when-not (and (map? abilities)
                 (= imports (set (keys abilities))))
    (reject :ability-mismatch "every declared import requires one exact scoped ability"))
  (doseq [[import ability] abilities]
    (when-not (and (keyword? import) (abi/valid-ability? ability))
      (reject :invalid-ability "component ability is not a complete bounded descriptor"))))

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
    (validate-abilities! (:imports world) (:abilities world))
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

(defn admit-and-link!
  "The sole Component execution hand-off.  The native engine/linker is passed
  in by the micro-TCB, but it receives bytes and provider bindings only after
  this gate has verified identity, world, grants, and resource bounds."
  [world component-bytes linker!]
  (let [world (validate-world! world)
        declared (get-in world [:identity :component-cid])
        actual (mf/cidv1-raw component-bytes)]
    (when-not (= declared actual)
      (reject :component-cid-mismatch "component bytes do not match admission identity"))
    (when-not (ifn? linker!)
      (reject :invalid-linker "native Component linker is required"))
    (linker! {:component-bytes component-bytes
              :imports (:provider-bindings world)
              :abilities (:abilities world)
              :budgets (:budgets world)
              :identity (:identity world)})))
