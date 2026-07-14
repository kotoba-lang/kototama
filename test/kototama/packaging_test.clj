(ns kototama.packaging-test
  "R3 packaging gate: systemd units + daemon wrapper without root."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest packaging-validate-script-passes
  (let [{:keys [exit out err]} (shell/sh "bash" "deploy/validate-packaging.sh")]
    (is (zero? exit) (str "stdout=" out " stderr=" err))))

(deftest staging-smoke-script-exists-and-is-documented
  (is (.exists (io/file "deploy/staging-smoke.sh")))
  (let [sh (slurp "deploy/staging-smoke.sh")]
    (is (str/includes? sh "fleet-gate"))
    (is (str/includes? sh "validate-packaging"))
    (is (str/includes? sh "kototama-fleet-daemon"))))

(deftest service-unit-is-oneshot-bounded
  (let [svc (slurp "deploy/systemd/kototama-fleet-daemon.service")]
    (is (str/includes? svc "Type=oneshot"))
    (is (str/includes? svc "kototama-fleet-daemon"))
    (is (str/includes? svc "max-passes"))
    (is (not (re-find #"(?i)Restart=always" svc))
        "must not be a forever-restart service — oneshot+timer only")))

(deftest timer-unit-activates-service
  (let [t (slurp "deploy/systemd/kototama-fleet-daemon.timer")]
    (is (str/includes? t "kototama-fleet-daemon.service"))
    (is (or (str/includes? t "OnUnitActiveSec=")
            (str/includes? t "OnCalendar=")))))

(deftest daemon-wrapper-dry-run-fact-guest
  (testing "same path systemd ExecStart uses (bounded 1 pass)"
    (let [root (str "tmp/packaging-daemon-" (System/currentTimeMillis))
          {:keys [exit out err]}
          (shell/sh "bash" "deploy/bin/kototama-fleet-daemon"
                    "--wasm" "test/kototama/fixtures/kotoba-compiled-fact.wasm"
                    "--root" root
                    "--interval-ms" "0"
                    "--max-passes" "1"
                    "--max-ticks" "1")]
      (is (zero? exit) (str "out=" out " err=" err))
      (doseq [f (reverse (file-seq (io/file root)))]
        (.delete f))
      (when (.exists (io/file "tmp/kototama-fleet"))
        ;; wrapper may symlink tmp/kototama-fleet → root; leave dir if foreign
        nil))))
