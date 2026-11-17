;; Streaming probe: does grok accept stream:true when it accepts stream:false?
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
(def api-key (get vars "INFERHUB_API_KEY"))
(def base (or (get vars "INFERHUB_BASE_URL") "https://api.inferhub.dev/v1"))

(doseq [[label stream?] [["stream=false" false] ["stream=true" true]]
        m ["grok/grok-4.7" "ali/kimi-k3.1-pro"]]
  (let [t0 (System/currentTimeMillis)
        out (try
              (let [r (http/post (str base "/chat/completions")
                                 {:headers {"Authorization" (str "Bearer " api-key)
                                            "Content-Type" "application/json"
                                            "x-max-output-price" "0.999999"}
                                  :body (json/generate-string
                                          {:model m :stream stream?
                                           :messages [{:role "user" :content "Reply OK."}]
                                           :max_tokens 8})
                                  :timeout 45000})]
                (str "status=" (:status r) " body=" (subs (str (:body r)) 0 (min 90 (count (str (:body r)))))))
              (catch Exception e (str "ERR " (.getMessage e))))]
    (println (format "%-22s %-13s %6dms  %s" m label (- (System/currentTimeMillis) t0) out))))
