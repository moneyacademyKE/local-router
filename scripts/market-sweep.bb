;; Market-wide sweep: dump every InferHub model with pricing + limits.
;; Key comes from .dev.vars at runtime; never printed.
(require '[cheshire.core :as json]
         '[clojure.string :as str])

(def repo-dir (System/getenv "REPO_DIR"))

(defn strip-quotes [s] (str/replace s #"^\"|\"$" ""))

(defn env-map [path]
  (->> (slurp path)
       str/split-lines
       (map #(str/split % #"=" 2))
       (filter #(= 2 (count %)))
       (map (fn [[k v]] [k (strip-quotes v)]))
       (into {})))

(def vars (env-map (str repo-dir "/.dev.vars")))
(def key (get vars "INFERHUB_API_KEY"))

(require '[babashka.http-client :as http])

(def feed
  (-> (http/get "https://api.inferhub.dev/v1/models"
                {:headers {"Authorization" (str "Bearer " key)
                           "Accept" "application/json"}})
      :body
      (json/parse-string true)))

(def models (:data feed))
(println "FEED_TOTAL" (count models))
(when (first models)
  (println "SAMPLE_KEYS" (pr-str (keys (first models))))
  (println "SAMPLE" (pr-str (dissoc (first models) :description :pricing_text))))

(defn num [x] (when x (if (number? x) x (try (Double/parseDouble (str x)) (catch Exception _ nil)))))

;; real feed shape: pricing.asks_in / asks_out arrays + min_ask + official_in/out
(defn p [m k] (get-in m [:pricing k]))

(def rows
  (for [m models]
    (let [asks-out (sort (keep num (p m :asks_out)))
          asks-in (sort (keep num (p m :asks_in)))]
      {:id (:id m)
       :label (:upstream_label m)
       :ctx (:input_token_limit m)
       :modality (:modality m)
       :owned_by (:owned_by m)
       :official_in (p m :official_in)
       :official_out (p m :official_out)
       :min_in (or (num (p m :min_ask_in)) (first asks-in))
       :min_out (or (num (p m :min_ask_out)) (first asks-out))
       :n (count asks-out)
       :n_in (count asks-in)})))

(def clean (remove #(str/blank? (str (:id %))) rows))
(println "PARSEABLE" (count clean))
(println "WITH_ASKS" (count (filter #(pos? (:n %)) clean)))
(spit (str repo-dir "/.market-sweep.json")
      (json/generate-string clean {:pretty true}))
(spit (str repo-dir "/.market-asks.json")
      (json/generate-string
        (for [m models]
          {:id (:id m) :asks_out (get-in m [:pricing :asks_out])})))
(println "WROTE .market-sweep.json .market-asks.json")
