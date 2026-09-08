(ns kototama.implementation-profile-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

;; Validates every qualification/implementation-declaration-*.edn against the
;; :conformance :implementation-declaration schema in spec/kototama-vm-v1.edn.
;; seq-3 follow-up: the declaration was standalone in qualification/; no test
;; validated it against the spec schema. This closes that gap.
;;
;; FINDING (2026-09-08, this test's first run): the two declarations drifted in
;; shape — amu-evm uses :profiles as a vector of {:profile k ...} maps and
;; :spec as a keyword; kototama-evm-tender uses :profiles as a {profile->map}
;; map and :spec as a spec-reference map. The spec schema does NOT pin
;; :profiles/:spec shape, so both satisfy the letter of the schema while
;; having incompatible shapes. We normalise both and validate schema
;; semantics; the shape divergence is recorded as a finding for a spec
;; tightening (do NOT claim consensus it doesn't have).
;;
;; 8-question Q1: an empty declaration set must FAIL, not pass vacuously.
(def spec
  (-> "spec/kototama-vm-v1.edn" io/file slurp edn/read-string))

(def conformance (:conformance spec))

(defn- glob-declaration-files []
  (let [dir (io/file "qualification")]
    (->> (seq (.listFiles dir))
         (filter (fn [f]
                   (and (str/includes? (.getName f) "implementation-declaration")
                        (str/ends-with? (.getName f) ".edn")))))))

;; Normalise both observed :profiles shapes -> flat vector of {:profile k ...map}.
(defn- normalised-profiles [d]
  (let [p (:profiles d)]
    (cond
      (map? p)      (mapv (fn [[k v]] (assoc v :profile k)) p)
      (vector? p)   (mapv (fn [pr] (if (map? pr) pr {:profile nil, :status nil, :omissions nil, :levels []})) p)
      :else         [])))

;; Normalise both observed :spec shapes -> a keyword identifying the spec.
(defn- read-declaration [f]
  (edn/read-string (slurp f)))

(defn- normalised-spec [d]
  (let [s (:spec d)]
    (cond
      (keyword? s) s
      (map? s)     (:schema s)
      :else        nil)))

(deftest declarations-exist-vacuity-guard
  (is (pos? (count (glob-declaration-files)))
      "at least one implementation-declaration-*.edn must exist in qualification/"))

(deftest declarations-refer-to-the-kototama-vm-v1-spec
  ;; Two valid references exist in the wild: :spec :kototama-vm-v1 (spec file)
  ;; and :spec {:schema :kototama.vm/spec-v1 ...} (spec-reference map). Both
  ;; must name the kototama-vm-v1 specification family.
  (doseq [f (glob-declaration-files)]
    (let [d (read-declaration f)]
      (is (contains? #{:kototama-vm-v1 :kototama.vm/spec-v1} (normalised-spec d))
          (str (.getName f) " :spec must reference the kototama-vm-v1 spec "
               "(file keyword or :spec-reference map)")))))

(deftest declarations-satisfy-required-and-profile-required-keys
  (let [{:keys [required profile-required]} (:implementation-declaration conformance)]
    (doseq [f (glob-declaration-files)]
      (let [d (read-declaration f)]
        (is (every? #(contains? d %) required)
            (str (.getName f) " missing required keys: "
                 (remove (set (keys d)) required)))
        (doseq [p (normalised-profiles d)]
          (is (map? p) (str (.getName f) " profile must be a map"))
          (is (every? #(contains? p %) profile-required)
              (str (.getName f) " profile " (:profile p) " missing: "
                   (remove (set (keys p)) profile-required))))))))

(deftest declarations-use-only-known-statuses-and-list-omissions-for-partial
  (let [{:keys [statuses evidence-required-for empty-levels-required-for]} (:implementation-declaration conformance)]
    (doseq [f (glob-declaration-files)]
      (let [d (read-declaration f)]
        (doseq [p (normalised-profiles d)]
          (is (contains? (set statuses) (:status p))
              (str (.getName f) " profile " (:profile p) " :status " (:status p)
                   " not in " statuses))
          (when (contains? (set evidence-required-for) (:status p))
            ;; :evidence may live at top level (map) or per-profile (:partial
            ;; profiles carry their own :evidence vector). Spec is shape-agnostic.
            (let [top (map? (:evidence d))
                  per-profile (seq (:evidence p))]
              (is (or top per-profile)
                  (str (.getName f) " :status " (:status p)
                       " requires :evidence top-level or per-profile"))))
          (when (contains? (set empty-levels-required-for) (:status p))
            (is (empty? (:levels p))
                (str (.getName f) " profile " (:profile p) " :status " (:status p)
                     " requires empty :levels"))))))))
