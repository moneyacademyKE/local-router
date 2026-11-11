(ns auto-worker-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]))

(defn run-node-eval [js]
  (let [{:keys [exit out err]} (sh "node" "--input-type=module" "-e" js)]
    (if (zero? exit) (json/parse-string out true)
        (throw (ex-info (str "Node eval error: " err) {:out out})))))

(deftest worker-auto-keeps-the-bid-on-every-fallback
  (let [res (run-node-eval
             "import worker from './src/worker.js';
              let row = null, feedCalls = 0; const sent = [], promises = [];
              const db={prepare(sql) {return {bind(...args) {return {
                async first() {return sql.includes('auto_chain_snapshot') ? row : null;},
                async run() {if (sql.includes('INSERT INTO auto_chain_snapshot')) row={hour:args[0],payload:args[1]};}
              };}};}};
              globalThis.fetch=async (url, opts) => {
                if (url.endsWith('/models')) {feedCalls++; return new Response(JSON.stringify({data:[
                  {id:'oai/gpt-6.1-sol',pricing:{asks_in:[0.1],asks_out:[0.5, 0.55]}},
                  {id:'ali/qwen3.8-max-0902',pricing:{asks_in:[0.01],asks_out:[0.03, 0.04]}}
                ]}),{status:200});}
                const body=JSON.parse(opts.body);
                sent.push({model:body.model,bid:opts.headers['x-max-output-price'],provider:opts.headers['X-InferHub-Provider']||null});
                if (body.model==='oai/gpt-6.1-sol') return new Response('price moved',{status:402});
                const answer={choices:[{index:0,delta:{content:'safe fallback'},finish_reason:'stop'}]};
                return new Response('data: '+JSON.stringify(answer)+'\\n\\ndata: [DONE]\\n\\n',
                  {status:200,headers:{'Content-Type':'text/event-stream'}});
              };
              const env={ROUTING_DB:db,INFERHUB_API_KEY:'key-from-test',INFERHUB_BASE_URL:'https://example.test/v1'};
              const ctx={waitUntil:p=>promises.push(p)};
              const request=() => new Request('https://worker.test/v1/chat/completions',{method:'POST',
                headers:{'Content-Type':'application/json','X-Infered-Max-Price':'5'},
                body:JSON.stringify({model:'infered/auto',stream:true,messages:[{role:'user',content:'hello'}]})});
              const response=await worker.fetch(request(),env,ctx); const body=await response.text();
              const inspect=await worker.fetch(new Request('https://worker.test/v1/auto'),env,ctx);
              const snapshot=await inspect.json(); await Promise.all(promises);
              console.log(JSON.stringify({status:response.status,body,sent,feedCalls,inspectStatus:inspect.status,
                snapshot:snapshot.models.map(m=>({id:m.modelId,askCount:m.askCount,
                  inCapDepth:m.inCapDepth,depthPrice:m.depthPrice}))}));")]
    (is (= 200 (:status res)))
    (is (.contains (:body res) "safe fallback"))
    (is (= ["oai/gpt-6.1-sol" "ali/qwen3.8-max-0902"] (mapv :model (:sent res))))
    (is (every? #(= "0.999999" (:bid %)) (:sent res)))
    (is (every? #(nil? (:provider %)) (:sent res)) "synthetic node ids are not sent as real upstream sellers")
    (is (= 1 (:feedCalls res)) "the endpoint reads the shared snapshot")
    (is (= 200 (:inspectStatus res)))
    (is (= [{:id "oai/gpt-6.1-sol" :askCount 2 :inCapDepth 2 :depthPrice 0.55}
            {:id "ali/qwen3.8-max-0902" :askCount 2 :inCapDepth 2 :depthPrice 0.04}]
           (:snapshot res))
        "/v1/auto surfaces per-model ask depth: total asks, in-cap sellers, sustained price")))

(deftest worker-auto-fails-closed-without-a-durable-live-snapshot
  (let [res (run-node-eval
             "import worker from './src/worker.js';
              const r=await worker.fetch(new Request('https://worker.test/v1/chat/completions',{method:'POST',
                headers:{'Content-Type':'application/json'},body:JSON.stringify({model:'infered/auto',
                  messages:[{role:'user',content:'hello'}]})}),{}, {waitUntil(){}});
              console.log(JSON.stringify({status:r.status,body:await r.json()}));")]
    (is (= 503 (:status res)))
    (is (.contains (get-in res [:body :error :message]) "no safe route"))))

(deftest worker-auto-serves-stale-snapshot-when-the-feed-dies
  (let [res (run-node-eval
             "import worker from './src/worker.js';
              const oldPayload = JSON.stringify({hour: 1, refreshedAt: 3600001, nextRefreshAt: 7200000,
                models: [{modelId:'ali/qwen3.8-max-0902',score:34,prompt:0.01,completion:0.03}], excluded: []});
              let row = {hour: 1, payload: oldPayload};
              const db={prepare(sql) {return {bind(...args) {return {
                async first() {return sql.includes('auto_chain_snapshot') ? row : null;},
                async run() {if (sql.includes('INSERT INTO auto_chain_snapshot')) row={hour:args[0],payload:args[1]};}
              };}};}};
              const sent = [];
              globalThis.fetch=async (url, opts) => {
                if (url.endsWith('/models')) throw new Error('feed down');
                const body=JSON.parse(opts.body);
                sent.push({model:body.model,bid:opts.headers['x-max-output-price']});
                const answer={choices:[{index:0,delta:{content:'stale but safe'},finish_reason:'stop'}]};
                return new Response('data: '+JSON.stringify(answer)+'\\n\\ndata: [DONE]\\n\\n',
                  {status:200,headers:{'Content-Type':'text/event-stream'}});
              };
              const env={ROUTING_DB:db,INFERHUB_API_KEY:'key-from-test',INFERHUB_BASE_URL:'https://example.test/v1'};
              const response=await worker.fetch(new Request('https://worker.test/v1/chat/completions',{method:'POST',
                headers:{'Content-Type':'application/json'},
                body:JSON.stringify({model:'infered/auto',stream:true,messages:[{role:'user',content:'hello'}]})}),env,{waitUntil(){}});
              const body=await response.text();
              const inspect=await worker.fetch(new Request('https://worker.test/v1/auto'),env,{waitUntil(){}});
              const snapshot=await inspect.json();
              console.log(JSON.stringify({status:response.status,body,sent,
                snapshot:snapshot.models.map(m=>m.modelId),stale:snapshot.stale}));")]
    (is (= 200 (:status res))
        "a feed outage at an hour boundary must not 503 the whole chain")
    (is (.contains (:body res) "stale but safe"))
    (is (= ["ali/qwen3.8-max-0902"] (mapv :model (:sent res))))
    (is (every? #(= "0.999999" (:bid %)) (:sent res))
        "stale serving never relaxes the output bid")
    (is (true? (:stale res)) "/v1/auto labels the fallback snapshot as stale")
    (is (= ["ali/qwen3.8-max-0902"] (:snapshot res)))))
