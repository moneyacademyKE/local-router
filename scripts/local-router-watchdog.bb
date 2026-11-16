#!/usr/bin/env bb
(require '[babashka.fs :as fs]
         '[babashka.process :as process]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[local.watchdog :as watchdog])

(def state-dir (or (System/getenv "INFERED_STATE_ROOT")
                   (str (System/getProperty "user.home") "/.opencrabs/state/infered-local")))
(def state-file (str state-dir "/watchdog.edn"))
(def log-file (str state-dir "/watchdog.log"))
(defn log! [event]
  (spit log-file (str (pr-str (assoc event :at (str (java.time.Instant/now)))) "\n") :append true))

(defn healthy? []
  (try
    (let [{:keys [exit out]} (process/shell {:out :string :err :string :continue true}
                                            "curl" "--fail" "--silent" "--show-error"
                                            "--max-time" "5" "http://127.0.0.1:8790/v1/health")]
      (and (zero? exit) (= "healthy" (:status (json/parse-string out true)))))
    (catch Exception _ false)))

(fs/create-dirs state-dir)
(let [start (System/currentTimeMillis)
      state (if (fs/exists? state-file) (edn/read-string (slurp state-file)) {})
      healthy (healthy?)
      next-state (watchdog/advance state healthy start)
      uid (clojure.string/trim (:out (process/shell {:out :string} "id" "-u")))]
  ;; Persist the restart reservation before asking launchd, so failures count too.
  (let [tmp (str (fs/create-temp-file {:dir state-dir :prefix "watchdog-"}))]
    (spit tmp (pr-str (dissoc next-state :restart?)))
    (fs/move tmp state-file {:replace-existing true}))
  (log! {:healthy healthy :health-ms (- (System/currentTimeMillis) start)
         :failures (:failures next-state) :restart? (:restart? next-state)})
  (when (:restart? next-state)
    (let [{:keys [exit err]} (process/shell {:out :string :err :string :continue true}
                                            "launchctl" "kickstart" "-k"
                                            (str "gui/" uid "/dev.moneyacad.infered"))]
      (log! {:event :restart :exit exit :error (when-not (zero? exit) err)})
      (when-not (zero? exit) (System/exit 1)))))
