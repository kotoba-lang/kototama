(ns kototama.fleet-exec
  "R3 edge: wire kototama.fleet run-loop-step → tender/run-report.

   execute-fn builds a tick executor that loads wasm bytes and runs under
   the lease's grants + fuel limit. Checkpoints after each successful step
   when a store is provided."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
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

   Stores :wasm path in lease meta so resume-from-checkpoint! can recover.
   Returns full result map including :lease-id :path (final checkpoint)."
  [tenant guest wasm-path & {:keys [budget grants store max-ticks ttl-ms]
                             :or {max-ticks 3 ttl-ms 300000}}]
  (let [store (or store (store/default-store))
        lease (fleet/make-lease tenant guest
                                :budget budget
                                :grants (or grants [])
                                :ttl-ms ttl-ms
                                :meta {:wasm (str wasm-path)
                                       :guest (str guest)
                                       :tenant (str tenant)})
        reg (fleet/register-lease (fleet/empty-registry) lease)
        id (:kototama.fleet/lease-id lease)
        result (run-lease! reg id {:wasm wasm-path
                                   :store store
                                   :max-ticks max-ticks
                                   :grants grants})
        final (store/save-checkpoint!
               (:registry result) store
               {:key (str "final-" id)
                :lease-id id
                :wasm (str wasm-path)})]
    (assoc result
           :lease-id id
           :checkpoint-path (:path final)
           :checkpoint-key (:key final)
           :wasm (str wasm-path))))

(defn- lease-wasm
  "Resolve wasm path from lease meta or explicit override."
  [lease wasm-override]
  (or wasm-override
      (get-in lease [:kototama.fleet/meta :wasm])
      (throw (ex-info "fleet-exec: no wasm path on lease meta; pass :wasm"
                      {:lease-id (:kototama.fleet/lease-id lease)
                       :meta (:kototama.fleet/meta lease)}))))

(defn resume-lease!
  "Continue an existing lease from a restored registry.

   opts: :wasm (override) :store :max-ticks :grants :caps-extra :execute"
  [registry lease-id {:keys [wasm store max-ticks grants caps-extra execute]
                      :or {max-ticks 10}
                      :as opts}]
  (let [lease (fleet/get-lease registry lease-id)
        _ (when-not lease
            (throw (ex-info "fleet-exec: unknown lease" {:lease-id lease-id})))
        wasm-path (lease-wasm lease wasm)
        result (run-lease! registry lease-id
                           (merge opts
                                  {:wasm wasm-path
                                   :store store
                                   :max-ticks max-ticks
                                   :grants (or grants (:kototama.fleet/grants lease))
                                   :caps-extra caps-extra
                                   :execute execute}))
        final (when store
                (store/save-checkpoint!
                 (:registry result) store
                 {:key (str "resume-" lease-id "-" (System/currentTimeMillis))
                  :lease-id lease-id
                  :wasm (str wasm-path)}))]
    (cond-> (assoc result :lease-id lease-id :wasm (str wasm-path) :resumed? true)
      final (assoc :checkpoint-path (:path final) :checkpoint-key (:key final)))))

(defn resume-from-checkpoint!
  "Load checkpoint key from store, resume every active lease (or :lease-id).

   opts:
     :store :wasm (global override) :max-ticks :lease-id (single)
     :grants :caps-extra

   Returns {:registry :resumes [result…] :active-before n}."
  [checkpoint-key & {:keys [store wasm max-ticks lease-id grants caps-extra]
                     :or {max-ticks 5}
                     :as opts}]
  (let [store (or store (store/default-store))
        reg (store/load-checkpoint! checkpoint-key store)
        _ (when-not reg
            (throw (ex-info "fleet-exec: checkpoint not found"
                            {:key checkpoint-key})))
        targets (if lease-id
                  (if-let [l (fleet/get-lease reg lease-id)]
                    [l]
                    (throw (ex-info "fleet-exec: lease not in checkpoint"
                                    {:lease-id lease-id :key checkpoint-key})))
                  (fleet/active-leases reg))
        resumes (mapv (fn [lease]
                        (resume-lease! reg
                                       (:kototama.fleet/lease-id lease)
                                       {:wasm wasm
                                        :store store
                                        :max-ticks max-ticks
                                        :grants grants
                                        :caps-extra caps-extra}))
                      targets)
        reg' (or (some-> resumes last :registry) reg)]
    {:checkpoint-key checkpoint-key
     :active-before (count targets)
     :resumes resumes
     :registry reg'
     :ok? (or (empty? targets)
              (boolean (some :ok? (map :last resumes))))}))

(defn recovery-pass!
  "One recovery pass: scan disk keys (or provided keys), resume active leases.

   Not a long-running daemon — call in a loop/cron. Bounded by :max-keys and
   :max-ticks per lease.

   opts:
     :store :wasm :max-keys :max-ticks :key-filter (fn [k] bool)"
  [& {:keys [store wasm max-keys max-ticks key-filter]
      :or {max-keys 20 max-ticks 3
           key-filter (fn [k]
                        (or (str/starts-with? (str k) "final-")
                            (str/starts-with? (str k) "resume-")
                            (str/starts-with? (str k) "lease-")))}}]
  (let [store (or store (store/disk-store))
        keys (->> (if (= :disk (:kind store))
                    (store/list-checkpoint-keys store)
                    (store/list-disk-checkpoint-keys
                     (or (:root store) "tmp/kototama-fleet")))
                  (filter key-filter)
                  (take max-keys)
                  vec)
        results (mapv (fn [k]
                        (try
                          (assoc (resume-from-checkpoint! k
                                                          :store store
                                                          :wasm wasm
                                                          :max-ticks max-ticks)
                                 :key k)
                          (catch Exception e
                            {:key k :ok? false :error (.getMessage e)})))
                      keys)]
    {:keys keys
     :results results
     :ok-count (count (filter :ok? results))
     :fail-count (count (remove :ok? results))}))
