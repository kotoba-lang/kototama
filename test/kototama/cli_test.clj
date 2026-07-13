(ns kototama.cli-test
  "Regression coverage for `kototama.cli/cmd-run` -- previously zero (this
  namespace had no test file at all), which is exactly how a live
  capability-admission bypass on the CLI's own \"canonical execute\" path
  went unnoticed: omitting `--grant` used to auto-derive the requested
  capability set from the untrusted guest's OWN declared Wasm imports, so
  a guest could simply ask for gen-keypair/http-post/log-write and get
  them without any operator consent. `cmd-run` must require an explicit
  `--grant` for every import a guest declares; a guest asking for
  capabilities it wasn't granted must be denied, not self-served."
  (:require [clojure.test :refer [deftest is testing]]
            [kototama.cli :as cli]))

(def ^:private gen-keypair-wasm
  "test/kototama/fixtures/kotoba-compiled-gen-keypair.wasm")

(def ^:private host-free-wasm
  "test/kototama/fixtures/kotoba-compiled-fact.wasm")

(deftest run-without-grant-denies-a-guest-that-declares-imports
  (testing "a guest declaring gen_keypair must NOT be able to self-grant it
            by simply asking -- omitting --grant must deny, not silently
            satisfy the import from the guest's own declaration"
    (let [report (cli/cmd-run gen-keypair-wasm [])]
      (is (false? (:ok? report))
          "no --grant means requested is [], so the guest's own gen_keypair
           import must fail to link rather than being silently satisfied"))))

(deftest run-with-explicit-grant-still-works
  (testing "the legitimate path -- an operator explicitly granting the
            capability a guest declares -- must keep working"
    (let [report (cli/cmd-run gen-keypair-wasm ["--grant" "gen-keypair"])]
      (is (true? (:ok? report)))
      (is (= #{:gen-keypair} (get-in report [:caps :grants]))))))

(deftest run-host-free-guest-needs-no-grant
  (testing "a guest with no declared imports at all keeps running with no
            flags -- this fix must not regress the host-free fast path"
    (let [report (cli/cmd-run host-free-wasm [])]
      (is (true? (:ok? report)))
      (is (= [] (:requested report))))))
