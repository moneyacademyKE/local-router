;; Gap analysis: models NOT in the catalog, ranked by suitability.
(require '[cheshire.core :as json]
         '[clojure.string :as str])

(def rows (json/parse-string (slurp ".market-sweep.json") true))

(def catalog-universe
  #{"cx/gpt-6-astra" "oai/gpt-6.1-sol" "oai/gpt-6.1-terra" "cx/gpt-6.1-terra"
    "ali/kimi-k3.1-pro" "ali/qwen3.8-max-0902" "ali/glm-5.3" "zai/glm-5.3"})

(defn family [id] (first (str/split (str id) #"/")))

;; recompute in-cap depth from the full ask ladder: ask <= $0.999999
(def feed-asks
  (->> (json/parse-string (slurp ".market-asks.json") true)
       (map (fn [m] [(keyword (:id m)) (:asks_out m)]))
       (into {})))

(defn in-cap [id]
  (count (filter #(<= (double %) 0.999999) (get feed-asks (keyword id) []))))

(defn fmt [m]
  (println (str/join "|" [(str (:id m)) (str (:label m)) (str (:min_in m))
                          (str (:min_out m)) (str (in-cap (:id m)))
                          (str (:n m)) (str (:ctx m))
                          (str (:official_in m)) (str (:official_out m)) (str (:modality m))])))

(def candidates
  (->> rows
       (remove #(catalog-universe (:id %)))
       (filter #(< (double (or (:min_out %) 999)) 1.0))
       (filter #(>= (in-cap (:id %)) 2))          ; bait rule: 2+ sellers in the low tier
       (filter #(>= (or (:ctx %) 0) 200000))      ; context sanity
       (filter #(str/includes? (str (:modality %)) "text"))))

(println ";; CANDIDATES" (count candidates))
(println ";; --- sorted by in-cap depth desc, then min_out asc ---")
(defn sortkey [m] [(- (in-cap (:id m))) (double (:min_out m))])
(defn sortkey2 [m] [(double (:min_out m)) (- (in-cap (:id m)))])
(doseq [m (take 45 (sort-by sortkey candidates))] (fmt m))
(println ";; --- cheapest first ---")
(doseq [m (take 30 (sort-by sortkey2 candidates))] (fmt m))

(println ";; --- CURRENT CATALOG MODELS (reference) ---")
(doseq [m (sort-by #(double (:min_out %)) (filter #(catalog-universe (:id %)) rows))] (fmt m))

(println ";; --- FAMILY CENSUS ---")
(doseq [[fam n] (sort-by val > (frequencies (map family candidates)))]
  (println fam n))
