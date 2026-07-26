(ns kototama.cli
  "Operational CLI for the kototama tender.

   Commands:
     doctor              — tender/browser maturity + import surface
     parity              — R2 browser/JVM import parity matrix
     lint <file.kotoba>  — emit-pitfall lint (no execution)
     inspect <file.wasm> — structural Wasm surface (no run)
     run <file.wasm> [--grant id …]  — run-report via tender
     help"
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [kototama.browser :as browser]
            [kototama.contract :as contract]
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
           :placement {:owner "kotoba-lang/fleet"
                       :gate "clojure -M:cli fleet-gate"}}))
  {:ok? true})

(defn cmd-parity []
  (pp/pprint (browser/r2-report))
  {:ok? true})

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
        ;; --grant is the ONLY source of requested capabilities: a guest's
        ;; own declared imports are never trusted as a stand-in for operator
        ;; consent (a guest could declare gen-keypair/http-post/log-write and
        ;; grant itself real access by asking for it). A host-free guest
        ;; (no imports) still runs fine with requested [] unchanged; a guest
        ;; that does need imports and got no --grant is denied below -- since
        ;; REQUESTED and CAPS' :grants are now always the same set, this
        ;; doesn't fire contract/validate-import-surface's :grants/missing
        ;; branch (requested - granted is always empty here); the actual
        ;; enforcement is Chicory's own instantiation-time failure to link
        ;; an import the guest declares but no HostFunction was wired for
        ;; (caught generically by tender/run-report's `catch Exception`) --
        ;; not silently self-granted either way.
        requested grants
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
            (println "kototama — .kotoba WASM runtime (tender)")
            (println "  Role: run guests emitted by kotoba (language). Compile elsewhere:")
            (println "        kotoba wasm emit cell.kotoba -o cell.wasm")
            (println "  Placement: use kotoba-lang/fleet")
            (println)
            (println "  doctor              tender/browser maturity snapshot")
            (println "  parity              R2 browser/JVM import matrix")
            (println "  lint <file.kotoba>  emit-pitfall lint (no compile)")
            (println "  inspect <file.wasm> structural surface")
            (println "  run <file.wasm> [--grant id …]   ← canonical execute")
            (println "  help")
            {:ok? true}))]
    (System/exit (if (:ok? result) 0 1))))
