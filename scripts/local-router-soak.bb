#!/usr/bin/env bb
(require '[babashka.fs :as fs]
         '[babashka.process :as process]
         '[cheshire.core :as json])

(def state-dir (or (System/getenv "INFERED_STATE_ROOT")
                   (str (System/getProperty "user.home") "/.opencrabs/state/infered-local")))
(def payload (json/generate-string {:model "infered/auto"
                                    :messages [{:role "user" :content "Reply OK."}]
                                    :max_tokens 8 :stream true}))
(def started (System/nanoTime))
(def result
  (try
    (process/shell {:out :string :err :string :continue true}
                   "curl" "--silent" "--show-error" "--fail" "--no-buffer"
                   "--max-time" "45" "--max-filesize" "65536"
                   "--write-out" "\n__HTTP_STATUS__:%{http_code}\n"
                   "--header" "Content-Type: application/json"
                   "--header" "X-Session-ID: bk-3893-soak"
                   "--data-raw" payload "http://127.0.0.1:8790/v1/chat/completions")
    (catch Exception e {:exit 127 :out "" :err (.getMessage e)})))
(def output (:out result ""))
(def status (some-> (re-find #"(?m)^__HTTP_STATUS__:(\d{3})$" output) second parse-long))
(def markers (count (re-seq #"(?m)^data: \[DONE\]\s*$" output)))
(def event {:at_eat (str (java.time.ZonedDateTime/now (java.time.ZoneId/of "Africa/Nairobi")))
            :http status :done_markers markers
            :elapsed_ms (long (/ (- (System/nanoTime) started) 1000000))
            :ok (and (zero? (:exit result)) (= 200 status) (= 1 markers))})
(fs/create-dirs state-dir)
(spit (str state-dir "/soak-probes.jsonl") (str (json/generate-string event) "\n") :append true)
(println (json/generate-string event))
(when-not (:ok event) (System/exit 1))
