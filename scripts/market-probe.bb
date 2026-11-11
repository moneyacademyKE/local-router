;; Probe account status (enabled/disabled) of shortlisted left-out models.
;; Direct to InferHub, tiny completions, key from .dev.vars at runtime.
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
  ["ag/gemini-4-argon"
   "ag/claude-sonnet-5-5"
   "ag/claude-sonnet-4-6"
   "ag/claude-opus-4-6-thinking"
   "cc/claude-haiku-5-5"
   "grok/grok-4.7"
   "cb/glm-5.3"
   "cb/deepseek-v4.1-flash"
   "cb/hy4-preview"
   "cb/kimi-k3"
   "cx/gpt-6-luna"])

(doseq [m models]
  (try
    (let [r (http/post "https://api.inferhub.dev/v1/chat/completions"
                       {:headers {"Authorization" (str "Bearer " key)
                                  "Content-Type" "application/json"}
                        :body (json/generate-string
                                {:model m
                                 :messages [{:role "user" :content "Say OK"}]
                                 :max_tokens 10
                                 :stream false})
                        :timeout 60000})]
      (let [body (try (:body r) (catch Exception _ ""))
            j (try (json/parse-string body true) (catch Exception _ nil))
            content (get-in j [:choices 0 :message :content])
            err (get-in j [:error :message])]
        (println (str m "|HTTP" (:status r) "|"
                       (if content (str "SERVED: " (subs (str content) 0 (min 20 (count (str content))))) (or err (str "body:" (subs (str body) 0 (min 80 (count (str body)))))))))))
    (catch Exception e
      (println (str m "|EXC|" (subs (str (.getMessage e)) 0 (min 80 (count (str (.getMessage e))))))))))

;; frontier check: what intelligence leaders exist on the market at all?
(println ";; --- frontier models present in feed (any price) ---")
(let [rows (json/parse-string (slurp ".market-sweep.json") true)
      names ["gemini-4-carbon" "claude-opus-5" "claude-fable" "gpt-6-astra" "muse-wonder" "claude-opus-4-8" "claude-opus-4-7"]
      hits (filter (fn [r] (some #(str/includes? (str (:id r)) %) names)) rows)]
  (doseq [r hits]
    (println (:id r) "|min_out" (:min_out r) "|asks" (:n r) "|label" (:label r))))
