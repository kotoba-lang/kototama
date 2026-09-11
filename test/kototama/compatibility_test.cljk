(ns kototama.compatibility-test
  (:require [clojure.test :refer [deftest is testing]]
            [kototama.compatibility :as compatibility]))

(defn- uleb [value]
  (loop [value value out []]
    (let [part (bit-and value 0x7f) next (unsigned-bit-shift-right value 7)]
      (if (zero? next) (conj out part) (recur next (conj out (bit-or part 0x80)))))))
(defn- text [value]
  (let [bytes (mapv #(bit-and 0xff %) (.getBytes ^String value "UTF-8"))]
    (into (uleb (count bytes)) bytes)))
(defn- module [overrides]
  (let [d (merge compatibility/supported
                 {:kir "kotoba.kir/v3" :target "wasm32-kotoba-v1"
                  :value-abi "kotoba.i64/direct-v1"} overrides)
        payload (vec (concat (text compatibility/section-name) [(:version d)]
                             (mapcat (comp text d)
                                     [:compiler :language :kir :target :runtime :value-abi
                                      :tender-role :tender-contract])))]
    (byte-array (map unchecked-byte
                     (concat [0 97 115 109 1 0 0 0 0] (uleb (count payload)) payload)))))
(defn- code [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:kototama.compatibility/code (ex-data e)))))

(deftest compatibility-admission-is-fail-closed
  (is (= "kotoba-compiler/1" (:compiler (compatibility/validate! (module {}) true))))
  (testing "metadata is mandatory for declared Kotoba artifacts"
    (is (= :missing (code #(compatibility/validate! (byte-array [0 97 115 109 1 0 0 0]) true)))))
  (testing "runtime and target substitution are rejected"
    (is (= :mismatch (code #(compatibility/validate! (module {:runtime "kotoba-browser-host-v1"}) true))))
    (is (= :mismatch (code #(compatibility/validate! (module {:target "wasm32-wasi-kotoba-v1"}) true))))))
