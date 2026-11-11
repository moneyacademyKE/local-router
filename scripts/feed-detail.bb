;; Print the full raw feed entry for given model ids (args), plus key inventories.
;; Key comes from env INFERHUB_KEY (extracted by the shell, never printed).
(require '[babashka.http-client :as http]
         '[cheshire.core :as json])

(def base (or (System/getenv "INFERHUB_BASE_URL") "https://api.inferhub.dev/v1"))
(def key (System/getenv "INFERHUB_KEY"))

(defn fetch-feed []
  (-> (http/get (str base "/models") {:headers {"Authorization" (str "Bearer " key)}})
      :body (json/parse-string true)))

(defn -main [& ids]
  (let [feed (fetch-feed)
        entries (into {} (map (fn [m] [(m :id) m]) (:data feed)))]
    (println "feed models:" (count (:data feed)))
    (doseq [id ids]
      (let [e (get entries id)]
        (if (nil? e)
          (do (println "\n===" id ": NOT IN FEED ==="))
          (do (println "\n===" id "===")
              (println "entry keys:" (pr-str (keys e)))
              (println "pricing keys:" (pr-str (keys (get e :pricing))))
              (println "full entry:")
              (println (json/generate-string e {:pretty true}))))))))
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
