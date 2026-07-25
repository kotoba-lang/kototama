(ns kototama.runtime-conformance-test
  (:require [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]
            [kototama.runtime-conformance :as conformance]))

(def host-free-corpus
  ["test/kototama/fixtures/kotoba-compiled-fact.wasm"
   "test/kototama/fixtures/kotoba-compiled-peak-cells.wasm"
   "web/demo.wasm"])

(defn wat-file [wat]
  (let [wat-file (java.io.File/createTempFile "kototama-differential" ".wat")
        wasm-file (java.io.File/createTempFile "kototama-differential" ".wasm")]
    (spit wat-file wat)
    (let [{:keys [exit err]}
          (shell/sh "wasm-tools" "parse" (.getPath wat-file)
                    "-o" (.getPath wasm-file))]
      (.delete wat-file)
      (when-not (zero? exit)
        (throw (ex-info "wasm-tools parse failed" {:error err})))
      wasm-file)))

(deftest production-host-free-corpus-agrees-across-independent-runtimes
  (doseq [path host-free-corpus]
    (let [result (conformance/compare-main path)]
      (is (:ok? result) (str path " diverged: " (pr-str result)))
      (is (= :result (:mode result)))
      (is (= 1 (count (set (map :result (vals (:results result))))))))))

(deftest arithmetic-memory-and-trap-semantics-agree
  (doseq [[label wat expected-mode expected]
          [[:memory-grow
            "(module (memory 1 2)
               (func (export \"main\") (result i64)
                 (i64.extend_i32_s (memory.grow (i32.const 1)))))"
            :result 1]
           [:signed-arithmetic
            "(module
               (func (export \"main\") (result i64)
                 (i64.div_s (i64.const -84) (i64.const 2))))"
            :result -42]
           [:division-trap
            "(module
               (func (export \"main\") (result i64)
                 (i64.div_s (i64.const 1) (i64.const 0))))"
            :trap nil]]]
    (testing (name label)
      (let [file (wat-file wat)]
        (try
          (let [result (conformance/compare-main (.getPath file))]
            (is (:ok? result) (pr-str result))
            (is (= expected-mode (:mode result)))
            (when (= :result expected-mode)
              (is (= #{expected}
                     (set (map :result (vals (:results result))))))))
          (finally
            (.delete file)))))))
