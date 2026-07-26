(ns kototama.release-evidence-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kototama.release-evidence :as release]))

(defn delete-tree! [root]
  (when (.exists (io/file root))
    (doseq [file (reverse (file-seq (io/file root)))]
      (.delete file))))

(deftest release-is-reproducible-signed-and-self-verifying
  (let [root (str "tmp/release-evidence-" (System/currentTimeMillis))
        a (str root "/a")
        b (str root "/b")
        seed (byte-array (map unchecked-byte (range 32)))]
    (try
      (let [first-build (release/build-evidence! a seed)
            second-build (release/build-evidence! b seed)]
        (is (= (:sha256 first-build) (:sha256 second-build))
            "same source state produces byte-identical JARs")
        (is (= (:source-root first-build) (:source-root second-build)))
        (is (pos? (:files first-build)))
        (is (:valid? (release/verify-evidence first-build)))
        (is (= #{"digest" "provenance" "sbom" "signature"}
               (set (map name
                         (keys (:checks
                                (release/verify-evidence first-build))))))))
      (finally (delete-tree! root)))))

(deftest release-pins-one-typed-component-abi-revision
  (let [coordinates (release/component-conformance-coordinates)]
    (is (:valid? coordinates))
    (is (= (:abi-sha coordinates) (:native-abi-sha coordinates)))
    (is (= "0.3.0" (:native-wit-version coordinates)))
    (is (= "aiueos:capability/application@0.3.0"
           (:component-world coordinates)))))

(deftest artifact-tampering-invalidates-release
  (let [root (str "tmp/release-tamper-" (System/currentTimeMillis))
        seed (byte-array (repeat 32 (unchecked-byte 7)))]
    (try
      (let [evidence (release/build-evidence! root seed)]
        (spit (:artifact evidence) "tampered" :append true)
        (let [verified (release/verify-evidence evidence)]
          (is (false? (:valid? verified)))
          (is (false? (get-in verified [:checks :digest])))
          (is (false? (get-in verified [:checks :signature])))))
      (finally (delete-tree! root)))))
