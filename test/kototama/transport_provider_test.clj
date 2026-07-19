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
