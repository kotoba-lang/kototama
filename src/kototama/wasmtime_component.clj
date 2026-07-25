(ns kototama.wasmtime-component
  "Narrow native Wasmtime adapter for provider-free Kotoba Components.

  It intentionally exposes no WASI directories, environment, arguments, or
  inherited stdio. Effectful Components stay rejected until their typed WIT
  provider adapters are implemented; the CLI must never become an ambient
  authority escape hatch."
  (:require [clojure.string :as str]
            [kototama.component-platform :as platform])
  (:import [java.nio.file Files Path]
           [java.util.concurrent TimeUnit]))

(defn- reject [code message]
  (throw (ex-info message {:phase :wasmtime-component :kototama.component/code code})))

(defn- parse-i64 [text]
  (let [trimmed (str/trim text)]
    (try (Long/parseLong trimmed)
         (catch NumberFormatException _
           (reject :invalid-engine-output "Wasmtime did not return one i64 result")))))

(defn run-provider-free!
  "Execute a verified, provider-free Component's main export through the
  Wasmtime binary. REQUEST is the already-admitted linker payload plus
  :runtime :wasmtime-component; it is not an alternative admission path."
  [{:keys [runtime component-bytes imports abilities budgets]}]
  (when-not (= :wasmtime-component runtime)
    (reject :runtime-mismatch "Wasmtime Component adapter was not selected"))
  (when-not (bytes? component-bytes)
    (reject :invalid-component "Component bytes are required"))
  (when-not (and (empty? imports) (empty? abilities))
    (reject :provider-adapter-required
            "effectful Components require a typed in-process provider adapter"))
  (let [deadline-ms (long (or (:deadline-ms budgets) 30000))
        path (Files/createTempFile "kototama-component-" ".wasm"
                                   (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (Files/write path component-bytes (make-array java.nio.file.OpenOption 0))
      (let [process (.start (doto (ProcessBuilder.
                                   (into-array String ["wasmtime" "run" "--invoke" "main"
                                                       (.toString path)]))
                             (.redirectInput java.lang.ProcessBuilder$Redirect/PIPE)
                             (.redirectError java.lang.ProcessBuilder$Redirect/PIPE)
                             (.redirectOutput java.lang.ProcessBuilder$Redirect/PIPE)))]
        (when-not (.waitFor process deadline-ms TimeUnit/MILLISECONDS)
          (.destroyForcibly process)
          (reject :deadline-exceeded "Component exceeded its execution deadline"))
        (let [out (slurp (.getInputStream process))
              err (slurp (.getErrorStream process))]
          (if (zero? (.exitValue process))
            {:result (parse-i64 out) :runtime runtime}
            (reject :engine-failed (str "Wasmtime Component execution failed: " (str/trim err))))))
      (catch java.io.IOException _
        (reject :runtime-unavailable "Wasmtime Component runtime is unavailable"))
      (finally (Files/deleteIfExists path)))))

(defn admit-and-run-provider-free!
  "The concrete Component-first execution path for a pure Kotoba app.
  Identity/world validation happens before Wasmtime receives bytes; capability
  imports are rejected by `run-provider-free!`, so this path cannot silently
  acquire WASI or host authority."
  [world component-bytes]
  (platform/admit-and-link!
   world component-bytes
   #(run-provider-free! (assoc % :runtime :wasmtime-component))))
