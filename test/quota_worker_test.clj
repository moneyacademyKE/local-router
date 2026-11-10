(ns quota-worker-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]))

(defn run-node-eval [js]
  (let [{:keys [exit out err]} (sh "node" "--input-type=module" "-e" js)]
    (if (zero? exit) (json/parse-string out true)
        (throw (ex-info (str "Node eval error: " err) {:out out})))))

(deftest solterra-quota-error-falls-through-the-real-worker
  (let [res (run-node-eval
    "import worker from './src/worker.js';
     const calls=[], waits=[];
     const frame=delta=>'data: '+JSON.stringify({choices:[{delta}]})+'\\n\\n';
     globalThis.fetch=async (url,opts)=>{
       const model=JSON.parse(opts.body).model; calls.push(model);
       const parts=model==='oai/gpt-6.1-sol'
         ? [frame({role:'assistant',content:''}),frame({content:'[Codex error: '}),
            frame({content:'credit_balance_exhausted: You have no credits remaining.]'})]
         : [frame({content:'terra answered'})];
       return new Response(parts.join('')+'data: [DONE]\\n\\n',
         {status:200,headers:{'Content-Type':'text/event-stream'}});
     };
     const request=new Request('https://worker.test/v1/chat/completions',{method:'POST',
       headers:{'Content-Type':'application/json'},body:JSON.stringify({model:'infered/solterra',stream:true,
       messages:[{role:'user',content:'quota-test'}]})});
     const response=await worker.fetch(request,{INFERHUB_API_KEY:'test-live'}, {waitUntil:p=>waits.push(p)});
     const body=await response.text(); await Promise.all(waits);
     console.log(JSON.stringify({status:response.status,body,calls}));")]
    (is (= 200 (:status res)))
    (is (.contains (:body res) "terra answered"))
    (is (not (.contains (:body res) "credit_balance_exhausted")))
    (is (= ["oai/gpt-6.1-sol" "oai/gpt-6.1-terra"] (:calls res)))
    (is (= 1 (count (re-seq #"\[DONE\]" (:body res)))))))
