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
                model('cx/gpt-6-astra', [1.01, 1.02]),
                model('oai/gpt-6.1-sol', [0.9, 0.95]),
                model('ag/claude-sonnet-4-6', [0.3, 0.35]),
                model('cx/gpt-6.1-terra', [0.1, 0.12, 0.15]),
                model('ali/kimi-k3.1-pro', [0.95, 0.96]),
                model('grok/grok-4.7', [0.01, 0.02, 0.03]),
                model('ali/qwen3.8-max-0902', [0.006, 0.018, 0.042]),
                model('ali/glm-5.3', [0.035, 0.08, 0.09]),
                model('zai/glm-5.3', [0.004, 0.25]),
                model('unranked/model', [0.001, 0.002])
              ]};
              const snapshot = buildAutoSnapshot(feed, 7200000);
              console.log(JSON.stringify(snapshot));")]
    (is (= 2 (:hour res)))
    (is (= 10800000 (:nextRefreshAt res)))
    (is (= ["oai/gpt-6.1-sol" "cx/gpt-6.1-terra" "ag/claude-sonnet-4-6" "ali/kimi-k3.1-pro"
            "grok/grok-4.7" "ali/qwen3.8-max-0902" "ali/glm-5.3" "zai/glm-5.3"]
           (mapv :modelId (:models res)))
        "equal scores order by in-cap seller depth before price: terra (3 in-cap) outranks sonnet (2) at 51; ali/glm (3 in-cap sellers) outranks zai/glm (2) despite zai's cheaper head ask")
    (is (every? #(< (:completion %) 1) (:models res)))
    (is (= {:askCount 3 :inCapDepth 3 :depthPrice 0.042}
           (select-keys (first (filter #(= "ali/qwen3.8-max-0902" (:modelId %)) (:models res)))
                        [:askCount :inCapDepth :depthPrice]))
        "members report their ask depth: total asks, in-cap sellers, sustained in-cap price")
    (is (not-any? #(= "cx/gpt-6-astra" (:modelId %)) (:models res))
        "Astra must not bypass the strict ceiling")
    (is (= [{:modelId "ag/gemini-4-carbon" :reason "not_in_feed"}
            {:modelId "cx/gpt-6-astra" :reason "over_cap" :ask 1.01}
            {:modelId "oai/gpt-6.1-terra" :reason "not_in_feed"}]
           (:excluded res))
        "exclusions are reported with their reason and disqualifying ask")))

(deftest hourly-d1-snapshot-refreshes-and-serves-stale-on-feed-failure
  (let [res (run-node-eval
             "import { getAutoSnapshot } from './src/router/auto.js';
              let row = null, fetches = 0, writes = 0;
              const db = {prepare(sql) { return {bind(...args) { return {
                async first() { return row; },
                async run() { writes++; if (!row || args[0] >= row.hour) row = {hour:args[0],payload:args[1]}; }
              }; }}; }};
              const feed = async () => { fetches++; return new Response(JSON.stringify({data:[
                {id:'cx/gpt-6-astra',pricing:{asks_in:[0.1],asks_out:[fetches === 1 ? 0.8 : 1.2, fetches === 1 ? 0.85 : 1.3]}},
                {id:'ali/glm-5.3',pricing:{asks_in:[0.01],asks_out:[0.03, 0.04]}}
              ]}), {status:200}); };
              const env = { ROUTING_DB: db, INFERHUB_API_KEY:'test-key', INFERHUB_BASE_URL:'https://example.test/v1' };
              const first = await getAutoSnapshot(env, feed, 7200001);
              const again = await getAutoSnapshot(env, feed, 7200999);
              const next = await getAutoSnapshot(env, feed, 10800001);
              const stale = await getAutoSnapshot(env, async () => {throw Error('feed down');}, 14400001);
              let cold = false;
              const emptyDb = {prepare() { return {bind(...args) { return {async first() { return null; }, async run() {}}; }}; }};
              try { await getAutoSnapshot({ROUTING_DB: emptyDb, INFERHUB_API_KEY:'k'}, async () => {throw Error('feed down');}, 18000001); }
              catch (_) { cold = true; }
              console.log(JSON.stringify({first:first.models.map(x=>x.modelId), again:again.models.map(x=>x.modelId),
                next:next.models.map(x=>x.modelId), nextStale:next.stale,
                staleModels:stale.models.map(x=>x.modelId), isStale:stale.stale, staleHour:stale.hour,
                fetches,writes,cold}));")]
    (is (= ["cx/gpt-6-astra" "ali/glm-5.3"] (:first res)))
    (is (= (:first res) (:again res)))
    (is (= ["ali/glm-5.3"] (:next res)))
    (is (false? (:nextStale res)))
    (is (= ["ali/glm-5.3"] (:staleModels res))
        "a feed outage serves the last live snapshot instead of killing the chain")
    (is (true? (:isStale res)) "the stale serve is labeled, never masquerading as current")
    (is (= 3 (:staleHour res)))
    (is (= 2 (:fetches res)) "one live refresh per observed hour")
    (is (= 2 (:writes res)))
    (is (:cold res) "no snapshot at all still fails closed")))

(deftest single-seller-books-are-bait-and-excluded
  (let [res (run-node-eval
             "import { buildAutoSnapshot } from './src/router/auto.js';
              const model = (id, asks) => ({id, pricing: {asks_in: asks.map(x => x/5), asks_out: asks}});
              const feed = {data: [
                model('oai/gpt-6.1-sol', [0.17, 5]),
                model('oai/gpt-6.1-terra', [0.085, 2.5]),
                model('ali/glm-5.3', [0.035, 0.08]),
                model('zai/glm-5.3', [0.004, 0.25])
              ]};
              console.log(JSON.stringify(buildAutoSnapshot(feed, 7200000)));")]
    (is (= ["ali/glm-5.3" "zai/glm-5.3"] (mapv :modelId (:models res)))
        "one-seller books drop out of the chain entirely: a lone ask is a price, not a market")
    (is (= [{:modelId "oai/gpt-6.1-sol" :reason "bait_priced" :ask 0.17 :nextAsk 5}
            {:modelId "oai/gpt-6.1-terra" :reason "bait_priced" :ask 0.085 :nextAsk 2.5}]
           (filter #(= "bait_priced" (:reason %)) (:excluded res)))
        "bait pricing is reported with the lone seller's ask and the cliff behind it")))

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

(deftest cache-adjusted-effective-input-orders-and-surfaces
  (let [res (run-node-eval
             "import { buildAutoSnapshot } from './src/router/auto.js';
              const mk = (id, asksOut, asksIn, cache) => ({id, supports_cache: cache,
                pricing: {asks_in: asksIn, asks_out: asksOut}});
              const ali = mk('ali/glm-5.3', [0.235, 0.244], [0.10], true);
              const zai = mk('zai/glm-5.3', [0.220, 0.230], [0.10], false);
              const withCache = buildAutoSnapshot({data: [ali, zai]}, 7200000);
              const without = buildAutoSnapshot({data: [{...ali, supports_cache: false}, zai]}, 7200000);
              const pick = (s, id) => s.models.filter(m => m.modelId === id)[0];
              console.log(JSON.stringify({
                orderWithCache: withCache.models.map(m => m.modelId),
                orderWithout: without.models.map(m => m.modelId),
                ali: ((m) => ({supportsCache: m.supportsCache, prompt: m.prompt,
                  effectiveInput: m.effectiveInput, effectiveBlended: m.effectiveBlended}))(pick(withCache, 'ali/glm-5.3')),
                zai: ((m) => ({supportsCache: m.supportsCache,
                  effectiveInput: m.effectiveInput, effectiveBlended: m.effectiveBlended}))(pick(withCache, 'zai/glm-5.3')),
                assumptions: withCache.cache}));")]
    (is (= 0.5 (get-in res [:assumptions :hitRate])))
    (is (= 0.1 (get-in res [:assumptions :tokenCost]))
        "cached tokens bill at 0.10x the fresh input ask (measured 2026-11-11: 5265/5276 prefix tokens cached, cache-read cost 5.25e-3 vs 5.7e-2)")
    (is (= ["zai/glm-5.3" "ali/glm-5.3"] (:orderWithout res))
        "without cache support the sustained price decides the 32-score tie")
    (is (= ["ali/glm-5.3" "zai/glm-5.3"] (:orderWithCache res))
        "cache support discounts the input share of the blended price and flips the tie")
    (is (= {:supportsCache true :prompt 0.10 :effectiveInput 0.055 :effectiveBlended 0.19675}
           (:ali res))
        "effective input = ask * (1 - hitRate*(1 - tokenCost)) = 0.10 * 0.55")
    (is (= {:supportsCache false :effectiveInput 0.10 :effectiveBlended 0.1975}
           (:zai res))
        "models without the flag pay the full input ask: effective = raw")))

(deftest quota-health-demotes-saturated-members
  (let [res (run-node-eval
             "import * as auto from './src/router/auto.js';
              const apply = auto.applyQuotaHealth || ((s) => s);
              const mk = (id, asksOut, asksIn) => ({id, supports_cache: true,
                pricing: {asks_in: asksIn, asks_out: asksOut}});
              const feed = {data: [
                mk('ag/gemini-4-carbon', [0.01, 0.5], [0.002, 0.1]),
                mk('ag/claude-sonnet-4-6', [0.3, 0.35], [0.06]),
                mk('ali/qwen3.8-max-0902', [0.006, 0.018], [0.002])
              ]};
              const snapshot = auto.buildAutoSnapshot(feed, 7200000);
              const quota = {hour: 2, probedAt: 7200000, results: {
                'ag/gemini-4-carbon': {status: 'quota', sellersTotal: 2221, sellersOut: 2215, ms: 412},
                'ag/claude-sonnet-4-6': {status: 'ok', ms: 890},
                'ali/qwen3.8-max-0902': {status: 'unknown'}
              }};
              const demoted = apply(snapshot, quota);
              const untouched = apply(snapshot, {hour: 2, probedAt: 7200000});
              const carbon = demoted.models.filter(m => m.modelId === 'ag/gemini-4-carbon')[0] || null;
              console.log(JSON.stringify({
                order: demoted.models.map(m => m.modelId),
                saturated: carbon && carbon.saturated,
                carbonQuota: carbon && carbon.quota,
                untouchedOrder: untouched.models.map(m => m.modelId)}));")]
    (is (= ["ag/claude-sonnet-4-6" "ali/qwen3.8-max-0902" "ag/gemini-4-carbon"] (:order res))
        "a saturated score-60 carbon demotes below available models: seller quota gates the head slot, it is not a tie-break")
    (is (true? (:saturated res)) "the demoted member is labeled saturated")
    (is (= {:status "quota" :sellersTotal 2221 :sellersOut 2215 :ms 412} (:carbonQuota res))
        "the live 429's seller evidence (N of M out of quota) rides on the member")
    (is (= ["ag/gemini-4-carbon" "ag/claude-sonnet-4-6" "ali/qwen3.8-max-0902"] (:untouchedOrder res))
        "quota data that is absent or malformed is a no-op, never a demotion")))

(deftest quota-probe-classifies-live-refusals-with-seller-evidence
  (let [res (run-node-eval
             "import * as auto from './src/router/auto.js';
              const probe = auto.probeQuotaHealth;
              if (!probe) { console.log(JSON.stringify({exported: false})); }
              else {
                const writes = [];
                const db = {prepare(sql) { return {bind(...args) { return {
                  async first() { return null; },
                  async run() { writes.push({sql, args}); }
                }; }}; }};
                const env = {ROUTING_DB: db, INFERHUB_API_KEY: 'k', INFERHUB_BASE_URL: 'https://example.test/v1'};
                const members = [{modelId: 'ag/gemini-4-carbon'}, {modelId: 'ag/claude-sonnet-4-6'},
                  {modelId: 'ali/qwen3.8-max-0902'}, {modelId: 'cx/gpt-6-astra'}];
                const bodies = [];
                const fetchFn = async (url, opts) => {
                  const body = JSON.parse(opts.body);
                  bodies.push({model: body.model, maxTokens: body.max_tokens, bid: opts.headers['x-max-output-price']});
                  if (body.model === 'ag/gemini-4-carbon')
                    return new Response(JSON.stringify({error: {code: 'capacity_saturated',
                      message: '2,215 of 2,221 sellers out of quota or cooling down'}}), {status: 429});
                  if (body.model === 'ag/claude-sonnet-4-6')
                    return new Response(JSON.stringify({error: {code: 'model_disabled',
                      message: 'model disabled in your preferences'}}), {status: 403});
                  if (body.model === 'cx/gpt-6-astra')
                    return new Response('gateway', {status: 502});
                  return new Response(JSON.stringify({choices: [{message: {content: 'OK'}}]}), {status: 200});
                };
                const health = await probe(env, members, fetchFn, 7200000);
                const write = writes.find(w => w.sql.includes('auto-quota')) || null;
                const persisted = write ? JSON.parse(write.args[1]) : null;
                const slim = (r) => Object.fromEntries(Object.entries(r).map(([k, v]) =>
                  [k, {status: v.status, sellersTotal: v.sellersTotal, sellersOut: v.sellersOut}]));
                console.log(JSON.stringify({exported: true, results: slim(health.results),
                  probed: bodies.map(b => b.model), bid: bodies.every(b => b.bid === '0.999999'),
                  tokens: bodies.every(b => b.maxTokens === 8),
                  persistedResults: persisted && slim(persisted.results), persistedHour: persisted && persisted.hour}));}")]
    (is (true? (:exported res)) "probeQuotaHealth is exported from auto.js")
    (is (= {:ag/gemini-4-carbon {:status "quota" :sellersTotal 2221 :sellersOut 2215}
            :ag/claude-sonnet-4-6 {:status "refused"}
            :ali/qwen3.8-max-0902 {:status "ok"}
            :cx/gpt-6-astra {:status "unknown"}}
           (:results res))
        "a 429 parses to quota with seller evidence, a 403 to refused, a 200 to ok, anything else stays unknown")
    (is (= ["ag/gemini-4-carbon" "ag/claude-sonnet-4-6" "ali/qwen3.8-max-0902" "cx/gpt-6-astra"] (:probed res))
        "every member is probed sequentially")
    (is (true? (:bid res)) "probes carry the output bid header: measurement can never overspend")
    (is (true? (:tokens res)) "probes are tiny: max_tokens 8")
    (is (= (:results res) (:persistedResults res))
        "the probe payload persists to the auto-quota row verbatim")
    (is (= 2 (:persistedHour res)))))
