(ns kototama.component-authority-http
  "Bounded HTTP receiver for signed Murakumo Component authority envelopes."
  (:require [clojure.edn :as edn]
            [kototama.component-authority :as authority])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent Executors]))

(def default-path "/v1/component-authority")
(def max-request-bytes (* 1024 1024))

(defn- response! [^HttpExchange exchange status body]
  (let [bytes (.getBytes (pr-str body) StandardCharsets/UTF_8)]
    (.set (.getResponseHeaders exchange) "content-type" "application/edn")
    (.sendResponseHeaders exchange status (alength bytes))
    (with-open [output (.getResponseBody exchange)]
      (.write output bytes))))

(defn- request-bytes! [^HttpExchange exchange]
  (let [length-value (.getFirst (.getRequestHeaders exchange) "content-length")
        length (when length-value
                 (try (Long/parseLong length-value)
                      (catch NumberFormatException _ -1)))]
    (when (or (and length (neg? length))
              (and length (> length max-request-bytes)))
      (throw (ex-info "Authority request length is invalid"
                      {:reason :invalid-content-length})))
    (let [bytes (.readNBytes (.getRequestBody exchange)
                             (inc max-request-bytes))]
      (when (> (alength bytes) max-request-bytes)
        (throw (ex-info "Authority request is too large"
                        {:reason :request-too-large})))
      bytes)))

(defn handler
  "Create the production handler. TRUST is passed directly to
  component-authority/apply-envelope! and contains the trusted key registry,
  local audience, clock, and freshness bounds."
  [state trust]
  (reify HttpHandler
    (handle [_ exchange]
      (let [^HttpExchange exchange exchange]
        (try
          (cond
            (not= "POST" (.getRequestMethod exchange))
            (response! exchange 405 {:ok? false :reason :method-not-allowed})

            (not= "application/edn"
                  (some-> (.getFirst (.getRequestHeaders exchange)
                                     "content-type")
                          (.split ";" 2)
                          first
                          .toLowerCase))
            (response! exchange 415 {:ok? false :reason :unsupported-content-type})

            :else
            (let [envelope (edn/read-string
                            (String. ^bytes (request-bytes! exchange)
                                     StandardCharsets/UTF_8))
                  event (authority/apply-envelope! state envelope trust)]
              (response! exchange 202
                         {:ok? true
                          :sequence (:murakumo.component/sequence event)})))
          (catch Exception exception
            (response! exchange 403
                       {:ok? false
                        :reason (or (:kototama.component-authority/reason
                                     (ex-data exception))
                                    (:reason (ex-data exception))
                                    :invalid-request)}))
          (finally
            (.close exchange)))))))

(defn start!
  "Start a real JDK HTTP receiver and return {:server :port :stop!}.
  Remote binding requires explicit :allow-remote? true."
  [{:keys [bind-host port path state trust allow-remote?]
    :or {bind-host "127.0.0.1" port 0 path default-path}}]
  (when-not (and state (map? trust))
    (throw (ex-info "Authority receiver requires state and trust configuration"
                    {:reason :missing-configuration})))
  (when (and (not (contains? #{"127.0.0.1" "::1" "localhost"} bind-host))
             (not allow-remote?))
    (throw (ex-info "Remote authority receiver binding requires explicit opt-in"
                    {:reason :remote-bind-not-authorized :bind-host bind-host})))
  (let [server (HttpServer/create
                (InetSocketAddress. ^String bind-host (int port)) 0)
        executor (Executors/newVirtualThreadPerTaskExecutor)]
    (.createContext server path (handler state trust))
    (.setExecutor server executor)
    (.start server)
    {:server server
     :port (.getPort (.getAddress server))
     :stop! (fn []
              (.stop server 0)
              (.close executor))}))
