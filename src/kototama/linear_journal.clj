(ns kototama.linear-journal
  "Durable at-most-once capability consumption journal."
  (:require [clojure.edn :as edn])
  (:import [java.io File RandomAccessFile]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent ConcurrentHashMap]
           [java.util.concurrent.locks ReentrantLock]
           [java.util.function Function]))

(defrecord Journal [^File file ^ReentrantLock lock])
(defonce ^:private path-locks (ConcurrentHashMap.))

(defn open! [path]
  (let [file (File. ^String path)]
    (when-let [parent (.getParentFile file)] (.mkdirs parent))
    (when-not (.exists file) (.createNewFile file))
    (->Journal file
               (.computeIfAbsent
                path-locks (.getCanonicalPath file)
                (reify Function
                  (apply [_ _] (ReentrantLock.)))))))

(defn entries [^Journal journal]
  (with-open [reader (java.io.BufferedReader. (java.io.FileReader. (:file journal)))]
    (->> (line-seq reader) (remove empty?) (mapv edn/read-string))))

(defn consumed [journal lease-id import]
  (count (filter #(and (= :consume (:op %))
                       (= lease-id (:lease-id %))
                       (= import (:import %)))
                 (entries journal))))

(defn claim!
  "Fsync one claim before provider execution. A crash may consume authority
  without a result, but recovery can never replay it."
  [^Journal journal lease-id import max-items]
  (let [lock (:lock journal)]
    (.lock lock)
    (try
      (with-open [raf (RandomAccessFile. (:file journal) "rw")
                  file-lock (.lock (.getChannel raf))]
        (let [bytes (byte-array (.length raf))
              _ (.seek raf 0)
              _ (.readFully raf bytes)
              recovered (->> (.split (String. bytes StandardCharsets/UTF_8) "\n")
                             (remove empty?)
                             (map edn/read-string))
              used (count (filter #(and (= :consume (:op %))
                                        (= lease-id (:lease-id %))
                                        (= import (:import %)))
                                  recovered))]
          (if (>= used max-items)
            false
            (do
              (.seek raf (.length raf))
              (.write raf (.getBytes
                           (str (pr-str {:op :consume :lease-id lease-id
                                        :import import :ordinal (inc used)}) "\n")
                           StandardCharsets/UTF_8))
              (.sync (.getFD raf))
              true))))
      (finally (.unlock lock)))))
