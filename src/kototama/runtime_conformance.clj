(ns kototama.runtime-conformance
  "Differential execution against Chicory and independent Wasmtime/Node
   engines. This is qualification code; it never participates in admission."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [kototama.contract :as contract]
            [kototama.tender :as tender]))

(defn chicory-main [wasm-bytes]
  (try
    {:ok? true
     :result (tender/run-main wasm-bytes [] (contract/host-caps {}))}
    (catch Exception e
      {:ok? false :error-class (.getName (class e))
       :message (.getMessage e)})))

(defn wasmtime-main [path]
  (let [{:keys [exit out err]}
        (shell/sh "wasmtime" "run" "--invoke" "main" (str path))]
    (if (zero? exit)
      {:ok? true :result (Long/parseLong (str/trim out))}
      {:ok? false :exit exit :message (str/trim err)})))

(def node-program
  "const fs=require('fs'); WebAssembly.instantiate(fs.readFileSync(process.argv[1]),{}).then(x=>{console.log(String(x.instance.exports.main()));}).catch(e=>{console.error(e.name+': '+e.message);process.exit(1);});")

(defn node-main [path]
  (let [{:keys [exit out err]} (shell/sh "node" "-e" node-program (str path))]
    (if (zero? exit)
      {:ok? true :result (Long/parseLong (str/trim out))}
      {:ok? false :exit exit :message (str/trim err)})))

(defn compare-main
  "Run one host-free module in all three engines and compare success/result or
   common trap behavior."
  [path]
  (let [bytes (java.nio.file.Files/readAllBytes
               (.toPath (java.io.File. (str path))))
        results {:chicory (chicory-main bytes)
                 :wasmtime (wasmtime-main path)
                 :node (node-main path)}
        success? (every? :ok? (vals results))
        values (set (map :result (vals results)))
        traps? (every? (comp not :ok?) (vals results))]
    {:ok? (or (and success? (= 1 (count values))) traps?)
     :mode (cond success? :result traps? :trap :else :divergence)
     :results results}))
