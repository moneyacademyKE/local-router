(ns telemetry-test
  (:require [cheshire.core :as json]
            [clojure.java.shell :refer [sh]]
            [clojure.test :refer [deftest is]]))

(deftest no-candidate-failure-is-persisted-to-a-real-sqlite-ledger
  (let [{:keys [exit out err]} (sh "node" "test/no_candidate_telemetry.js")]
    (is (zero? exit) err)
    (when (zero? exit)
      (let [{:keys [status row analytics]} (json/parse-string out true)]
        (is (= 400 status))
        (is (= "infered/auto" (:requested_model row)))
        (is (= "unserved" (:selected_model row)))
        (is (= 0 (:ok row)))
        (is (= 0 (:attempts row)))
        (is (= 3 (get-in analytics [:switching :errors])) "failures are ok=0, regardless of attempts")
        (is (= 1 (get-in analytics [:switching :directHits])) "cache hits and failures are not direct hits")
        (is (= 1 (get-in analytics [:switching :failovers])) "failed retries are not absorbed failovers")
        (is (= 0 (:flappingSessions analytics)) "unserved is not a model switch")
        (is (= ["test/model"] (mapv :model (:topModels analytics))) "unserved is not a performing model")))))
