;; Probe account status of left-out candidates: SERVED / DISABLED / CAPACITY / ERROR.
(require '[babashka.http-client :as http]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(defn strip-quotes [s] (str/replace s #"^\"|\"$" ""))

(def vars
  (->> (slurp ".dev.vars")
       str/split-lines
       (map #(str/split % #"=" 2))
       (filter #(= 2 (count %)))
       (map (fn [[k v]] [k (strip-quotes v)]))
       (into {})))

(def key (get vars "INFERHUB_API_KEY"))

(def models
  ["ag/gemini-4-argon" "ag/claude-sonnet-5-5" "ag/claude-sonnet-4-6"
   "ag/claude-opus-4-6-thinking" "cc/claude-haiku-5-5" "cb/glm-5.3"
   "cb/kimi-k3" "ag/gemini-4-carbon" "cc/claude-opus-5-5"
   "cc/claude-opus-4-7" "cb/deepseek-v4.1-flash" "cb/hy4-preview"
   "cb/glm-5.3-flash" "cb/minimax-m3.1-flash" "cb/kimi-k2.8-preview"
   "cbcn/kimi-k2.7" "ali/deepseek-v4-pro-0813" "ag/gemini-3.8-flash"])

(doseq [m models]
  (let [result
        (try
          (let [r (http/post "https://api.inferhub.dev/v1/chat/completions"
                             {:headers {"Authorization" (str "Bearer " key)
                                        "Content-Type" "application/json"}
                              :body (json/generate-string
                                      {:model m
                                       :messages [{:role "user" :content "Say OK"}]
                                       :max_tokens 10 :stream false})
                              :timeout 90000})]
            [:ok (:status r) (str (:body r))])
          (catch Exception e
            (let [d (ex-data e)]
              [:exc (or (:status d) "?") (or (:body d) (str (.getMessage e)))])))]
    (let [[kind status body] result
          j (try (json/parse-string body true) (catch Exception _ nil))
          content (get-in j [:choices 0 :message :content])
          err-code (get-in j [:error :type])
          err-msg (get-in j [:error :message])]
      (println (str m " | " status " | "
                    (cond
                      content (str "SERVED: " (subs (str content) 0 (min 12 (count (str content)))))
                      (= err-code "model_disabled") "DISABLED (dashboard toggle)"
                      (= err-code "no_capacity") "CAPACITY (503, try later)"
                      :else (str (or err-code "?") " " (subs (str (or err-msg body)) 0 (min 90 (count (str (or err-msg body))))))))))))
