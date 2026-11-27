(ns auto-worker-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]))

(defn run-node-eval [js]
  (let [{:keys [exit out err]} (sh "node" "--input-type=module" "-e" js)]
    (if (zero? exit) (json/parse-string out true)
        (throw (ex-info (str "Node eval error: " err) {:out out})))))

(def ^:private board-json
  "const boardJson = JSON.stringify({board: {publishedOn: '2026-11-27'}, ranking: [
     {model: 'cx/gpt-6.2-sol', score: 55}, {model: 'ali/qwen4-max', score: 52},
     {model: 'zai/glm-5.4', score: 50}]});")

(deftest worker-auto-keeps-the-bid-on-every-fallback
  (let [res (run-node-eval
             (str board-json "
              import worker from './src/worker.js';
              let row = null, feedCalls = 0, boardCalls = 0; const sent = [], promises = [];
              const db={prepare(sql) {return {bind(...args) {return {
                async first() {return sql.includes('auto-intelligence') ? null : row;},
                async run() {if (sql.includes('auto-intelligence')) return;
                  if (sql.includes('INSERT INTO auto_chain_snapshot')) row={hour:args[0],payload:args[1]};}
              };}};}};
              globalThis.fetch=async (url, opts) => {
                if (url.includes('models.fru.dev')) {boardCalls++; return new Response(boardJson, {status:200});}
                if (url.endsWith('/models')) {feedCalls++; return new Response(JSON.stringify({data:[
                  {id:'cx/gpt-6.2-sol',supports_cache:true,pricing:{asks_in:[0.1],asks_out:[0.5, 0.55]}},
                  {id:'ali/qwen4-max',pricing:{asks_in:[0.01],asks_out:[0.03, 0.04]}}
                ]}),{status:200});}
                const body=JSON.parse(opts.body);
                sent.push({model:body.model,bid:opts.headers['x-max-output-price'],provider:opts.headers['X-InferHub-Provider']||null});
                if (body.model==='cx/gpt-6.2-sol') return new Response('price moved',{status:402});
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
              console.log(JSON.stringify({status:response.status,body,sent,feedCalls,boardCalls,inspectStatus:inspect.status,
                declared:snapshot.declared, parameters:snapshot.parameters,
                snapshot:snapshot.models.map(m=>({id:m.modelId,askCount:m.askCount,
                  inCapDepth:m.inCapDepth,depthPrice:m.depthPrice,
                  supportsCache:m.supportsCache,effectiveInput:m.effectiveInput}))}));"))]
    (is (= 200 (:status res)))
    (is (.contains (:body res) "safe fallback"))
    (is (= ["cx/gpt-6.2-sol" "ali/qwen4-max"] (mapv :model (:sent res)))
        "the leading member fails, so the chain falls to the next under the same bid")
    (is (every? #(= "1" (:bid %)) (:sent res)))
    (is (every? #(nil? (:provider %)) (:sent res)) "synthetic node ids are not sent as real upstream sellers")
    (is (= 1 (:feedCalls res)) "the endpoint reads the shared snapshot")
    (is (= 1 (:boardCalls res))
        "the refresh reads the live intelligence board once; the served snapshot does not re-read it")
    (is (= 200 (:inspectStatus res)))
    (is (nil? (:declared res))
        "infered/auto states parameters, not a member list (owner directive 2026-11-27)")
    (is (= {:maxOutputPrice 1 :order "measured-value" :refreshMs 300000 :exclude ["gemini4argon"]}
           (:parameters res)))
    (is (= [{:id "cx/gpt-6.2-sol" :askCount 2 :inCapDepth 2 :depthPrice 0.55}
            {:id "ali/qwen4-max" :askCount 2 :inCapDepth 2 :depthPrice 0.04}]
           (mapv #(select-keys % [:id :askCount :inCapDepth :depthPrice]) (:snapshot res)))
        "/v1/auto surfaces per-model ask depth: total asks, in-cap sellers, sustained price")
    (is (= [{:id "cx/gpt-6.2-sol" :supportsCache true :effectiveInput 0.055}
            {:id "ali/qwen4-max" :supportsCache false :effectiveInput 0.01}]
           (mapv #(select-keys % [:id :supportsCache :effectiveInput]) (:snapshot res)))
        "/v1/auto surfaces the cache flag and the cache-adjusted input price")))

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
                models: [{modelId:'cx/gpt-6.2-sol',score:34,prompt:0.01,completion:0.03}], excluded: []});
              let row = {hour: 1, payload: oldPayload};
              const db={prepare(sql) {return {bind(...args) {return {
                async first() {return sql.includes('auto-intelligence') ? null : row;},
                async run() {if (sql.includes('auto-intelligence')) return;
                  if (sql.includes('INSERT INTO auto_chain_snapshot')) row={hour:args[0],payload:args[1]};}
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
        "a feed outage at a period boundary must not 503 the whole chain")
    (is (.contains (:body res) "stale but safe"))
    (is (= ["cx/gpt-6.2-sol"] (mapv :model (:sent res)))
        "a stale row still names a member the chain can score — the score on the row is the member's ticket")
    (is (every? #(= "1" (:bid %)) (:sent res))
        "stale serving never relaxes the output bid")
    (is (true? (:stale res)) "/v1/auto labels the fallback snapshot as stale")
    (is (= ["cx/gpt-6.2-sol"] (:snapshot res)))))

(deftest worker-auto-applies-quota-health-to-the-served-chain
  (let [res (run-node-eval
             (str board-json "
              import worker from './src/worker.js';
              import { buildAutoSnapshot } from './src/router/auto.js';
              import { intelligenceFromBoard } from './src/router/intelligence.js';
              import { MARKET_CHAINS } from './src/router/chains.js';
              const period = Math.floor(Date.now() / 300000);
              const market = buildAutoSnapshot({data: [
                {id: 'cx/gpt-6.2-sol', supports_cache: true, pricing: {asks_in: [0.1], asks_out: [0.19, 0.21]}},
                {id: 'ali/qwen4-max', pricing: {asks_in: [0.06], asks_out: [0.27, 0.3]}},
                {id: 'zai/glm-5.4', pricing: {asks_in: [0.002], asks_out: [0.88, 0.9]}}
              ]}, Date.now(), MARKET_CHAINS['infered/auto'], intelligenceFromBoard(JSON.parse(boardJson)));
              const marketRow = {hour: period, payload: JSON.stringify(market)};
              const quotaRow = {hour: period, payload: JSON.stringify({hour: period, probedAt: Date.now(), results: {
                'cx/gpt-6.2-sol': {status: 'quota', sellersTotal: 2221, sellersOut: 2215, ms: 412},
                'ali/qwen4-max': {status: 'ok', ms: 890}
              }})};
              const db={prepare(sql) {return {bind(...args) {return {
                async first() {return sql.includes('auto-quota') ? quotaRow :
                  (sql.includes('auto_chain_snapshot') ? marketRow : null);},
                async run() {}
              };}};}};
              const sent = [];
              globalThis.fetch=async (url, opts) => {
                if (url.endsWith('/models')) throw new Error('feed must not be called: market row is current');
                const body=JSON.parse(opts.body);
                sent.push({model:body.model,bid:opts.headers['x-max-output-price']});
                const answer={choices:[{index:0,delta:{content:'quota-aware serve'},finish_reason:'stop'}]};
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
              const head=snapshot.models.filter(m=>m.modelId==='cx/gpt-6.2-sol')[0]||null;
              console.log(JSON.stringify({status:response.status,body,sent,
                order:snapshot.models.map(m=>m.modelId),
                headSaturated:head && head.saturated,
                headQuota:head && head.quota,
                stale:snapshot.stale}));"))]
    (is (= 200 (:status res)))
    (is (.contains (:body res) "quota-aware serve"))
    (is (= ["ali/qwen4-max"] (mapv :model (:sent res)))
        "the saturated sol head is demoted below available members: the request never even tries it")
    (is (every? #(= "1" (:bid %)) (:sent res)))
    (is (= ["ali/qwen4-max" "zai/glm-5.4" "cx/gpt-6.2-sol"] (:order res))
        "/v1/auto shows the quota-demoted order with the saturated sol last")
    (is (true? (:headSaturated res)))
    (is (= {:status "quota" :sellersTotal 2221 :sellersOut 2215 :ms 412} (:headQuota res)))
    (is (false? (:stale res)))))
