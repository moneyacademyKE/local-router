(ns local.watchdog)

(def failure-threshold 3)
(def max-restarts-per-hour 3)
(def hour-ms (* 60 60 1000))

(defn advance
  "Advance the health policy. A restart is permitted only within the hourly budget."
  [state healthy? now-ms]
  (let [recent (->> (:restarts-ms state [])
                    (filter #(> % (- now-ms hour-ms)))
                    vec)
        failures (if healthy? 0 (inc (:failures state 0)))
        restart? (and (>= failures failure-threshold)
                      (< (count recent) max-restarts-per-hour))]
    {:failures (if restart? 0 failures)
     :restarts-ms (if restart? (conj recent now-ms) recent)
     :restart? restart?}))
