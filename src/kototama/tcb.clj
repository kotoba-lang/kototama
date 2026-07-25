(ns kototama.tcb
  "Machine-verifiable trusted-computing-base inventory for the tender."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.security MessageDigest]))

(def inventory-path "qualification/tcb-inventory.edn")

(defn- hex [^bytes value]
  (apply str (map #(format "%02x" (bit-and (int %) 0xff)) value)))

(defn sha256-file [path]
  (with-open [input (io/input-stream path)]
    (let [digest (MessageDigest/getInstance "SHA-256")
          buffer (byte-array 16384)]
      (loop []
        (let [n (.read input buffer)]
          (when (pos? n)
            (.update digest buffer 0 n)
            (recur))))
      (hex (.digest digest)))))

(defn read-inventory []
  (edn/read-string (slurp inventory-path)))

(defn validate
  "Validate completeness metadata, file presence, roles, digests, and external
   boundary records. Trusted code cannot change silently."
  ([] (validate (read-inventory)))
  ([inventory]
   (let [files (:tcb/files inventory)
         paths (mapv :path files)
         external (:tcb/external inventory)
         errors
         (into []
               (concat
                (when-not (= 1 (:tcb/version inventory))
                  [{:kind :unsupported-version
                    :actual (:tcb/version inventory)}])
                (when-not (= (count paths) (count (set paths)))
                  [{:kind :duplicate-path}])
                (when-not (seq external)
                  [{:kind :missing-external-boundaries}])
                (mapcat
                 (fn [{:keys [path role sha256]}]
                   (let [file (io/file path)]
                     (cond
                       (not (.exists file))
                       [{:kind :missing-file :path path}]
                       (not (keyword? role))
                       [{:kind :missing-role :path path}]
                       (not= sha256 (sha256-file file))
                       [{:kind :digest-drift :path path
                         :expected sha256 :actual (sha256-file file)}]
                       :else [])))
                 files)
                (keep (fn [boundary]
                        (when-not (and (:coordinate boundary)
                                       (:role boundary)
                                       (or (:version boundary)
                                           (:git-sha boundary)
                                           (:minimum-version boundary)))
                          {:kind :unversioned-external-boundary
                           :boundary boundary}))
                      external)))]
     {:valid? (empty? errors)
      :files (count files)
      :external (count external)
      :errors errors})))

(defn -main [& _]
  (let [result (validate)]
    (prn result)
    (when-not (:valid? result)
      (System/exit 1))))
