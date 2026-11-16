(ns soak-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest scheduled-probe-records-bounded-stream-integrity
  (fs/with-temp-dir [dir {}]
    (let [bin (fs/create-dir (fs/path dir "bin"))
          state (fs/create-dir (fs/path dir "state"))
          curl (fs/path bin "curl")
          env {"HOME" (str dir) "INFERED_STATE_ROOT" (str state)
               "PATH" (str bin ":/usr/bin:/bin:/opt/homebrew/bin")}
          run-probe #(sh (str (fs/which "bb")) "scripts/local-router-soak.bb" :env env)]
      (spit (str curl) "#!/bin/sh\nprintf 'data: {}\\n\\ndata: [DONE]\\n\\n__HTTP_STATUS__:200\\n'\n")
      (fs/set-posix-file-permissions curl "rwx------")
      (is (zero? (:exit (run-probe))))
      (spit (str curl) "#!/bin/sh\nprintf 'data: {}\\n\\n__HTTP_STATUS__:200\\n'\n")
      (is (not (zero? (:exit (run-probe)))))
      (let [events (mapv #(json/parse-string % true)
                         (str/split-lines (slurp (str (fs/path state "soak-probes.jsonl")))))]
        (is (= [true false] (mapv :ok events)))
        (is (= [1 0] (mapv :done_markers events)))
        (is (= [200 200] (mapv :http events)))))))
