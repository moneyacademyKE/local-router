;; Live health probe of every auto-chain member: one tiny completion each.
;; Prints status, elapsed ms, and a short error snippet — never the key.
(require '[babashka.http-client :as http]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(defn strip-quotes [s] (str/replace s #"^\"|\"$" ""))
(defn clip [s n] (let [s (or s "")] (if (> (count s) n) (subs s 0 n) s)))

(def vars
  (->> (slurp ".dev.vars")
       str/split-lines
       (map #(str/split % #"=" 2))
       (filter #(= 2 (count %)))
       (map (fn [[k v]] [k (strip-quotes v)]))
       (into {})))

(def api-key (get vars "INFERHUB_API_KEY"))
(def base (or (get vars "INFERHUB_BASE_URL") "https://api.inferhub.dev/v1"))

(def models
  ["grok/grok-4.7"
   "ag/gemini-4-carbon"
   "cx/gpt-6-astra"
   "oai/gpt-6.1-sol"
   "cx/gpt-6.1-terra"
   "oai/gpt-6.1-terra"
   "ali/kimi-k3.1-pro"
   "ali/glm-5.3"])

(println "model                       status   ms      note")
(doseq [m models]
  (let [t0 (System/currentTimeMillis)
        out (try
              (let [r (http/post (str base "/chat/completions")
                                 {:headers {"Authorization" (str "Bearer " api-key)
                                            "Content-Type" "application/json"
                                            "x-max-output-price" "1"}
                                  :body (json/generate-string
                                          {:model m
                                           :messages [{:role "user" :content "Say OK"}]
                                           :max_tokens 8
                                           :stream false})
                                  :timeout 45000})]
                {:status (:status r)
                 :note (when (not= 200 (:status r))
                         (-> (try (json/parse-string (:body r) true) (catch Exception _ nil))
                             :error :message
                             (or "")
                             (str/replace #"\s+" " ")
                             (clip 80)))})
              (catch Exception e {:status "ERR" :note (clip (str (.getMessage e)) 80)}))
        ms (- (System/currentTimeMillis) t0)]
    (println (format "%-26s %-8s %-7d %s" m (str (:status out)) ms (or (:note out) "")))))
