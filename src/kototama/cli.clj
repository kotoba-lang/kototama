(ns kototama.cli
  "Operational CLI for kototama maturity R1.

   Commands:
     doctor              — maturity snapshot (R0–R3) + import surface
     parity              — R2 browser/JVM import parity matrix
     fleet-demo          — R3 lease→tick→checkpoint pure demo
     lint <file.kotoba>  — emit-pitfall lint (no execution)
     inspect <file.wasm> — structural Wasm surface (no run)
     run <file.wasm> [--grant id …]  — run-report via tender
     help"
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [kototama.browser :as browser]
            [kototama.contract :as contract]
            [kototama.fleet :as fleet]
            [kototama.guest :as guest]
            [kototama.tender :as tender])
  (:gen-class))

(defn- read-bytes [path]
  (with-open [in (io/input-stream path)]
    (.readAllBytes in)))

(defn- parse-grants [args]
  (loop [xs args
         grants []]
    (cond
      (empty? xs) grants
      (= "--grant" (first xs))
      (if-let [g (second xs)]
        (recur (nnext xs) (conj grants (keyword g)))
        grants)
      :else (recur (rest xs) grants))))

(defn cmd-doctor []
  (pp/pprint
   (merge (guest/maturity-report)
          {:r2 (browser/r2-report)
           :r3 (fleet/r3-report)}))
  {:ok? true})

(defn cmd-parity []
  (pp/pprint (browser/r2-report))
  {:ok? true})

(defn cmd-fleet-demo []
  (let [lease (fleet/make-lease "tenant-a" "guest/fact"
                                :budget {:fuel 100000 :ticks 3 :llm-infers 0 :http-posts 0}
                                :grants [])
        reg0 (fleet/register-lease (fleet/empty-registry) lease)
        execute (fn [_tick]
                  ;; synthetic run-report (no wasm) for pure demo
                  {:ok? true :result 120 :fuel-used 59 :limits {}})
        step1 (fleet/run-loop-step reg0 (:kototama.fleet/lease-id lease) execute)
        step2 (fleet/run-loop-step (:registry step1)
                                   (:kototama.fleet/lease-id lease)
                                   execute)
        cp (fleet/checkpoint (:registry step2) {:demo true})
        restored (fleet/restore cp)]
    (pp/pprint
     {:ok? true
      :lease-id (:kototama.fleet/lease-id lease)
      :step1-ok (:ok? step1)
      :step2-ok (:ok? step2)
      :budget-after (:kototama.fleet/budget (:lease step2))
      :checkpoint-schema (:kototama.fleet/checkpoint-schema cp)
      :restored-tenant-leases
      (count (fleet/tenant-leases restored "tenant-a"))})
    {:ok? true}))

(defn cmd-lint [path]
  (let [src (slurp path)
        report (guest/lint-kotoba-source src)]
    (pp/pprint (assoc report :path path))
    report))

(defn cmd-inspect [path]
  (let [info (tender/inspect-module (read-bytes path))]
    (pp/pprint (assoc info :path path))
    info))

(defn cmd-run [path args]
  (let [grants (parse-grants args)
        wasm (read-bytes path)
        info (tender/inspect-module wasm)
        ;; map import field names back to contract ids when possible
        requested (if (seq grants)
                    grants
                    ;; host-free if no imports; else require explicit --grant
                    (if (empty? (:import-names info))
                      []
                      (mapv (fn [field]
                              (or (some (fn [[id f]]
                                          (when (= f field) id))
                                        guest/wasm-field-by-import-id)
                                  (keyword field)))
                            (:import-names info))))
        caps (contract/host-caps
              {:grants requested
               :limits (cond-> {}
                         (some #{:gen-keypair :sign} requested)
                         (assoc :allow-secret-imports? true)
                         (some #{:log-write} requested)
                         (assoc :allow-write-imports? true)
                         (some #{:http-post} requested)
                         (assoc :max-http-posts 8)
                         (some #{:llm-infer} requested)
                         (assoc :max-llm-infers 4))})
        report (tender/run-report wasm requested caps)]
    (pp/pprint (assoc report
                      :path path
                      :inspect (select-keys info [:byte-count :has-main? :import-names :export-names])
                      :profile (guest/profile requested caps)))
    report))

(defn -main [& args]
  (let [[cmd & more] args
        result
        (case cmd
          "doctor" (cmd-doctor)
          "parity" (cmd-parity)
          "fleet-demo" (cmd-fleet-demo)
          "lint" (if-let [p (first more)]
                   (cmd-lint p)
                   (do (binding [*out* *err*]
                         (println "usage: lint <file.kotoba>"))
                       {:ok? false}))
          "inspect" (if-let [p (first more)]
                      (cmd-inspect p)
                      (do (binding [*out* *err*]
                            (println "usage: inspect <file.wasm>"))
                          {:ok? false}))
          "run" (if-let [p (first more)]
                  (cmd-run p (next more))
                  (do (binding [*out* *err*]
                        (println "usage: run <file.wasm> [--grant id …]"))
                      {:ok? false}))
          (do
            (println "kototama CLI (maturity R2 + R3 skeleton)")
            (println "  doctor              maturity snapshot R0–R3")
            (println "  parity              R2 browser/JVM import matrix")
            (println "  fleet-demo          R3 lease→tick→checkpoint demo")
            (println "  lint <file.kotoba>  emit-pitfall lint")
            (println "  inspect <file.wasm> structural surface")
            (println "  run <file.wasm> [--grant id …]")
            (println "  help")
            {:ok? true}))]
    (System/exit (if (:ok? result) 0 1))))
