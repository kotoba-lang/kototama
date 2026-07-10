(ns kototama.fleet-fence
  "Cross-node lease fencing skeleton (not Raft/Paxos).

   Pure data rules so multiple recovery daemons can share a checkpoint store
   without double-running the same tenant/guest:

   - Each lease carries :kototama.fleet/epoch (monotonic generation).
   - fence-key = [tenant guest] — identity of the work unit.
   - Higher epoch always wins. Equal epoch → later issued-at; then owner id.
   - claim-or-renew: take ownership if free/expired/higher epoch; refuse if
     another owner holds a live equal-or-higher epoch.

   Does NOT implement network consensus, leader election, or clock sync.
   Nodes must share wall-clock roughly and a shared store (disk NFS / B2)."
  (:require [kototama.fleet :as fleet]))

(defn fence-key
  "Identity of a work unit for fencing (tenant + guest)."
  [lease]
  [(:kototama.fleet/tenant lease)
   (:kototama.fleet/guest lease)])

(defn epoch
  ([lease] (long (or (:kototama.fleet/epoch lease) 1)))
  ([lease n] (assoc lease :kototama.fleet/epoch (long n))))

(defn with-epoch
  "Ensure lease has an epoch (default 1)."
  ([lease] (if (:kototama.fleet/epoch lease) lease (epoch lease 1)))
  ([lease n] (epoch lease n)))

(defn lease-beats?
  "True if lease A should win over lease B under fencing rules.
   Nil B always loses."
  [a b]
  (cond
    (nil? b) true
    (nil? a) false
    :else
    (let [ea (epoch a)
          eb (epoch b)]
      (cond
        (> ea eb) true
        (< ea eb) false
        :else
        (let [ia (long (:kototama.fleet/issued-at a 0))
              ib (long (:kototama.fleet/issued-at b 0))]
          (cond
            (> ia ib) true
            (< ia ib) false
            :else
            ;; stable tie-break: owner string
            (pos? (compare (str (:kototama.fleet/owner a))
                           (str (:kototama.fleet/owner b))))))))))

(defn holder-for-key
  "Best (winning) lease currently registered for fence-key, if any."
  [registry fkey]
  (->> (fleet/all-leases registry)
       (filter #(= fkey (fence-key %)))
       (sort (fn [a b] (if (lease-beats? a b) -1 1)))
       first))

(defn can-claim?
  "May NODE-ID claim/renew work for LEASE under REGISTRY?

   Returns {:ok? true :reason :free|:renew|:steal-expired|:higher-epoch}
   or {:ok? false :reason :held-by-other :holder lease}.

   Live holders are only displaced by a *strictly higher* epoch (or expiry).
   Owner string / issued-at tie-breaks are for merge-registries only, not steal."
  [registry lease node-id]
  (let [lease (with-epoch lease)
        fkey (fence-key lease)
        holder (holder-for-key registry fkey)
        now (fleet/now-ms)]
    (cond
      (nil? holder)
      {:ok? true :reason :free}

      (= (str node-id) (str (:kototama.fleet/owner holder)))
      {:ok? true :reason :renew :holder holder}

      (fleet/lease-expired? holder now)
      {:ok? true :reason :steal-expired :holder holder}

      (> (epoch lease) (epoch holder))
      {:ok? true :reason :higher-epoch :holder holder}

      :else
      {:ok? false :reason :held-by-other :holder holder})))

(defn claim-lease
  "Attempt to put LEASE into REGISTRY as owned by NODE-ID.

   On success: register (possibly with bumped epoch). On failure: registry
   unchanged + :ok? false.

   opts:
     :bump-epoch?  when stealing expired/higher, bump epoch to holder+1 (default true)
     :ttl-ms       renew TTL when same owner (default 60000)"
  [registry lease node-id & {:keys [bump-epoch? ttl-ms]
                             :or {bump-epoch? true ttl-ms 60000}}]
  (let [lease (-> lease
                  (with-epoch)
                  (assoc :kototama.fleet/owner (str node-id)))
        decision (can-claim? registry lease node-id)]
    (if-not (:ok? decision)
      {:ok? false
       :reason (:reason decision)
       :holder (:holder decision)
       :registry registry}
      (let [holder (:holder decision)
            lease' (case (:reason decision)
                     :renew
                     (fleet/renew-lease (assoc lease
                                              :kototama.fleet/lease-id
                                              (:kototama.fleet/lease-id holder)
                                              :kototama.fleet/epoch (epoch holder)
                                              :kototama.fleet/budget
                                              (:kototama.fleet/budget holder))
                                       ttl-ms)

                     (:steal-expired :higher-epoch)
                     (let [next-epoch (if bump-epoch?
                                        (inc (epoch (or holder lease)))
                                        (epoch lease))]
                       (-> lease
                           (with-epoch next-epoch)
                           (assoc :kototama.fleet/status :active)))

                     ;; :free
                     (with-epoch lease (epoch lease)))
            ;; drop losing holders for same fence-key
            reg-cleaned
            (reduce (fn [reg l]
                      (if (and (= (fence-key l) (fence-key lease'))
                               (not= (:kototama.fleet/lease-id l)
                                     (:kototama.fleet/lease-id lease')))
                        (let [id (:kototama.fleet/lease-id l)
                              tid (:kototama.fleet/tenant l)]
                          (-> reg
                              (update :kototama.fleet/leases dissoc id)
                              (update-in [:kototama.fleet/tenants tid] disj id)))
                        reg))
                    registry
                    (fleet/all-leases registry))
            reg' (fleet/register-lease reg-cleaned lease')]
        {:ok? true
         :reason (:reason decision)
         :lease lease'
         :registry reg'}))))

(defn merge-registries
  "Merge two registries by fence-key: winner per fencing rules kept.

   Used when two nodes wrote checkpoints to a shared store and a third
   (or either) loads both and reconciles before recovery."
  [reg-a reg-b]
  (let [all (concat (fleet/all-leases reg-a) (fleet/all-leases reg-b))
        by-key (group-by fence-key all)
        winners (mapv (fn [[_ leases]]
                        (reduce (fn [best l]
                                  (if (lease-beats? l best) l best))
                                (first leases)
                                (rest leases)))
                      by-key)]
    (reduce fleet/register-lease (fleet/empty-registry) winners)))

(defn node-id
  "Stable-ish node identity: env KOTOTAMA_NODE_ID or hostname+pid."
  []
  (or #?(:clj (System/getenv "KOTOTAMA_NODE_ID")
         :cljs nil)
      #?(:clj (str (or (.getHostName (java.net.InetAddress/getLocalHost)) "node")
                   "-"
                   (.pid (java.lang.ProcessHandle/current)))
         :cljs "node-cljs")))
