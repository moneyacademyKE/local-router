(ns local-runtime-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [local.watchdog :as watchdog]))

(deftest watchdog-restart-policy
  (testing "a transient failure does not restart a healthy service"
    (is (= {:failures 1 :restarts-ms [] :restart? false}
           (watchdog/advance {} false 1000)))
    (is (= {:failures 0 :restarts-ms [] :restart? false}
           (watchdog/advance {:failures 2} true 2000))))
  (testing "three consecutive failures request exactly one restart"
    (is (= {:failures 0 :restarts-ms [3000] :restart? true}
           (watchdog/advance {:failures 2 :restarts-ms []} false 3000))))
  (testing "restart attempts are capped for an hour and recover when the window expires"
    (let [state {:failures 2 :restarts-ms [1000 2000 3000]}]
      (is (= {:failures 3 :restarts-ms [1000 2000 3000] :restart? false}
             (watchdog/advance state false 4000)))
      (is (= {:failures 0 :restarts-ms [2000 3000 3601001] :restart? true}
             (watchdog/advance state false 3601001))))))

(deftest startup-uses-one-explicit-persistent-state
  (let [script (slurp "scripts/local-router-start.sh")]
    (is (not (clojure.string/includes? script "/Desktop/")))
    (is (= 2 (count (re-seq #"--persist-to \"\$PERSIST_DIR\"" script))))
    (is (= 2 (count (re-seq #"wrangler@4\.163\.0" script))))))

(deftest startup-refuses-to-create-a-fresh-ledger-by-accident
  (fs/with-temp-dir [dir {}]
    (let [{:keys [exit]} (sh "/bin/sh" "scripts/local-router-start.sh"
                             :env {"HOME" (str dir) "INFERED_ROOT" (str dir)
                                   "INFERED_STATE_ROOT" (str dir "/state")})
          log (slurp (str dir "/state/dev-server.log"))]
      (is (not (zero? exit)))
      (is (str/includes? log "refusing to create an empty ledger"))
      (is (not (fs/exists? (str dir "/state/wrangler-state/v3/d1")))))))

(deftest startup-rejects-an-empty-d1-directory
  (fs/with-temp-dir [dir {}]
    (fs/create-dirs (fs/path dir "state/wrangler-state/v3/d1"))
    (let [{:keys [exit]} (sh "/bin/sh" "scripts/local-router-start.sh"
                             :env {"HOME" (str dir) "INFERED_ROOT" (str dir)
                                   "INFERED_STATE_ROOT" (str dir "/state")})]
      (is (not (zero? exit)))
      (is (str/includes? (slurp (str dir "/state/dev-server.log"))
                         "refusing to create an empty ledger")))))

(deftest startup-rejects-an-unrelated-database
  (fs/with-temp-dir [dir {}]
    (let [databases (fs/create-dirs (fs/path dir "state/wrangler-state/v3/d1/miniflare-D1DatabaseObject"))
          db (str (fs/path databases "not-the-router.sqlite"))]
      (is (zero? (:exit (sh "sqlite3" db "CREATE TABLE unrelated (id INTEGER)"))))
      (let [{:keys [exit]} (sh "/bin/sh" "scripts/local-router-start.sh"
                               :env {"HOME" (str dir) "INFERED_ROOT" (str dir)
                                     "INFERED_STATE_ROOT" (str dir "/state")})]
        (is (not (zero? exit)))
        (is (str/includes? (slurp (str dir "/state/dev-server.log"))
                           "refusing to create an empty ledger"))))))

(deftest watchdog-cli-only-restarts-after-bounded-failures
  (fs/with-temp-dir [dir {}]
    (let [bin (fs/create-dir (fs/path dir "bin"))
          state (fs/create-dir (fs/path dir "state"))
          curl (fs/path bin "curl")
          launchctl (fs/path bin "launchctl")
          env {"HOME" (str dir) "INFERED_STATE_ROOT" (str state)
               "PATH" (str bin ":/usr/bin:/bin:/opt/homebrew/bin")}
          run-watchdog #(sh (str (fs/which "bb")) "-cp" "src"
                            "scripts/local-router-watchdog.bb" :env env)]
      (spit (str curl) "#!/bin/sh\nexit 7\n")
      (spit (str launchctl) "#!/bin/sh\nprintf 'restart\\n' >> \"$INFERED_STATE_ROOT/restarts\"\n")
      (fs/set-posix-file-permissions curl "rwx------")
      (fs/set-posix-file-permissions launchctl "rwx------")
      (dotimes [_ 2] (is (zero? (:exit (run-watchdog)))))
      (is (not (fs/exists? (fs/path state "restarts"))))
      (dotimes [_ 10] (is (zero? (:exit (run-watchdog)))))
      (is (= 3 (count (str/split-lines (slurp (str (fs/path state "restarts")))))))
      (spit (str curl) "#!/bin/sh\nprintf '{\"status\":\"healthy\"}'\n")
      (is (zero? (:exit (run-watchdog))))
      (is (zero? (:failures (edn/read-string (slurp (str (fs/path state "watchdog.edn"))))))))))
