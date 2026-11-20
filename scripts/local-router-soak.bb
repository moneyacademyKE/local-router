#!/usr/bin/env bb
;; One soak probe through the local router. Records http status, [DONE] markers,
;; elapsed, AND which chain member actually served it (x-infered-selected-model)
;; with the attempt count — a soak that cannot name the served member cannot tell
;; a healthy head from a silent fallback.
;;
;; Env knobs:
;;   SOAK_MODEL      request model (default infered/auto)
;;   SOAK_MAX_PRICE  body max_price, i.e. cap the bid for this probe only
;;   SOAK_SESSION    x-session-id tag (default soak-chain)
;;   INFERED_STATE_ROOT  state dir (default ~/.opencrabs/state/infered-local)
(require '[babashka.fs :as fs]
         '[babashka.process :as process]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(def state-dir (or (System/getenv "INFERED_STATE_ROOT")
                   (str (System/getProperty "user.home") "/.opencrabs/state/infered-local")))
(def model (or (System/getenv "SOAK_MODEL") "infered/auto"))
(def session (or (System/getenv "SOAK_SESSION") "soak-chain"))
(def max-price (some-> (System/getenv "SOAK_MAX_PRICE") parse-double))
(def payload (json/generate-string
               (cond-> {:model model
                        :messages [{:role "user" :content "Reply OK."}]
                        :max_tokens 8 :stream true}
                 max-price (assoc :max_price max-price))))
(def hdr-file (str (fs/create-temp-file {:prefix "soak-hdr" :suffix ".txt"})))
(def started (System/nanoTime))
(def result
  (try
    (process/shell {:out :string :err :string :continue true}
                   "curl" "--silent" "--show-error" "--fail" "--no-buffer"
                   "--max-time" "45" "--max-filesize" "65536"
                   "--dump-header" hdr-file
                   "--write-out" "\n__HTTP_STATUS__:%{http_code}\n"
                   "--header" "Content-Type: application/json"
                   "--header" (str "X-Session-ID: " session)
                   "--data-raw" payload "http://127.0.0.1:8790/v1/chat/completions")
    (catch Exception e {:exit 127 :out "" :err (.getMessage e)})))
(def output (:out result ""))
(def status (some-> (re-find #"(?m)^__HTTP_STATUS__:(\d{3})$" output) second parse-long))
(def markers (count (re-seq #"(?m)^data: \[DONE\]\s*$" output)))
(def headers (try (str/lower-case (slurp hdr-file)) (catch Exception _ "")))
(defn hdr [k]
  (some-> (re-find (re-pattern (str "(?m)^" k ":\\s*(\\S+)")) headers) second))
(def event {:at_eat (str (java.time.ZonedDateTime/now (java.time.ZoneId/of "Africa/Nairobi")))
            :model model
            :max_price max-price
            :served (hdr "x-infered-selected-model")
            :attempts (some-> (hdr "x-infered-attempts") parse-long)
            :latency_ms_header (some-> (hdr "x-infered-latency-ms") parse-long)
            :http status :done_markers markers
            :elapsed_ms (long (/ (- (System/nanoTime) started) 1000000))
            :ok (and (zero? (:exit result)) (= 200 status) (= 1 markers))})
(fs/delete-if-exists hdr-file)
(fs/create-dirs state-dir)
(spit (str state-dir "/soak-probes.jsonl") (str (json/generate-string event) "\n") :append true)
(println (json/generate-string event))
(when-not (:ok event) (System/exit 1))
