(ns latency-test
  (:require [cheshire.core :as json]
            [clojure.java.shell :refer [sh]]
            [clojure.test :refer [deftest is testing]]))

(defn run-scenario [scenario]
  (let [{:keys [exit out err]} (sh "node" "test/latency_fixture.js" scenario)]
    (when-not (zero? exit) (throw (ex-info err {:exit exit :out out})))
    (json/parse-string out true)))

(deftest ledger-includes-all-attempts
  (doseq [scenario ["nonstream" "stream" "failure"]]
    (testing scenario
      (let [{:keys [status row calls hasDone]} (run-scenario scenario)]
        (is (= 2 calls))
        (is (= 2 (:attempts row)))
        (is (= 42590 (:latency_ms row)) "ledger reports full request time, not just final attempt")
        (if (= scenario "failure")
          (do (is (= 503 status)) (is (= 0 (:ok row))) (is (= "unserved" (:selected_model row))))
          (do (is (= 200 status)) (is (= 1 (:ok row))) (is (= "cx/gpt-6.2-sol" (:selected_model row)))))
        (when (= scenario "stream") (is hasDone))))))
