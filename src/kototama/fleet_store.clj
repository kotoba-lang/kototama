(ns kototama.fleet-store
  "R3 checkpoint persistence: disk always; B2 optional via env or inject.

   Store shape (open map):
     {:save! (fn [key edn-data] path-or-id)
      :load! (fn [key] edn-data-or-nil)
      :kind  :disk | :memory | :b2 | :composite
      :root  string}

   B2 uses native Backblaze JSON API when B2_KEY_ID + B2_APP_KEY + B2_BUCKET
   are set (or KOTOTAMA_FLEET_B2_* overrides). No AWS SDK."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [kototama.fleet :as fleet])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.util Base64)
           (java.time Duration)))

(defn- ensure-parent! [f]
  (when-let [p (.getParentFile (io/file f))]
    (.mkdirs p)))

(defn memory-store
  "In-process atom store (tests)."
  []
  (let [a (atom {})]
    {:kind :memory
     :save! (fn [k data]
              (swap! a assoc k data)
              (str "mem://" k))
     :load! (fn [k] (get @a k))
     :dump (fn [] @a)}))

(defn- safe-key [k]
  (-> (str k)
      (str/replace #"[^A-Za-z0-9._/-]" "_")
      (str/replace #"/" "__")))

(defn disk-store
  "Write EDN checkpoints under root-dir (default ./tmp/kototama-fleet)."
  ([] (disk-store "tmp/kototama-fleet"))
  ([root-dir]
   (let [root (io/file root-dir)]
     (.mkdirs root)
     {:kind :disk
      :root (.getPath root)
      :save! (fn [k data]
               (let [f (io/file root (str (safe-key k) ".edn"))]
                 (ensure-parent! f)
                 (spit f (pr-str data))
                 (.getPath f)))
      :load! (fn [k]
               (let [f (io/file root (str (safe-key k) ".edn"))]
                 (when (.exists f)
                   (edn/read-string (slurp f)))))})))

(defn append-tick-audit!
  "Persist one tick's run-report under audit/<lease-id>/tN-ts for R3 observability.

   Schema v1 — not a full SIEM; enough for recovery and local forensics."
  [store lease-id tick-n report]
  (let [key (str "audit/" lease-id "/t" tick-n "-" (System/currentTimeMillis))
        data {:kototama.fleet/audit-schema 1
              :kototama.fleet/lease-id (str lease-id)
              :kototama.fleet/tick (long tick-n)
              :kototama.fleet/at (System/currentTimeMillis)
              :ok? (boolean (:ok? report))
              :result (:result report)
              :fuel-used (:fuel-used report)
              :error (or (:error report) (:reason report))}]
    (when-let [save! (:save! store)]
      (let [path (save! key data)]
        {:key key :path path :data data}))))

;; ── B2 native (authorize + upload/download by name) ─────────────────────────

(defn- env [k]
  (not-empty (System/getenv k)))

(defn- b2-creds
  "Resolve B2 credentials from env. Prefer KOTOTAMA_FLEET_B2_*; fall back to B2_*."
  []
  (let [key-id (or (env "KOTOTAMA_FLEET_B2_KEY_ID") (env "B2_KEY_ID") (env "B2_APPLICATION_KEY_ID"))
        app-key (or (env "KOTOTAMA_FLEET_B2_APP_KEY") (env "B2_APP_KEY") (env "B2_APPLICATION_KEY"))
        bucket (or (env "KOTOTAMA_FLEET_B2_BUCKET") (env "B2_BUCKET"))
        prefix (or (env "KOTOTAMA_FLEET_B2_PREFIX") "kototama-fleet/")]
    (when (and key-id app-key bucket)
      {:key-id key-id :app-key app-key :bucket bucket :prefix prefix})))

(defn- http-client []
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 30))
      .build))

(defn- basic-auth [id key]
  (str "Basic "
       (.encodeToString
        (Base64/getEncoder)
        (.getBytes (str id ":" key) StandardCharsets/UTF_8))))

(defn- b2-authorize!
  "b2_authorize_account → {:api-url :auth-token :download-url :account-id}"
  [{:keys [key-id app-key]}]
  (let [req (-> (HttpRequest/newBuilder
                 (URI/create "https://api.backblazeb2.com/b2api/v2/b2_authorize_account"))
                (.header "Authorization" (basic-auth key-id app-key))
                (.GET)
                .build)
        resp (.send (http-client) req (HttpResponse$BodyHandlers/ofString))]
    (when-not (<= 200 (.statusCode resp) 299)
      (throw (ex-info "b2_authorize_account failed"
                      {:status (.statusCode resp) :body (.body resp)})))
    (let [body (json/read-str (.body resp) :key-fn keyword)]
      {:api-url (:apiUrl body)
       :auth-token (:authorizationToken body)
       :download-url (:downloadUrl body)
       :account-id (:accountId body)})))

(defn- b2-bucket-id!
  [auth bucket-name]
  (let [req (-> (HttpRequest/newBuilder
                 (URI/create (str (:api-url auth) "/b2api/v2/b2_list_buckets")))
                (.header "Authorization" (:auth-token auth))
                (.header "Content-Type" "application/json")
                (.POST (HttpRequest$BodyPublishers/ofString
                        (json/write-str {:accountId (:account-id auth)
                                         :bucketName bucket-name})))
                .build)
        resp (.send (http-client) req (HttpResponse$BodyHandlers/ofString))
        body (json/read-str (.body resp) :key-fn keyword)
        buckets (:buckets body)
        match (first (filter #(= bucket-name (:bucketName %)) buckets))]
    (when-not match
      (throw (ex-info "B2 bucket not found" {:bucket bucket-name :status (.statusCode resp)})))
    (:bucketId match)))

(defn- b2-get-upload-url!
  [auth bucket-id]
  (let [req (-> (HttpRequest/newBuilder
                 (URI/create (str (:api-url auth) "/b2api/v2/b2_get_upload_url")))
                (.header "Authorization" (:auth-token auth))
                (.header "Content-Type" "application/json")
                (.POST (HttpRequest$BodyPublishers/ofString
                        (json/write-str {:bucketId bucket-id})))
                .build)
        resp (.send (http-client) req (HttpResponse$BodyHandlers/ofString))
        body (json/read-str (.body resp) :key-fn keyword)]
    (when-not (<= 200 (.statusCode resp) 299)
      (throw (ex-info "b2_get_upload_url failed" {:status (.statusCode resp) :body (.body resp)})))
    {:upload-url (:uploadUrl body)
     :auth-token (:authorizationToken body)}))

(defn- sha1-hex [^bytes bs]
  (let [md (java.security.MessageDigest/getInstance "SHA-1")
        d (.digest md bs)]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))

(defn- b2-upload!
  [upload file-name ^String content]
  (let [bs (.getBytes content StandardCharsets/UTF_8)
        sha (sha1-hex bs)
        req (-> (HttpRequest/newBuilder (URI/create (:upload-url upload)))
                (.header "Authorization" (:auth-token upload))
                (.header "X-Bz-File-Name"
                         (-> (URLEncoder/encode file-name "UTF-8")
                             (str/replace "+" "%20")))
                (.header "Content-Type" "application/edn")
                (.header "X-Bz-Content-Sha1" sha)
                (.POST (HttpRequest$BodyPublishers/ofByteArray bs))
                .build)
        resp (.send (http-client) req (HttpResponse$BodyHandlers/ofString))]
    (when-not (<= 200 (.statusCode resp) 299)
      (throw (ex-info "b2 upload failed" {:status (.statusCode resp) :body (.body resp)})))
    (json/read-str (.body resp) :key-fn keyword)))

(defn- b2-download-by-name!
  [auth bucket-name file-name]
  (let [url (str (:download-url auth) "/file/" bucket-name "/" file-name)
        req (-> (HttpRequest/newBuilder (URI/create url))
                (.header "Authorization" (:auth-token auth))
                (.GET)
                .build)
        resp (.send (http-client) req (HttpResponse$BodyHandlers/ofString))]
    (when (= 200 (.statusCode resp))
      (edn/read-string (.body resp)))))

(defn b2-store
  "B2-backed store. Returns nil if credentials missing (caller falls back).

   opts: :key-id :app-key :bucket :prefix — or env via b2-creds."
  ([] (b2-store nil))
  ([opts]
   (when-let [creds (merge (b2-creds) opts)]
     (when (and (:key-id creds) (:app-key creds) (:bucket creds))
       (let [auth (atom nil)
             bucket-id (atom nil)
             prefix (or (:prefix creds) "kototama-fleet/")
             ensure! (fn []
                       (when-not @auth
                         (reset! auth (b2-authorize! creds))
                         (reset! bucket-id (b2-bucket-id! @auth (:bucket creds)))))]
         {:kind :b2
          :bucket (:bucket creds)
          :prefix prefix
          :save! (fn [k data]
                   (ensure!)
                   (let [name (str prefix
                                   (-> (str k)
                                       (str/replace #"[^A-Za-z0-9._/-]" "_")
                                       (str/replace #"/" "__")
                                       (str ".edn")))
                         up (b2-get-upload-url! @auth @bucket-id)
                         content (pr-str data)]
                     (b2-upload! up name content)
                     (str "b2://" (:bucket creds) "/" name)))
          :load! (fn [k]
                   (ensure!)
                   (let [name (str prefix
                                   (-> (str k)
                                       (str/replace #"[^A-Za-z0-9._/-]" "_")
                                       (str/replace #"/" "__")
                                       (str ".edn")))]
                     (b2-download-by-name! @auth (:bucket creds) name)))})))))

(defn composite-store
  "Write-through: disk always; also remote when present."
  [disk remote]
  {:kind :composite
   :save! (fn [k data]
            (let [p ((:save! disk) k data)]
              (when remote
                (try ((:save! remote) k data)
                     (catch Exception e
                       ;; disk succeeded; surface remote error as meta only
                       (binding [*out* *err*]
                         (println "kototama.fleet-store: remote save failed:" (.getMessage e))))))
              p))
   :load! (fn [k]
            (or ((:load! disk) k)
                (when remote
                  (try ((:load! remote) k)
                       (catch Exception _ nil)))))})

(defn default-store
  "disk + optional B2 from env."
  ([] (default-store "tmp/kototama-fleet"))
  ([root]
   (composite-store (disk-store root) (b2-store))))

(defn save-checkpoint!
  "fleet/checkpoint → store. Returns {:path :checkpoint}."
  ([registry] (save-checkpoint! registry (default-store) nil))
  ([registry store] (save-checkpoint! registry store nil))
  ([registry store meta]
   (let [cp (fleet/checkpoint registry (or meta {}))
         key (or (:key meta)
                 (str "cp-" (:kototama.fleet/checkpointed-at cp)))
         path ((:save! store) key cp)]
     {:path path :key key :checkpoint cp})))

(defn checkpoint-edn?
  "True when EDN looks like a fleet checkpoint (not tick audit / other blobs)."
  [cp]
  (and (map? cp)
       (= 1 (:kototama.fleet/checkpoint-schema cp))))

(defn load-checkpoint!
  "Load + restore registry from store key. Returns registry or nil.

   Skips non-checkpoint blobs (e.g. tick audit journal entries)."
  ([key] (load-checkpoint! key (default-store)))
  ([key store]
   (when-let [cp ((:load! store) key)]
     (when (checkpoint-edn? cp)
       (fleet/restore cp)))))

(defn load-checkpoint-raw!
  "Load checkpoint map without restore (includes :meta with wasm path etc.)."
  ([key] (load-checkpoint-raw! key (default-store)))
  ([key store]
   ((:load! store) key)))

(defn- raw-store-keys
  "All keys on store (includes audit). Prefer list-checkpoint-keys for fleet."
  [store]
  (case (:kind store)
    :disk
    (let [root (io/file (:root store))]
      (if-not (.isDirectory root)
        []
        (->> (.listFiles root)
             (filter #(.isFile %))
             (map #(.getName %))
             (filter #(str/ends-with? % ".edn"))
             (map #(subs % 0 (- (count %) 4)))
             sort
             vec)))
    :memory
    (if-let [dump (:dump store)]
      (vec (sort (keys (dump))))
      [])
    :composite
    (let [disk-keys (when (:root store)
                      (raw-store-keys (disk-store (:root store))))]
      (or disk-keys []))
    []))

(defn list-checkpoint-keys
  "List checkpoint keys known to the store (excludes tick audit keys).

   Disk: scan root for *.edn basenames (without extension).
   Memory: dump keys. B2-only remote: empty unless dump provided."
  [store]
  (->> (raw-store-keys store)
       (remove (fn [k]
                 (let [s (str/replace (str k) #"__" "/")]
                   (str/starts-with? s "audit/"))))
       vec))

(defn list-disk-checkpoint-keys
  "Convenience: list keys under a disk root path."
  ([] (list-checkpoint-keys (disk-store)))
  ([root] (list-checkpoint-keys (disk-store root))))

(defn list-audit-keys
  "Keys that look like tick audits (prefix audit/ after __ unescape on disk)."
  [store]
  (->> (raw-store-keys store)
       (map (fn [k]
              ;; disk basenames keep __ for /
              (str/replace (str k) #"__" "/")))
       (filter #(str/starts-with? (str %) "audit/"))
       vec))
