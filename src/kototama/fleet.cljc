(ns kototama.fleet
  "R3 advanced-partial: multi-tenant durable outer loop for kototama tender.

   Pure data — no threads, no network. Models the durable loop described in
   the actor design (lease / tick / budget / governor / crash recovery):

     1 tick = 1 bounded guest run (call-main or run-report)
     lease  = who may run which guest until when
     budget = remaining fuel / llm / http quota for a tenant
     checkpoint = EDN-serializable recovery snapshot

   Execution still calls into kototama.tender on the JVM host; this ns only
   owns the *scheduling and recovery contract*. Not Raft consensus — see
   docs/maturity.md R3."
  (:require [clojure.string :as str]))

;; ── time helpers (injectable clock for tests) ───────────────────────────────

(def ^:dynamic *now-ms*
  "Override wall clock in tests: (binding [fleet/*now-ms* 0] …)."
  nil)

(defn now-ms
  "Wall clock ms. Honors *now-ms* binding when set."
  []
  (or *now-ms*
      #?(:clj (System/currentTimeMillis)
         :cljs (.now js/Date))))

(defn- clock []
  (now-ms))

;; ── budget ──────────────────────────────────────────────────────────────────

(def default-budget
  {:fuel 5000000
   :llm-infers 0
   :http-posts 0
   :ticks 100})

(defn budget
  "Overlay m on default-budget; all values non-negative longs."
  ([] default-budget)
  ([m]
   (merge-with (fn [_ b] (max 0 (long (or b 0))))
               default-budget
               (select-keys (or m {}) (keys default-budget)))))

(defn budget-remaining?
  "True when every dimension still has positive remaining (ticks/fuel must
   be >0; llm/http may be 0 if unused)."
  [b]
  (and (pos? (long (:fuel b 0)))
       (pos? (long (:ticks b 0)))))

(defn charge-budget
  "Subtract usage map from budget. Clamps at 0. Returns new budget."
  [b usage]
  (let [b (budget b)
        u (or usage {})]
    (budget
     {:fuel (- (:fuel b) (long (or (:fuel u) 0)))
      :llm-infers (- (:llm-infers b) (long (or (:llm-infers u) 0)))
      :http-posts (- (:http-posts b) (long (or (:http-posts u) 0)))
      :ticks (- (:ticks b) (long (or (:ticks u) 1)))})))

;; ── lease ───────────────────────────────────────────────────────────────────

(defn make-lease
  "Create a lease for TENANT-ID over GUEST-ID.

   opts:
     :ttl-ms     lease duration (default 60000)
     :owner      runner id (string)
     :budget     initial budget map
     :grants     import ids the tender may wire
     :meta       free-form map"
  [tenant-id guest-id & {:keys [ttl-ms owner grants meta]
                         :or {ttl-ms 60000 owner "local" grants [] meta {}}
                         :as opts}]
  (let [t (clock)
        exp (+ t (long ttl-ms))]
    {:kototama.fleet/lease-id (str tenant-id "/" guest-id "/" t)
     :kototama.fleet/tenant tenant-id
     :kototama.fleet/guest guest-id
     :kototama.fleet/owner owner
     :kototama.fleet/issued-at t
     :kototama.fleet/expires-at exp
     ;; (:budget opts) — do not bind `budget` as a local (shadows budget fn)
     :kototama.fleet/budget (budget (:budget opts))
     :kototama.fleet/grants (vec grants)
     :kototama.fleet/meta meta
     :kototama.fleet/epoch (long (or (:epoch opts) 1))
     :kototama.fleet/status :active}))

(defn lease-expired?
  ([lease] (lease-expired? lease (clock)))
  ([lease now]
   (or (= :expired (:kototama.fleet/status lease))
       (>= (long now) (long (:kototama.fleet/expires-at lease))))))

(defn renew-lease
  "Extend expires-at by ttl-ms if still active and not expired."
  ([lease] (renew-lease lease 60000))
  ([lease ttl-ms]
   (let [now (clock)]
     (if (lease-expired? lease now)
       (assoc lease :kototama.fleet/status :expired)
       (assoc lease
              :kototama.fleet/expires-at (+ now (long ttl-ms))
              :kototama.fleet/status :active)))))

(defn expire-lease [lease]
  (assoc lease :kototama.fleet/status :expired))

;; ── tick (one bounded run descriptor) ───────────────────────────────────────

