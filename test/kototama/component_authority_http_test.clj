(ns kototama.component-authority-http-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [ed25519.core :as ed]
            [kotoba.abi.contract :as abi]
            [kototama.component-authority :as authority]
            [kototama.component-authority-http :as http])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

(def seed (byte-array (range 32)))
(def now 1785000000000)
(def cid "bafycomponentauthorityhttp")

(def trust
  {:trusted-keys {"murakumo-2026-01"
                  {:issuer "did:key:murakumo"
                   :public-key (ed/pubkey-from-seed seed)}}
   :audience "did:key:kototama-edge-a"
   :now-ms (constantly now)})

(defn- hex [bytes]
  (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes)))

(defn- envelope [epoch sequence]
  (let [unsigned
        {:format :murakumo.component-authority/v1
         :algorithm :ed25519
         :key-id "murakumo-2026-01"
         :issuer "did:key:murakumo"
         :audience "did:key:kototama-edge-a"
         :issued-at-ms now
         :event {:murakumo.component/version 1
                 :murakumo.component/event :revoked
                 :murakumo.component/component-cid cid
                 :murakumo.component/epoch epoch
                 :murakumo.component/sequence sequence
                 :murakumo.component/node nil}}]
    (assoc unsigned :signature
           (hex (ed/sign seed
                         (.getBytes
                          (abi/component-authority-signing-payload unsigned)
                          "UTF-8"))))))

(defn- post [port value]
  (let [request (-> (HttpRequest/newBuilder
                     (URI/create
                      (str "http://127.0.0.1:" port http/default-path)))
                    (.header "content-type" "application/edn")
                    (.POST (HttpRequest$BodyPublishers/ofString
                            (pr-str value)))
                    .build)]
    (.send (HttpClient/newHttpClient) request
           (HttpResponse$BodyHandlers/ofString))))

(deftest real-http-receiver-updates-live-provider-epoch
  (let [state (atom (authority/initial-state))
        {:keys [port stop!]} (http/start! {:state state :trust trust})]
    (try
      (let [response (post port (envelope 1 1))]
        (is (= 202 (.statusCode response)))
        (is (= {:ok? true :sequence 1}
               (edn/read-string (.body response))))
        (is (= 1 ((:lease-epoch-source
                   (authority/admission-options state cid))))))
      (let [response (post port
                           (update (envelope 2 2)
                                   :event assoc
                                   :murakumo.component/epoch 99))]
        (is (= 403 (.statusCode response)))
        (is (= 1 ((authority/epoch-source state cid)))))
      (finally (stop!)))))

(deftest remote-listen-needs-explicit-authorization
  (is (thrown? clojure.lang.ExceptionInfo
               (http/start! {:bind-host "0.0.0.0"
                             :state (atom (authority/initial-state))
                             :trust trust}))))
