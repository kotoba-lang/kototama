(ns kototama.fleet-exec
  "R3 edge: wire kototama.fleet run-loop-step → tender/run-report.

   execute-fn builds a tick executor that loads wasm bytes and runs under
   the lease's grants + fuel limit. Checkpoints after each successful step
   when a store is provided."
  (:require [clojure.java.io :as io]
            [kototama.contract :as contract]
            [kototama.fleet :as fleet]
            [kototama.fleet-store :as store]
            [kototama.tender :as tender]))

(defn load-wasm
  "Load wasm bytes from path or resource string."
  [path-or-bytes]
  (cond
    (bytes? path-or-bytes) path-or-bytes
    (string? path-or-bytes)
    (let [f (io/file path-or-bytes)]
      (if (.exists f)
        (with-open [in (io/input-stream f)]
          (.readAllBytes in))
        (with-open [in (io/input-stream (io/resource path-or-bytes))]
          (.readAllBytes in))))
    :else
    (throw (ex-info "fleet-exec: need path or bytes" {:got (type path-or-bytes)}))))

(defn make-execute
  "Return (fn tick -> run-report) for run-loop-step.

   opts:
     :wasm       path string or byte[]
     :grants     override lease grants (default from tick)
     :caps-extra extra HostCaps keys (e.g. :limits)
     :store      optional fleet-store for side-effect free (unused here)
     :fuel       override fuel (default from tick :fuel-limit)"
  [{:keys [wasm grants caps-extra fuel] :as opts}]
  (let [wasm-bytes (load-wasm wasm)]
    (fn [tick]
      (let [g (or grants (:kototama.fleet/grants tick) [])
            fuel' (or fuel (:kototama.fleet/fuel-limit tick) tender/default-fuel-limit)
            caps (contract/host-caps
                  (merge
                   {:grants g
                    :limits (merge
                             {}
                             (when (some #{:gen-keypair :sign} g)
                               {:allow-secret-imports? true})
                             (when (some #{:log-write} g)
                               {:allow-write-imports? true})
                             (when (some #{:http-post} g)
                               {:max-http-posts 8})
                             (when (some #{:llm-infer} g)
                               {:max-llm-infers 4})
                             (:limits caps-extra))}
                   (dissoc caps-extra :limits)))]
        (tender/run-report wasm-bytes g caps {:fuel fuel'})))))

(defn run-lease!
  "Drive N ticks (or until governor stops) for lease-id.

   opts:
     :wasm :store :max-ticks :grants :caps-extra :checkpoint-every (default 1)
     :execute  override make-execute result

   Returns {:registry :steps :last}."
  [registry lease-id {:keys [wasm store max-ticks grants caps-extra
                             checkpoint-every execute]
                      :or {max-ticks 10 checkpoint-every 1}}]
  (let [exec (or execute (make-execute {:wasm wasm :grants grants :caps-extra caps-extra}))
        steps (atom [])]
    (loop [reg registry
           n 0]
      (if (>= n max-ticks)
        {:registry reg :steps @steps :last (last @steps) :stopped :max-ticks}
        (let [step (fleet/run-loop-step reg lease-id exec)]
          (swap! steps conj (dissoc step :registry))
          (let [reg' (:registry step)]
            (when (and store (:ok? step) (pos? checkpoint-every)
                       (zero? (mod (inc n) checkpoint-every)))
              (store/save-checkpoint!
               reg' store
               {:key (str "lease-" lease-id "-t" (inc n))
                :lease-id lease-id}))
            (if-not (:ok? step)
              {:registry reg' :steps @steps :last step :stopped (:reason (:governor step)
                                                                   (:reason (:planned step)
                                                                            :execute-failed))}
              (recur reg' (inc n)))))))))

(defn bootstrap-and-run!
  "make-lease + register + run-lease! + final checkpoint.

   Returns full result map including :lease-id :path (final checkpoint)."
  [tenant guest wasm-path & {:keys [budget grants store max-ticks ttl-ms]
                             :or {max-ticks 3 ttl-ms 300000}}]
  (let [store (or store (store/default-store))
        lease (fleet/make-lease tenant guest
                                :budget budget
                                :grants (or grants [])
                                :ttl-ms ttl-ms)
        reg (fleet/register-lease (fleet/empty-registry) lease)
        id (:kototama.fleet/lease-id lease)
        result (run-lease! reg id {:wasm wasm-path
                                   :store store
                                   :max-ticks max-ticks
                                   :grants grants})
        final (store/save-checkpoint!
               (:registry result) store
               {:key (str "final-" id) :lease-id id})]
    (assoc result
           :lease-id id
           :checkpoint-path (:path final)
           :checkpoint-key (:key final))))
