(ns kototama.packaging-test
  "Packaging gate for the component-authority receiver. Fleet daemon
   packaging moved to kotoba-lang/fleet under ADR-2607266000."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest packaging-validate-script-passes
  (let [{:keys [exit out err]} (shell/sh "bash" "deploy/validate-packaging.sh")]
    (is (zero? exit) (str "stdout=" out " stderr=" err))))

(deftest authority-service-is-contained
  (let [svc (slurp "deploy/systemd/kototama-authority-daemon.service")]
    (is (str/includes? svc "Type=simple"))
    (is (str/includes? svc "NoNewPrivileges=true"))
    (is (str/includes? svc "ProtectSystem=strict"))))
