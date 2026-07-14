(ns wasm.prior-shortcut-test
  "Hosts wasm/prior_shortcut.wasm (compiled from wasm/prior_shortcut.kotoba,
  see wasm/README.md) via a real Chicory Instance -- proves
  kototama.unspsc.life/prior-shortcut? runs as a real WASM guest, not just
  as JVM Clojure. ADR-2607151500's kotoba-lang-org-internal narrow-slice
  porting candidate: the first candidate found
  (kototama.unspsc.capability's segment-capabilities table) turned out to
  store predicate closures as data and was abandoned; prior-shortcut? has
  no such dependency, is a genuinely pure integer/boolean decision, and
  (unlike the abandoned candidate) has a real call site today
  (kototama.unspsc.organism).

  Every oracle case below is copied from kototama.unspsc.life-test's own
  prior-consensus-parity test (life_test.clj), translated from a single
  `consensus` map argument into 4 flattened positional scalars (the port's
  one real adaptation -- .kotoba has no string-equality op in its safe
  subset, so `(:dominant-status consensus)` = \"authorized\" is pushed to
  the CALLER as a precomputed 0/1 flag; see prior_shortcut.kotoba's own
  ns docstring).

  ABI: main is 0-arity (kotoba wasm emit rejects a parameterized main);
  the 4 real i32 inputs are written into the guest's exported linear
  memory at fixed offsets before calling main() -- see
  wasm/prior_shortcut.kotoba's ns docstring for the offset layout."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]])
  (:import (com.dylibso.chicory.runtime Instance)
           (com.dylibso.chicory.wasm Parser)))

(defn- wasm-bytes []
  (.readAllBytes (io/input-stream (io/file "wasm/prior_shortcut.wasm"))))

(defn- run-prior-shortcut? [outcome-count confidence-permille input-match-count authorized]
  (let [module (Parser/parse (wasm-bytes))
        instance (-> (Instance/builder module) (.build))
        memory (.memory instance)]
    (.writeI32 memory 0 outcome-count)
    (.writeI32 memory 4 confidence-permille)
    (.writeI32 memory 8 input-match-count)
    (.writeI32 memory 12 authorized)
    (aget (.apply (.export instance "main") (long-array 0)) 0)))

(deftest prior-shortcut-wasm-matches-life-test-oracle-a
  (testing "mixed-status priors, confidence below the 800/1000 floor -> no shortcut"
    (is (= 0 (run-prior-shortcut? 3 666 2 1)))))

(deftest prior-shortcut-wasm-matches-life-test-oracle-b
  (testing "empty priors, nothing dominant -> no shortcut"
    (is (= 0 (run-prior-shortcut? 0 0 0 0)))))

(deftest prior-shortcut-wasm-matches-life-test-all-authorized
  (testing "all-authorized matching priors at full confidence -> shortcut fires"
    (is (= 1 (run-prior-shortcut? 3 1000 3 1)))))

(deftest prior-shortcut-wasm-threshold-boundaries
  (testing "exactly at every threshold -> shortcut fires"
    (is (= 1 (run-prior-shortcut? 3 800 1 1))))
  (testing "just under the confidence floor -> no shortcut"
    (is (= 0 (run-prior-shortcut? 3 799 1 1))))
  (testing "just under the outcome-count floor -> no shortcut"
    (is (= 0 (run-prior-shortcut? 2 1000 5 1))))
  (testing "just under the input-match-count floor -> no shortcut"
    (is (= 0 (run-prior-shortcut? 5 1000 0 1))))
  (testing "every other signal strong but not authorized -> no shortcut"
    (is (= 0 (run-prior-shortcut? 10 1000 10 0)))))
