(ns kototama.transport-provider-test
  (:require [clojure.test :refer [deftest is]]
            [kototama.transport-provider :as transport]))

(deftest transport-admission-uses-shared-four-axis-abac
  (let [attributes {:subject {:id :workload/payment :tenant "alpha"}
                    :resource {:tenant "alpha" :trust :private-service}
                    :environment {:surface :fleet :network-zone :prod
                                  :device-trusted? true}}
        policy {:policy/id :transport/payment-db
                :subject/ids #{:workload/payment}
                :resource/ids #{"db.internal:5432"}
                :resource/tenants #{"alpha"}
                :resource/trust #{:private-service}
                :action/ids #{:transport/connect}
                :action/capabilities #{:transport-connect}
                :environment/surfaces #{:fleet}
                :environment/network-zones #{:prod}
                :environment/require-device-trust? true
                :tenant/isolation? true}
        allowed (transport/transport-decision policy attributes "db.internal" 5432)
        denied (transport/transport-decision policy attributes "metadata.internal" 80)]
    (is (true? (:abac/allowed? allowed)))
    (is (= :transport/payment-db (:abac/policy-id allowed)))
    (is (false? (:abac/allowed? denied)))
    (is (= [:resource-id] (mapv :abac/control (:abac/violations denied))))))

(deftest transport-egress-prevents-implicit-downgrade
  (let [context {:subject :workload/payment :purpose :database-sync
                 :now "2026-07-19T12:00:00Z"
                 :input-classifications [:restricted]
                 :output-classification :internal}
        denied (transport/egress-decision context)
        grant {:id :redacted-export :subject :workload/payment
               :purpose :database-sync :from :restricted :to :internal
               :expires-at "2026-07-20T00:00:00Z"}
        allowed (transport/egress-decision
                 (assoc context :declassification-grant grant))]
    (is (false? (:information-flow/allowed? denied)))
    (is (true? (:information-flow/allowed? allowed)))))

(deftest production-transport-rejects-hybrid-label-without-pq-component
  (let [policy {:kotoba.security/crypto-policy-version 1
                :mode :hybrid-required :hybrid-epoch-floor 1}
        envelope {:envelope/provider {:provider/id :kagi
                                      :provider/fips-validated false}
                  :envelope/kem? true :envelope/hybrid? true
                  :envelope/epoch 2
                  :envelope/algorithms [:x25519 :ml-kem-768]}]
    (is (:valid? (transport/crypto-decision policy envelope)))
    (is (false? (:valid?
                 (transport/crypto-decision
                 policy (assoc envelope :envelope/algorithms [:x25519])))))))

(deftest production-transport-requires-hardware-backed-client-signing
  (let [evidence {:provider-id :apple-secure-enclave
                  :hardware-backed? true :provider-origin-verified? true
                  :private-exported? false :sign-verified? true
                  :unavailable-failed-closed? true}]
    (is (:hardware-signing/qualified?
         (transport/enforce-hardware-signing! true evidence)))
    (doseq [bad [(assoc evidence :private-exported? true)
                 (assoc evidence :provider-origin-verified? false)
                 (assoc evidence :unavailable-failed-closed? false)]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"hardware signing policy denies"
           (transport/enforce-hardware-signing! true bad))))
    (is (false? (:hardware-signing/qualified?
                 (transport/enforce-hardware-signing!
                  false (assoc evidence :private-exported? true))))
        "development profile may observe failure but production required mode denies")))
