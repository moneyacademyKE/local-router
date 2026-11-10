(ns auto-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]))

(defn run-node-eval [js]
  (let [{:keys [exit out err]} (sh "node" "--input-type=module" "-e" js)]
    (if (zero? exit) (json/parse-string out true)
        (throw (ex-info (str "Node eval error: " err) {:out out})))))

(deftest hourly-selection-is-intelligence-ranked-and-exclusive
  (let [res (run-node-eval
             "import { buildAutoSnapshot } from './src/router/auto.js';
              const model = (id, asks) => ({id, pricing: {asks_in: asks.map(x => x/5), asks_out: asks}});
              const feed = {data: [
                model('cx/gpt-6-astra', [1, 1.01]),
                model('oai/gpt-6.1-sol', [0.999999, 1]),
                model('cx/gpt-6.1-terra', [0.1]),
                model('oai/gpt-6.1-terra', [0.9]),
                model('ali/kimi-k3.1-pro', [0.95]),
                model('ali/qwen3.8-max-0902', [0.006]),
                model('ali/glm-5.3', [0.035]),
                model('zai/glm-5.3', [0.004]),
                model('unranked/model', [0.001])
              ]};
              const snapshot = buildAutoSnapshot(feed, 7200000);
              console.log(JSON.stringify(snapshot));")]
    (is (= 2 (:hour res)))
    (is (= 10800000 (:nextRefreshAt res)))
    (is (= ["oai/gpt-6.1-sol" "cx/gpt-6.1-terra" "oai/gpt-6.1-terra"
            "ali/kimi-k3.1-pro" "ali/qwen3.8-max-0902" "zai/glm-5.3" "ali/glm-5.3"]
           (mapv :modelId (:models res))))
    (is (every? #(< (:completion %) 1) (:models res)))
    (is (not-any? #(= "cx/gpt-6-astra" (:modelId %)) (:models res))
        "Astra must not bypass the strict ceiling")))

(deftest hourly-d1-snapshot-refreshes-and-fails-closed
  (let [res (run-node-eval
             "import { getAutoSnapshot } from './src/router/auto.js';
              let row = null, fetches = 0, writes = 0;
              const db = {prepare(sql) { return {bind(...args) { return {
                async first() { return row; },
                async run() { writes++; if (!row || args[0] >= row.hour) row = {hour:args[0],payload:args[1]}; }
              }; }}; }};
              const feed = async () => { fetches++; return new Response(JSON.stringify({data:[
                {id:'cx/gpt-6-astra',pricing:{asks_in:[0.1],asks_out:[fetches === 1 ? 0.8 : 1.2]}},
                {id:'ali/glm-5.3',pricing:{asks_in:[0.01],asks_out:[0.03]}}
              ]}), {status:200}); };
              const env = { ROUTING_DB: db, INFERHUB_API_KEY:'test-key', INFERHUB_BASE_URL:'https://example.test/v1' };
              const first = await getAutoSnapshot(env, feed, 7200001);
              const again = await getAutoSnapshot(env, feed, 7200999);
              const next = await getAutoSnapshot(env, feed, 10800001);
              let closed = false;
              try { await getAutoSnapshot(env, async () => {throw Error('feed down');}, 14400001); }
              catch (_) { closed = true; }
              console.log(JSON.stringify({first:first.models.map(x=>x.modelId), again:again.models.map(x=>x.modelId),
                next:next.models.map(x=>x.modelId), fetches,writes,closed}));")]
    (is (= ["cx/gpt-6-astra" "ali/glm-5.3"] (:first res)))
    (is (= (:first res) (:again res)))
    (is (= ["ali/glm-5.3"] (:next res)))
    (is (= 2 (:fetches res)) "one live refresh per observed hour")
    (is (= 2 (:writes res)))
    (is (:closed res) "expired snapshot cannot masquerade as current when refresh fails")))

(deftest strict-auto-ranking-ignores-unbounded-ladder-exemptions-and-pins
  (let [res (run-node-eval
             "import { rankCandidates } from './src/router/pareto.js';
              import { createPriceCache } from './src/router/pricing.js';
              import { createMetricsStore } from './src/router/metrics.js';
              const snap = {models:[
                {modelId:'cx/gpt-6-astra',prompt:0.1,completion:1,score:57},
                {modelId:'oai/gpt-6.1-sol',prompt:0.1,completion:0.9,score:55},
                {modelId:'ali/glm-5.3',prompt:0.01,completion:0.1,score:32}
              ]};
              const rank = (cap) => rankCandidates({model:'infered/auto',autoSnapshot:snap,
                priceCache:createPriceCache(),metricsStore:createMetricsStore(),maxFallbackPrice:cap,
                sessionAffinityModel:'ali/glm-5.3'});
              console.log(JSON.stringify({normal:rank(null).map(c=>({id:c.modelId,bid:c.maxOutputPrice})),
                capped:rank(0.2).map(c=>({id:c.modelId,bid:c.maxOutputPrice})),
                missing:rank(null).length && rank(0.001).length}));")]
    (is (= ["oai/gpt-6.1-sol" "ali/glm-5.3"] (mapv :id (:normal res))))
    (is (= ["ali/glm-5.3"] (mapv :id (:capped res))))
    (is (every? #(< (:bid %) 1) (:normal res)))
    (is (= 0.2 (-> res :capped first :bid)))
    (is (zero? (:missing res)) "no Infinity or exempt-model escape hatch")))

(deftest upstream-bid-is-sent-for-stream-and-nonstream
  (let [res (run-node-eval
             "import { executeWithFallback } from './src/router/client.js';
              import { createMetricsStore } from './src/router/metrics.js';
              const bids = [];
              const fetchFn = async (_, opts) => { bids.push(opts.headers['x-max-output-price']);
                return new Response(JSON.stringify({choices:[{message:{role:'assistant',content:'ok'}}]}),
                  {status:200,headers:{'Content-Type':'application/json'}}); };
              const candidate={providerId:'auto-node',modelId:'ali/glm-5.3',quote:{prompt:0.01,completion:0.03},
                maxOutputPrice:0.999999,savingsPct:90,blendedPrice:0.03};
              for (const stream of [false,true]) {
                const r=await executeWithFallback({candidates:[candidate],requestBody:{stream,messages:[{role:'user',content:'hi'}]},
                  apiKey:'test',metricsStore:createMetricsStore(),fetchFn});
                if (r.stream) await new Response(r.stream).text();
              }
              console.log(JSON.stringify({bids}));")]
    (is (= ["0.999999" "0.999999"] (:bids res)) "bid enforced by marketplace on both wire paths")))
