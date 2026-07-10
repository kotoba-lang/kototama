(ns kototama.fleet-exec
  "R3 edge: wire kototama.fleet run-loop-step → tender/run-report.

   execute-fn builds a tick executor that loads wasm bytes and runs under
   the lease's grants + fuel limit. Checkpoints after each successful step
   when a store is provided."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [kototama.aiueos-adapter :as aiueos]
            [kototama.contract :as contract]
            [kototama.fleet :as fleet]
            [kototama.fleet-fence :as fence]
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

;; ── aiueos-gated grants (optional) ──────────────────────────────────────────

(def aiueos-translatable-imports
  "Subset host-caps-for-imports can decide (see aiueos-adapter)."
  #{:log-write :clock-monotonic})

(defn resolve-grants
  "Resolve HostCaps grants for a lease.

   If :use-aiueos? and imports intersect aiueos-translatable-imports,
   ask aiueos; on deny → empty grants (fail-closed). Explicit :grants
   always win when :use-aiueos? is false."
  [import-ids {:keys [use-aiueos? grants limits trust]
               :or {use-aiueos? false trust :verified}}]
  (let [ids (vec (or grants import-ids))]
    (if-not use-aiueos?
      {:grants ids :source :explicit :host-caps (contract/host-caps {:grants ids :limits limits})}
      (let [translatable (filterv aiueos-translatable-imports ids)
            rest-ids (vec (remove aiueos-translatable-imports ids))]
        (if (empty? translatable)
          {:grants ids :source :explicit-no-aiueos-overlap
           :host-caps (contract/host-caps {:grants ids :limits limits})}
          (let [{:keys [host-caps decision]}
                (aiueos/host-caps-for-imports translatable
                                              {:trust trust :limits limits})
                granted (set (:grants host-caps))
                ;; fail-closed: only what aiueos granted + non-translatable explicit
                final (vec (concat (filter granted translatable) rest-ids))]
            {:grants final
             :source :aiueos
             :decision decision
             :host-caps (contract/host-caps {:grants final :limits limits})}))))))


(defn- lease-wasm
  "Resolve wasm path from lease meta or explicit override."
  [lease wasm-override]
  (or wasm-override
      (get-in lease [:kototama.fleet/meta :wasm])
      (throw (ex-info "fleet-exec: no wasm path on lease meta; pass :wasm"
                      {:lease-id (:kototama.fleet/lease-id lease)
                       :meta (:kototama.fleet/meta lease)}))))

(defn load-merged-registry
  "Load and fence-merge registries from multiple checkpoint keys.
   Empty keys → empty registry."
  [keys store]
  (reduce (fn [acc k]
            (if-let [reg (store/load-checkpoint! k store)]
              (fence/merge-registries acc reg)
              acc))
          (fleet/empty-registry)
          keys))

(defn claim-before-run
  "Fence-claim LEASE as NODE-ID on REGISTRY before tender runs.

   Returns {:ok? true :registry :lease :claim} or {:ok? false :reason :holder}."
  [registry lease node-id]
  (let [node (str (or node-id (fence/node-id)))
        claim (fence/claim-lease registry lease node)]
    (if (:ok? claim)
      {:ok? true
       :registry (:registry claim)
       :lease (:lease claim)
       :claim claim
       :node-id node}
      {:ok? false
       :reason (:reason claim)
       :holder (:holder claim)
       :registry registry
       :node-id node})))

(defn bootstrap-and-run!
  "make-lease + fence-claim + run-lease! + final checkpoint.

   Stores :wasm path in lease meta so resume-from-checkpoint! can recover.
   Optional :use-aiueos? routes grants through aiueos-adapter (fail-closed).
   :node-id defaults to fence/node-id. :fence? (default true) enables claim.
   Returns full result map including :lease-id :path (final checkpoint)."
  [tenant guest wasm-path & {:keys [budget grants store max-ticks ttl-ms
                                    use-aiueos? limits trust node-id fence?]
                             :or {max-ticks 3 ttl-ms 300000 use-aiueos? false
                                  trust :verified fence? true}}]
  (let [store (or store (store/default-store))
        node (str (or node-id (fence/node-id)))
        resolved (resolve-grants (or grants [])
                                 {:use-aiueos? use-aiueos?
                                  :grants grants
                                  :limits limits
                                  :trust trust})
        g (:grants resolved)
        lease0 (fleet/make-lease tenant guest
                                 :budget budget
                                 :grants g
                                 :ttl-ms ttl-ms
                                 :owner node
                                 :meta {:wasm (str wasm-path)
                                        :guest (str guest)
                                        :tenant (str tenant)
                                        :grant-source (:source resolved)})
        ;; Seed registry from existing disk checkpoints (shared store multi-node)
        prior-keys (try (store/list-checkpoint-keys store) (catch Exception _ []))
        prior (load-merged-registry (take 50 prior-keys) store)
        claimed (if fence?
                  (claim-before-run prior lease0 node)
                  {:ok? true
                   :registry (fleet/register-lease prior lease0)
                   :lease lease0
                   :node-id node})
        _ (when-not (:ok? claimed)
            (throw (ex-info "fleet-exec: fence claim refused at bootstrap"
                            {:reason (:reason claimed)
                             :holder (:holder claimed)
                             :node-id node})))
        lease (:lease claimed)
        reg (:registry claimed)
        id (:kototama.fleet/lease-id lease)
        result (run-lease! reg id {:wasm wasm-path
                                   :store store
                                   :max-ticks max-ticks
                                   :grants g})
        final (store/save-checkpoint!
               (:registry result) store
               {:key (str "final-" id)
                :lease-id id
                :wasm (str wasm-path)
                :node-id node})]
    (assoc result
           :lease-id id
           :checkpoint-path (:path final)
           :checkpoint-key (:key final)
           :wasm (str wasm-path)
           :grant-source (:source resolved)
           :aiueos-decision (:decision resolved)
           :fenced? (boolean fence?)
           :node-id node
           :claim-reason (get-in claimed [:claim :reason] :no-fence))))

(defn resume-lease!
  "Continue an existing lease from a restored registry.

   Fence-claims as :node-id before tender. Skip (no throw) when held-by-other
   if :skip-if-held? true (default true for recovery paths).

   opts: :wasm :store :max-ticks :grants :caps-extra :execute
         :node-id :fence? (default true) :skip-if-held? (default true)"
  [registry lease-id {:keys [wasm store max-ticks grants caps-extra execute
                             node-id fence? skip-if-held?]
                      :or {max-ticks 10 fence? true skip-if-held? true}
                      :as opts}]
  (let [lease (fleet/get-lease registry lease-id)
        _ (when-not lease
            (throw (ex-info "fleet-exec: unknown lease" {:lease-id lease-id})))
        node (str (or node-id (fence/node-id)))
        wasm-path (lease-wasm lease wasm)
        claimed (if fence?
                  (claim-before-run registry lease node)
                  {:ok? true :registry registry :lease lease :node-id node})]
    (if-not (:ok? claimed)
      (if skip-if-held?
        {:registry registry
         :lease-id lease-id
         :wasm (str wasm-path)
         :resumed? false
         :skipped? true
         :skip-reason (:reason claimed)
         :holder (:holder claimed)
         :node-id node
         :ok? false
         :last {:ok? false :reason (:reason claimed)}}
        (throw (ex-info "fleet-exec: fence claim refused on resume"
                        {:reason (:reason claimed)
                         :holder (:holder claimed)
                         :lease-id lease-id})))
      (let [reg (:registry claimed)
            lease' (:lease claimed)
            id (:kototama.fleet/lease-id lease')
            result (run-lease! reg id
                               (merge opts
                                      {:wasm wasm-path
                                       :store store
                                       :max-ticks max-ticks
                                       :grants (or grants (:kototama.fleet/grants lease'))
                                       :caps-extra caps-extra
                                       :execute execute}))
            final (when store
                    (store/save-checkpoint!
                     (:registry result) store
                     {:key (str "resume-" id "-" (System/currentTimeMillis))
                      :lease-id id
                      :wasm (str wasm-path)
                      :node-id node}))]
        (cond-> (assoc result
                       :lease-id id
                       :wasm (str wasm-path)
                       :resumed? true
                       :skipped? false
                       :node-id node
                       :claim-reason (get-in claimed [:claim :reason] :no-fence)
                       :fenced? (boolean fence?))
          final (assoc :checkpoint-path (:path final) :checkpoint-key (:key final)))))))

(defn resume-from-checkpoint!
  "Load checkpoint key from store, fence-claim + resume active leases.

   opts:
     :store :wasm :max-ticks :lease-id :grants :caps-extra
     :node-id :fence? :skip-if-held?

   Returns {:registry :resumes :active-before :skipped-count}."
  [checkpoint-key & {:keys [store wasm max-ticks lease-id grants caps-extra
                            node-id fence? skip-if-held?]
                     :or {max-ticks 5 fence? true skip-if-held? true}
                     :as opts}]
  (let [store (or store (store/default-store))
        node (str (or node-id (fence/node-id)))
        ;; Merge this checkpoint with other keys on the store for multi-node view
        keys (try (store/list-checkpoint-keys store) (catch Exception _ []))
        merged (load-merged-registry
                (distinct (cons checkpoint-key (or keys [])))
                store)
        reg (or (store/load-checkpoint! checkpoint-key store)
                (throw (ex-info "fleet-exec: checkpoint not found"
                                {:key checkpoint-key})))
        ;; Prefer merged world for fencing, but targets from primary checkpoint
        fence-reg (if (seq (fleet/all-leases merged)) merged reg)
        targets (if lease-id
                  (if-let [l (or (fleet/get-lease fence-reg lease-id)
                                 (fleet/get-lease reg lease-id))]
                    [l]
                    (throw (ex-info "fleet-exec: lease not in checkpoint"
                                    {:lease-id lease-id :key checkpoint-key})))
                  (fleet/active-leases fence-reg))
        resumes (mapv (fn [lease]
                        (resume-lease! fence-reg
                                       (:kototama.fleet/lease-id lease)
                                       {:wasm wasm
                                        :store store
                                        :max-ticks max-ticks
                                        :grants grants
                                        :caps-extra caps-extra
                                        :node-id node
                                        :fence? fence?
                                        :skip-if-held? skip-if-held?}))
                      targets)
        ran (filterv (complement :skipped?) resumes)
        skipped (filterv :skipped? resumes)
        reg' (or (some-> (last ran) :registry) fence-reg)]
    {:checkpoint-key checkpoint-key
     :active-before (count targets)
     :resumes resumes
     :ran-count (count ran)
     :skipped-count (count skipped)
     :registry reg'
     :node-id node
     :fenced? (boolean fence?)
     :ok? (or (empty? targets)
              (boolean (some #(get-in % [:last :ok?]) ran))
              (and (seq skipped) (empty? ran)))}))

(defn recovery-pass!
  "One recovery pass: merge store view, fence-claim, resume only if we hold.

   opts:
     :store :wasm :max-keys :max-ticks :key-filter :node-id :fence?"
  [& {:keys [store wasm max-keys max-ticks key-filter node-id fence?]
      :or {max-keys 20 max-ticks 3 fence? true
           key-filter (fn [k]
                        (or (str/starts-with? (str k) "final-")
                            (str/starts-with? (str k) "resume-")
                            (str/starts-with? (str k) "lease-")))}}]
  (let [store (or store (store/disk-store))
        node (str (or node-id (fence/node-id)))
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
                                                          :max-ticks max-ticks
                                                          :node-id node
                                                          :fence? fence?)
                                 :key k)
                          (catch Exception e
                            {:key k :ok? false :error (.getMessage e)})))
                      keys)]
    {:keys keys
     :results results
     :ok-count (count (filter :ok? results))
     :fail-count (count (remove :ok? results))
     :skipped-total (reduce + 0 (map #(or (:skipped-count %) 0) results))
     :node-id node
     :fenced? (boolean fence?)
     :at (System/currentTimeMillis)}))

;; ── recovery daemon (bounded multi-pass) ────────────────────────────────────

(defn daemon-config
  "Normalize daemon options. Never infinite by default (max-passes required
   or defaults to 3). interval-ms is sleep between passes."
  [{:keys [interval-ms max-passes max-keys max-ticks stop? continue-on-error?]
    :or {interval-ms 1000
         max-passes 3
         max-keys 20
         max-ticks 2
         continue-on-error? true}}]
  {:interval-ms (max 0 (long interval-ms))
   :max-passes (max 1 (long max-passes))
   :max-keys (max 1 (long max-keys))
   :max-ticks (max 1 (long max-ticks))
   :stop? (or stop? (constantly false))
   :continue-on-error? (boolean continue-on-error?)})

(defn run-daemon!
  "Run up to :max-passes recovery-pass! iterations with sleep between them.

   This is the long-running recovery *loop* (cron/service can wrap it).
   Always bounded — never an open-ended while-true without max-passes.

   opts:
     :store :wasm :interval-ms :max-passes :max-keys :max-ticks
     :stop? (fn [pass-index last-pass-result] bool)
     :continue-on-error? (default true)
     :sleep-fn (fn [ms] ...) for tests (default Thread/sleep)
     :pass-fn  override recovery-pass! for tests

   Returns {:passes [pass…] :stopped :max-passes|:stop?|:error :ok-count :fail-count}."
  [& {:keys [store wasm interval-ms max-passes max-keys max-ticks
             stop? continue-on-error? sleep-fn pass-fn]
      :as opts}]
  (let [cfg (daemon-config opts)
        sleep! (or sleep-fn (fn [ms]
                              (when (pos? ms)
                                (Thread/sleep (long ms)))))
        pass! (or pass-fn
                  (fn []
                    (recovery-pass! :store (or store (store/disk-store))
                                    :wasm wasm
                                    :max-keys (:max-keys cfg)
                                    :max-ticks (:max-ticks cfg))))
        stop?-fn (:stop? cfg)]
    (loop [i 0
           passes []
           stop-reason nil]
      (cond
        stop-reason
        {:passes passes
         :stopped stop-reason
         :ok-count (reduce + 0 (map :ok-count passes))
         :fail-count (reduce + 0 (map :fail-count passes))
         :pass-count (count passes)}

        (>= i (:max-passes cfg))
        {:passes passes
         :stopped :max-passes
         :ok-count (reduce + 0 (map :ok-count passes))
         :fail-count (reduce + 0 (map :fail-count passes))
         :pass-count (count passes)}

        (and (seq passes) (stop?-fn i (last passes)))
        (recur i passes :stop?)

        :else
        (let [pass (try
                     (pass!)
                     (catch Exception e
                       {:ok-count 0 :fail-count 1 :error (.getMessage e) :ok? false}))]
          (if (and (:error pass) (not (:continue-on-error? cfg)))
            (recur (inc i) (conj passes pass) :error)
            (do
              (when (< (inc i) (:max-passes cfg))
                (sleep! (:interval-ms cfg)))
              (recur (inc i) (conj passes pass) nil))))))))
