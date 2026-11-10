(ns quota-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]))

(defn run-node-eval [js]
  (let [{:keys [exit out err]} (sh "node" "--input-type=module" "-e" js)]
    (if (zero? exit) (json/parse-string out true)
        (throw (ex-info (str "Node eval error: " err) {:out out})))))

(deftest split-streamed-credit-error-is-withheld-and-spliced
  (let [res (run-node-eval
    "import {executeWithFallback} from './src/router/client.js';
     import {createMetricsStore} from './src/router/metrics.js';
     const c=(model,provider)=>({modelId:model,providerId:provider,quote:{prompt:.1,completion:.2},savingsPct:90});
     const candidates=[c('oai/gpt-6.1-sol','a'),c('oai/gpt-6.1-sol','b'),c('oai/gpt-6.1-terra','c')];
     const calls=[];
     const frame=(delta)=>'data: '+JSON.stringify({choices:[{delta}]})+'\\n\\n';
     const fetchFn=async (_,opts)=>{
       const model=JSON.parse(opts.body).model; calls.push(model);
       const parts=model.includes('-sol')
         ? [': heartbeat\\n\\n',frame({role:'assistant',content:''}),frame({content:'[Cod'}),
           frame({content:'ex error: credit_'}),frame({content:'balance_exhausted: You have no credits remaining.]'}),
            'data: [DONE]\\n\\n']
         : [frame({content:'healthy fallback'}),'data: [DONE]\\n\\n'];
       return new Response(parts.join(''),{status:200,headers:{'Content-Type':'text/event-stream'}});
     };
     const r=await executeWithFallback({candidates,requestBody:{stream:true,messages:[]},apiKey:'test',
       metricsStore:createMetricsStore(),fetchFn});
     const body=await new Response(r.stream).text();
     console.log(JSON.stringify({body,calls,metrics:r.getMetrics()}));")]
    (is (.contains (:body res) "healthy fallback"))
    (is (not (.contains (:body res) "credit_balance_exhausted")))
    (is (not (.contains (:body res) "[Cod")) "partial quota text is never shown")
    (is (= 1 (count (re-seq #"\[DONE\]" (:body res)))))
    (is (= ["oai/gpt-6.1-sol" "oai/gpt-6.1-terra"] (:calls res))
        "account-level exhaustion skips repeated nodes for that model")
    (is (= "oai/gpt-6.1-terra" (get-in res [:metrics :servedModel])))))

(deftest json-credit-error-is-a-failure-not-an-answer
  (let [res (run-node-eval
    "import {executeWithFallback} from './src/router/client.js';
     import {createMetricsStore} from './src/router/metrics.js';
     const c=(model,provider)=>({modelId:model,providerId:provider,quote:{prompt:.1,completion:.2},savingsPct:90});
     const candidates=[c('oai/gpt-6.1-sol','a'),c('oai/gpt-6.1-sol','b'),c('oai/gpt-6.1-terra','c')];
     const calls=[];
     const fetchFn=async (_,opts)=>{
       const model=JSON.parse(opts.body).model; calls.push(model);
       const content=model.includes('-sol') ? '[Codex error: credit_balance_exhausted: You have no credits remaining.]' : 'healthy fallback';
       return new Response(JSON.stringify({choices:[{message:{role:'assistant',content}}]}),{status:200,headers:{'Content-Type':'application/json'}});
     };
     const r=await executeWithFallback({candidates,requestBody:{stream:false,messages:[]},apiKey:'test',
       metricsStore:createMetricsStore(),fetchFn});
     console.log(JSON.stringify({success:r.success,content:r.responseBody?.choices?.[0]?.message?.content,calls}));")]
    (is (:success res))
    (is (= "healthy fallback" (:content res)))
    (is (= ["oai/gpt-6.1-sol" "oai/gpt-6.1-terra"] (:calls res)))))

(deftest genuine-code-discussion-is-not-filtered
  (let [res (run-node-eval
    "import {executeWithFallback} from './src/router/client.js';
     import {createMetricsStore} from './src/router/metrics.js';
     const msg='[Codex error: credit_balance_exhausted] can appear in logs.';
     const fetchFn=async ()=>new Response(JSON.stringify({choices:[{message:{content:msg}}]}),{status:200,headers:{'Content-Type':'application/json'}});
     const r=await executeWithFallback({candidates:[{modelId:'ali/glm-5.3',providerId:'p'}],
       requestBody:{stream:false},metricsStore:createMetricsStore(),fetchFn});
     console.log(JSON.stringify({success:r.success,content:r.responseBody?.choices?.[0]?.message?.content}));")]
    (is (:success res))
    (is (= "[Codex error: credit_balance_exhausted] can appear in logs." (:content res)))))
