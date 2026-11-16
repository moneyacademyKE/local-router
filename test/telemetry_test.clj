(ns telemetry-test
  (:require [cheshire.core :as json]
            [clojure.java.shell :refer [sh]]
            [clojure.test :refer [deftest is]]))

(deftest no-candidate-failure-is-persisted-to-a-real-sqlite-ledger
  (let [{:keys [exit out err]} (sh "node" "test/no_candidate_telemetry.js")]
    (is (zero? exit) err)
    (when (zero? exit)
      (let [{:keys [status row]} (json/parse-string out true)]
        (is (= 400 status))
        (is (= "infered/auto" (:requested_model row)))
        (is (= "unserved" (:selected_model row)))
        (is (= 0 (:ok row)))
        (is (= 0 (:attempts row)))))))
