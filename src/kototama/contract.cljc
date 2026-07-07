(ns kototama.contract
  "Pure CLJC authority contract for the kototama host facade.

  This namespace defines data shapes and validation rules for actor host imports.
  Hosts may adapt these maps to Rust, JS, or JVM APIs, but the requested import
  surface is accepted only when the contract grants and runtime limits allow it.")

(def actor-host-namespace "actor:host")
(def actor-host-version 0)

(def import-surface
  {:abi/namespace actor-host-namespace
   :abi/version actor-host-version
   :abi/imports
   [{:import/id :gen-keypair
     :import/name "gen-keypair"
     :import/category :identity
     :import/effects #{:crypto :secret}}
    {:import/id :sign
     :import/name "sign"
     :import/category :identity
     :import/effects #{:crypto :secret}}
    {:import/id :verify
     :import/name "verify"
     :import/category :identity
     :import/effects #{:crypto}}
    {:import/id :sha256-hex
     :import/name "sha256-hex"
     :import/category :content-addressing
     :import/effects #{:crypto}}
    {:import/id :http-post
     :import/name "http-post"
     :import/category :network
     :import/effects #{:network}}
    {:import/id :log-read
     :import/name "log-read"
     :import/category :storage
     :import/effects #{:storage}}
    {:import/id :log-write
     :import/name "log-write"
     :import/category :storage
     :import/effects #{:storage :write}}
    {:import/id :clock-monotonic
     :import/name "clock-monotonic"
     :import/category :clock
     :import/effects #{:clock}}
    {:import/id :llm-infer
     :import/name "llm-infer"
     :import/category :llm
     :import/effects #{:network}}]})

(def import-by-id
  (into {} (map (juxt :import/id identity) (:abi/imports import-surface))))

(def import-id-by-name
  (into {} (map (juxt :import/name :import/id) (:abi/imports import-surface))))

(def RuntimeLimits
  {:model/name :kototama.contract/RuntimeLimits
   :model/keys {:max-imports :non-negative-int
                :max-http-posts :non-negative-int
                :max-llm-infers :non-negative-int
                :max-log-read-bytes :non-negative-int
                :max-log-write-bytes :non-negative-int
                :max-memory-pages :non-negative-int
                :allow-secret-imports? :boolean
                :allow-write-imports? :boolean}})

(def default-runtime-limits
  {:max-imports (count (:abi/imports import-surface))
   :max-http-posts 0
   ;; Same "fully denied unless a caller's HostCaps explicitly raises the
   ;; limit AND grants the import" default as :max-http-posts -- an LLM
   ;; call is metered network egress + real API spend, not a free action.
   :max-llm-infers 0
   :max-log-read-bytes 1048576
   :max-log-write-bytes 65536
   ;; 16 Wasm pages (64 KiB/page) = 1 MiB -- a guest that legitimately
   ;; needs more grows this explicitly via HostCaps, same as every other
   ;; limit here; this is NOT an :abi/imports-gated effect (a guest's
   ;; linear memory ceiling applies regardless of which host imports it's
   ;; granted), so it is deliberately NOT checked by
   ;; validate-import-surface below -- a host adapter (kototama.tender,
   ;; the browser actor-host.js) reads it directly off HostCaps' :limits
   ;; when it instantiates.
   :max-memory-pages 16
   :allow-secret-imports? false
   :allow-write-imports? false})

(def HostCaps
  {:model/name :kototama.contract/HostCaps
   :model/keys {:abi/namespace :string
                :abi/version :non-negative-int
                :grants :set-of-import-id
                :limits (:model/name RuntimeLimits)}})

(def default-host-caps
  {:abi/namespace actor-host-namespace
   :abi/version actor-host-version
   :grants #{}
   :limits default-runtime-limits})

(defn import-id
  "Returns the canonical import id for a request item, or nil when unknown."
  [x]
  (cond
    (keyword? x) (when (contains? import-by-id x) x)
    (string? x) (get import-id-by-name x)
    (map? x) (or (import-id (:import/id x))
                 (import-id (:id x))
                 (import-id (:import/name x))
                 (import-id (:fn x))
                 (import-id (:name x)))
    :else nil))

(defn requested-import-ids
  "Normalizes a requested surface into canonical import ids.

  Accepts a vector of keywords/strings/maps, or an ABI-shaped map containing
  :abi/imports. Unknown entries are preserved as nil so callers can detect them
  by comparing with the original request."
  [surface]
  (let [imports (if (map? surface) (:abi/imports surface) surface)]
    (mapv import-id (or imports []))))

(defn runtime-limits
  "Builds RuntimeLimits data by overlaying m on default-runtime-limits."
  ([] default-runtime-limits)
  ([m] (merge default-runtime-limits (or m {}))))

(defn host-caps
  "Builds HostCaps data. :grants is normalized to canonical import ids."
  ([] default-host-caps)
  ([m]
   (let [limits (runtime-limits (:limits m))
         grants (set (keep import-id (:grants m)))]
     (merge default-host-caps
            (select-keys m [:abi/namespace :abi/version])
            {:grants grants
             :limits limits}))))

(defn- limit-errors [limits requested ids]
  (let [requested-count (count requested)
        http-posts (count (filter #{:http-post} ids))
        llm-infers (count (filter #{:llm-infer} ids))
        secret-imports (filterv #(some (:import/effects (import-by-id %)) [:secret]) ids)
        write-imports (filterv #(some (:import/effects (import-by-id %)) [:write]) ids)]
    (cond-> []
      (> requested-count (:max-imports limits))
      (conj {:error :limit/max-imports
             :limit (:max-imports limits)
             :actual requested-count})

      (> http-posts (:max-http-posts limits))
      (conj {:error :limit/max-http-posts
             :limit (:max-http-posts limits)
             :actual http-posts})

      (> llm-infers (:max-llm-infers limits))
      (conj {:error :limit/max-llm-infers
             :limit (:max-llm-infers limits)
             :actual llm-infers})

      (and (false? (:allow-secret-imports? limits))
           (seq secret-imports))
      (conj {:error :limit/secret-imports
             :imports secret-imports})

      (and (false? (:allow-write-imports? limits))
           (seq write-imports))
      (conj {:error :limit/write-imports
             :imports write-imports}))))

(defn- abi-errors [surface]
  (if (map? surface)
    (cond-> []
      (not= actor-host-namespace (:abi/namespace surface))
      (conj {:error :abi/namespace
             :expected actor-host-namespace
             :actual (:abi/namespace surface)})

      (not= actor-host-version (:abi/version surface))
      (conj {:error :abi/version
             :expected actor-host-version
             :actual (:abi/version surface)}))
    []))

(defn validate-import-surface
  "Validates requested imports against HostCaps grants and RuntimeLimits.

  Returns {:ok? true ...} or {:ok? false :errors [...] ...}. This is pure data
  so host adapters can report or encode failures without duplicating authority
  decisions."
  ([surface caps]
   (let [caps (host-caps caps)]
     (validate-import-surface surface (:grants caps) (:limits caps))))
  ([surface grants limits]
   (let [abi-errors (abi-errors surface)
         requested (if (map? surface) (:abi/imports surface) surface)
         requested (vec (or requested []))
         ids (requested-import-ids requested)
         indexed (map vector requested ids)
         unknown (vec (keep (fn [[request id]]
                              (when (nil? id) request))
                            indexed))
         known-ids (vec (keep identity ids))
         grants (set (keep import-id grants))
         missing (vec (remove grants known-ids))
         limits (runtime-limits limits)
         errors (cond-> abi-errors
                  (seq unknown)
                  (conj {:error :imports/unknown
                         :imports unknown})

                  (seq missing)
                  (conj {:error :grants/missing
                         :imports missing}))]
     (let [errors (into errors (limit-errors limits requested known-ids))]
       {:ok? (empty? errors)
        :requested known-ids
        :granted grants
        :limits limits
        :errors errors}))))