(defn plan-tick
  "Plan one tick under LEASE. Returns
   {:ok? true :tick {...}} or {:ok? false :reason keyword}."
  [lease]
  (cond
    (lease-expired? lease)
    {:ok? false :reason :lease-expired}

    (not (budget-remaining? (:kototama.fleet/budget lease)))
    {:ok? false :reason :budget-exhausted}

    :else
    {:ok? true
     :tick {:kototama.fleet/tick-id (str (:kototama.fleet/lease-id lease)
                                         "/t"
                                         (:ticks (:kototama.fleet/budget lease)))
            :kototama.fleet/tenant (:kototama.fleet/tenant lease)
            :kototama.fleet/guest (:kototama.fleet/guest lease)
            :kototama.fleet/grants (:kototama.fleet/grants lease)
            :kototama.fleet/fuel-limit (:fuel (:kototama.fleet/budget lease))
            :kototama.fleet/planned-at (clock)}}))

(defn apply-tick-result
  "Fold a run-report-like RESULT into LEASE.

   RESULT keys (optional): :ok? :fuel-used :limits {:llm-infers :http-posts}
   On success charges budget; on failure still charges 1 tick (attempt cost)."
  [lease result]
  (let [usage {:fuel (long (or (:fuel-used result) 0))
               :llm-infers (long (or (get-in result [:limits :llm-infers]) 0))
               :http-posts (long (or (get-in result [:limits :http-posts]) 0))
               :ticks 1}
        b' (charge-budget (:kototama.fleet/budget lease) usage)
        status (cond
                 (lease-expired? lease) :expired
                 (not (budget-remaining? b')) :budget-exhausted
                 :else :active)]
    (assoc lease
           :kototama.fleet/budget b'
           :kototama.fleet/status status
           :kototama.fleet/last-result
           {:ok? (boolean (:ok? result))
            :result (:result result)
            :usage usage
            :at (clock)})))

;; ── multi-tenant registry ───────────────────────────────────────────────────

(defn empty-registry
  "In-memory multi-tenant lease registry (pure map)."
  []
  {:kototama.fleet/leases {}
   :kototama.fleet/tenants {}})

(defn register-lease
  "Put lease into registry keyed by lease-id. Isolates per tenant index."
  [registry lease]
  (let [id (:kototama.fleet/lease-id lease)
        tid (:kototama.fleet/tenant lease)]
    (-> registry
        (assoc-in [:kototama.fleet/leases id] lease)
        (update-in [:kototama.fleet/tenants tid] (fnil conj #{}) id))))

(defn get-lease [registry lease-id]
  (get-in registry [:kototama.fleet/leases lease-id]))

(defn tenant-leases
  "All leases for tenant-id (active or not)."
  [registry tenant-id]
  (let [ids (get-in registry [:kototama.fleet/tenants tenant-id] #{})]
    (mapv #(get-in registry [:kototama.fleet/leases %]) ids)))

(defn all-leases
  "Vector of every lease in the registry."
  [registry]
  (vec (vals (:kototama.fleet/leases registry))))

(defn active-leases
  "Leases that are not expired and still have budget (resumable)."
  ([registry] (active-leases registry (clock)))
  ([registry now]
   (vec (filter (fn [lease]
                  (and lease
                       (not (lease-expired? lease now))
                       (budget-remaining? (:kototama.fleet/budget lease))
                       (not= :expired (:kototama.fleet/status lease))
                       (not= :budget-exhausted (:kototama.fleet/status lease))))
                (all-leases registry)))))

(defn sweep-expired
  "Mark all expired leases :expired; returns updated registry."
  ([registry] (sweep-expired registry (clock)))
  ([registry now]
   (update registry :kototama.fleet/leases
           (fn [m]
             (into {}
                   (map (fn [[id lease]]
                          [id (if (lease-expired? lease now)
                                (expire-lease lease)
                                lease)]))
                   m)))))

;; ── checkpoint / recovery ───────────────────────────────────────────────────

(defn checkpoint
  "EDN-serializable recovery snapshot of registry (+ optional meta)."
  ([registry] (checkpoint registry {}))
  ([registry meta]
   {:kototama.fleet/checkpoint-schema 1
    :kototama.fleet/checkpointed-at (clock)
    :kototama.fleet/registry registry
    :kototama.fleet/meta meta}))

(defn restore
  "Restore registry from checkpoint map. Validates schema version."
  [cp]
  (when-not (= 1 (:kototama.fleet/checkpoint-schema cp))
    (throw (ex-info "kototama.fleet: unknown checkpoint schema"
                    {:schema (:kototama.fleet/checkpoint-schema cp)})))
  (sweep-expired (:kototama.fleet/registry cp)))

;; ── governor (fail-closed gate before tick) ─────────────────────────────────

(defn governor-allow?
  "Independent gate: lease active, budget remaining, optional deny set.

   opts:
     :deny-tenants  set of tenant ids to block
     :deny-guests   set of guest ids to block
     :now           override clock"
  ([lease] (governor-allow? lease {}))
  ([lease {:keys [deny-tenants deny-guests now] :or {deny-tenants #{} deny-guests #{}}}]
   (let [now (or now (clock))]
     (cond
       (lease-expired? lease now)
       {:allow? false :reason :lease-expired}

       (not (budget-remaining? (:kototama.fleet/budget lease)))
       {:allow? false :reason :budget-exhausted}

       (contains? deny-tenants (:kototama.fleet/tenant lease))
       {:allow? false :reason :tenant-denied}

       (contains? deny-guests (:kototama.fleet/guest lease))
       {:allow? false :reason :guest-denied}

       :else
       {:allow? true :reason :ok}))))

(defn run-loop-step
  "One outer-loop step (pure): governor → plan-tick → (caller runs guest) →
   apply-tick-result.

   `execute` is a pure fn tick -> run-report map (inject tender/run-report
   at the edges). Returns {:registry :lease :tick :result :governor}."
  [registry lease-id execute]
  (let [lease (get-lease registry lease-id)
        gov (governor-allow? lease)]
    (if-not (:allow? gov)
      {:registry registry
       :lease lease
       :governor gov
       :ok? false}
      (let [planned (plan-tick lease)]
        (if-not (:ok? planned)
          {:registry registry
           :lease lease
           :governor gov
           :planned planned
           :ok? false}
          (let [result (execute (:tick planned))
                lease' (apply-tick-result lease result)
                reg' (assoc-in registry [:kototama.fleet/leases lease-id] lease')]
            {:registry reg'
             :lease lease'
             :governor gov
             :tick (:tick planned)
             :result result
             :ok? (boolean (:ok? result))}))))))

(defn r3-report
  "Aggregate R3 snapshot for CLI doctor.

   Status advanced-partial: all non-consensus fleet surfaces landed with
   automated `fleet-gate` harness + tick audit. Still not Raft / full broker."
  []
  {:level :r3
   :status :advanced-partial
   :title "Fleet multi-tenant tender"
   :landed ["lease create/renew/expire"
            "budget charge (fuel/llm/http/ticks)"
            "plan-tick + apply-tick-result"
            "governor-allow? fail-closed"
            "registry multi-tenant index"
            "checkpoint/restore EDN schema v1"
            "run-loop-step (injectable execute)"
            "fleet-store disk + optional B2"
            "fleet-exec tender/run-report bridge"
            "resume-from-checkpoint! + recovery-pass!"
            "run-daemon! bounded multi-pass recovery loop"
            "optional aiueos grant resolution (resolve-grants :use-aiueos?)"
            "fleet-fence epoch claim/merge (not Raft)"
            "fence-gated bootstrap/resume/recovery (tender only if claim wins)"
            "systemd oneshot+timer packaging under deploy/systemd/"
            "tick audit journal (audit/<lease>/tN)"
            "programmatic acceptance gate (run-r3-gate! / fleet-gate CLI)"
            "lease heartbeat renew across multi-tick runs"
            "multi-tenant isolation on shared store (gate)"
            "optional aiueos grant path in bootstrap (--use-aiueos)"
            "aiueos GRANT/DENY E2E through fleet-exec + tender"
            "fleet-status / fleet-audit observability CLI"]
   :not-yet ["Raft/Paxos multi-node consensus"
             "full aiueos fleet broker (all actor:host kinds as first-class policy)"]
   :stable-when ["fleet-gate green in CI on every merge"
                 "aiueos grant+deny E2E green without flakiness"
                 "documented ops runbook for systemd daemon"
                 "still NOT claiming Raft — stable means local/shared-store fleet ops"]
   :api ['kototama.fleet 'kototama.fleet-store 'kototama.fleet-exec
         'kototama.fleet-fence]
   :gate "clojure -M:cli fleet-gate"
   :notes ["Pure cljc core + JVM store/exec edges"
           "1 tick = 1 bounded guest run; no internal infinite loops"
           "B2 via B2_KEY_ID/B2_APP_KEY/B2_BUCKET or KOTOTAMA_FLEET_B2_*"
           "CLI: fleet-run | list | status | audit | resume | recover | daemon | fence-demo | fleet-gate"
           "deploy: deploy/systemd + deploy/bin/kototama-fleet-daemon"
           "Multi-node: claim-before-run; held-by-other → skip tender"
           "R3 advanced-partial — not production multi-datacenter"]})
