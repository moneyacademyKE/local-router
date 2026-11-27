(ns auto-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]))

;; infered/auto discovers its members (owner directive 2026-11-27 16:42 EAT):
;; every text model the live feed quotes under the $1.00/M output bid that
;; carries a known intelligence score, re-read every five minutes, served in
;; measured-value order. The declared sol/qwen4-max/glm-5.4 sequence is gone;
;; nothing about the snapshot/staleness/quota/bid mechanism changed.

(defn run-node-eval [js]
  (let [{:keys [exit out err]} (sh "node" "--input-type=module" "-e" js)]
    (if (zero? exit) (json/parse-string out true)
        (throw (ex-info (str "Node eval error: " err) {:out out})))))

(def ^:private board-js
  "import { intelligenceFromBoard } from './src/router/intelligence.js';
   const board = intelligenceFromBoard({ranking: [
     {model: 'cx/gpt-6.2-sol', score: 55}, {model: 'ali/qwen4-max', score: 52},
     {model: 'zai/glm-5.4', score: 50}, {model: 'cx/gpt-6.1-terra', score: 51},
     {model: 'grok/grok-4.7', score: 35}
   ]});")

(def ^:private model-fn
  "const model = (id, asks) => ({id, pricing: {asks_in: asks.map(x => x/5), asks_out: asks}});")

(deftest auto-discovers-its-members-and-names-what-it-cannot-rank
  (let [res (run-node-eval
             (str board-js model-fn "
              import { buildAutoSnapshot } from './src/router/auto.js';
              const feed = {data: [
                model('cx/gpt-6.2-sol', [0.19, 0.21]),
                model('cx/gpt-6.1-terra', [0.30, 0.32]),
                model('zai/glm-5.4', [0.88]),
                model('unranked/model', [0.001, 0.002]),
                model('ali/qwen4-max', [1.5])
              ]};
              const snapshot = buildAutoSnapshot(feed, 7200000, undefined, board);
              console.log(JSON.stringify({hour: snapshot.hour, periodMs: snapshot.periodMs,
                nextRefreshAt: snapshot.nextRefreshAt, bid: snapshot.bid,
                models: snapshot.models.map(m => m.modelId),
                completion: snapshot.models.map(m => m.completion),
                sol: ((m) => ({askCount: m.askCount, inCapDepth: m.inCapDepth, depthPrice: m.depthPrice}))
                  (snapshot.models.find(m => m.modelId === 'cx/gpt-6.2-sol')),
                excluded: snapshot.excluded, unscored: snapshot.unscored}));"))]
    (is (= 2 (:hour res)))
    (is (= 300000 (:periodMs res))
        "auto re-reads the market every five minutes, like auto-budget (owner directive 2026-11-27)")
    (is (= 7500000 (:nextRefreshAt res)))
    (is (= 1 (:bid res)) "the chain's own ceiling is still the owner's $1.00/M output bid")
    (is (= ["cx/gpt-6.2-sol" "cx/gpt-6.1-terra" "zai/glm-5.4"] (:models res))
        "every scored text model inside the bid is a candidate, best score first — no declared list")
    (is (every? #(< % 1) (:completion res)))
    (is (= {:askCount 2 :inCapDepth 2 :depthPrice 0.21} (:sol res))
        "candidates report their ask depth: total asks, in-cap sellers, sustained in-cap price")
    (is (= [{:modelId "ali/qwen4-max" :reason "over_cap" :ask 1.5}] (:excluded res))
        "a scored model whose every ask sits above the bid is excluded WITH its disqualifying ask — never bought quietly outside the ceiling")
    (is (= ["unranked/model"] (:unscored res))
        "a model quoted inside the bid with no published score is counted, not served: the only thing keeping it out is a missing number")))

(deftest auto-snapshot-refreshes-and-serves-stale-on-feed-failure
  (let [res (run-node-eval
             "import { getAutoSnapshot } from './src/router/auto.js';
              let row = null, fetches = 0, writes = 0;
              const db = {prepare(sql) { return {bind(...args) { return {
                async first() { return sql.includes('auto-intelligence') ? null : row; },
                async run() { writes++; if (!row || args[0] >= row.hour) row = {hour:args[0],payload:args[1]}; }
              }; }}; }};
              const feed = async (url) => {
                if (url.includes('models.fru.dev')) return new Response('[]', {status: 200});
                fetches++;
                return new Response(JSON.stringify({data:[
                  {id:'cx/gpt-6.1-terra',pricing:{asks_in:[0.1],asks_out:[fetches === 1 ? 0.8 : 1.2, fetches === 1 ? 0.85 : 1.3]}},
                  {id:'grok/grok-4.7',pricing:{asks_in:[0.01],asks_out:[0.03, 0.04]}}
                ]}), {status:200}); };
              const env = { ROUTING_DB: db, INFERHUB_API_KEY:'test-key', INFERHUB_BASE_URL:'https://example.test/v1' };
              const first = await getAutoSnapshot(env, feed, 7200001);
              const again = await getAutoSnapshot(env, feed, 7200999);
              const next = await getAutoSnapshot(env, feed, 10800001);
              const stale = await getAutoSnapshot(env, async (url) => {
                if (url.includes('models.fru.dev')) return new Response('[]', {status: 200});
                throw Error('feed down'); }, 14400001);
              let cold = false;
              const emptyDb = {prepare() { return {bind(...args) { return {async first() { return null; }, async run() {}}; }}; }};
              try { await getAutoSnapshot({ROUTING_DB: emptyDb, INFERHUB_API_KEY:'k'}, async () => {throw Error('feed down');}, 18000001); }
              catch (_) { cold = true; }
              console.log(JSON.stringify({first:first.models.map(x=>x.modelId), again:again.models.map(x=>x.modelId),
                next:next.models.map(x=>x.modelId), nextStale:next.stale, nextExcluded:next.excluded,
                staleModels:stale.models.map(x=>x.modelId), isStale:stale.stale, staleHour:stale.hour,
                fetches,writes,cold}));")]
    (is (= ["cx/gpt-6.1-terra" "grok/grok-4.7"] (:first res))
        "a dead intelligence board degrades to the recorded scores: terra and grok rank without it")
    (is (= (:first res) (:again res)) "one snapshot per five-minute period")
    (is (= ["grok/grok-4.7"] (:next res))
        "the next period's first request refreshes the market snapshot")
    (is (false? (:nextStale res)))
    (is (= [{:modelId "cx/gpt-6.1-terra" :reason "over_cap" :ask 1.2}] (:nextExcluded res))
        "a price move between reads narrows the chain at the next refresh, not silently")
    (is (= ["grok/grok-4.7"] (:staleModels res))
        "a feed outage serves the last live snapshot instead of killing the chain")
    (is (true? (:isStale res)) "the stale serve is labeled, never masquerading as current")
    (is (= 3 (:staleHour res)))
    (is (= 2 (:fetches res)) "one live market refresh per observed period")
    (is (= 2 (:writes res)))
    (is (:cold res) "no snapshot at all still fails closed")))

(deftest persisted-snapshots-drop-models-the-chain-cannot-rank
  (let [res (run-node-eval
             "import { getAutoSnapshot } from './src/router/auto.js';
              const row = {hour: 24, payload: JSON.stringify({hour:2, models:[
                {modelId:'cx/gpt-6.2-sol', score:55, prompt:0.02, completion:0.04},
                {modelId:'grok/grok-4.7', score:35.5, prompt:0.01, completion:0.03},
                {modelId:'zai/glm-5.4', prompt:0.01, completion:0.88},
                {modelId:'ag/gemini-4-argon', score:60, prompt:0.02, completion:0.24},
                {modelId:'cb/deepseek-v4', prompt:0.01, completion:0.004}
              ]})};
              const db = {prepare(sql) { return {bind() { return {
                async first() { return sql.includes('auto-quota') ? null : row; },
                async run() {}
              }; }}; }};
              const env = {ROUTING_DB:db, INFERHUB_API_KEY:'test-key'};
              const current = await getAutoSnapshot(env, async () => {throw Error('must not refresh');}, 7200000);
              const stale = await getAutoSnapshot(env, async () => {throw Error('feed down');}, 10800000);
              console.log(JSON.stringify({current:current.models.map(m=>m.modelId),
                stale:stale.models.map(m=>m.modelId), isStale:stale.stale}));")]
    (is (= ["cx/gpt-6.2-sol" "grok/grok-4.7"] (:current res))
        "a snapshot written before the flip serves only models the discovered chain can still score: glm-5.4 (unscored) and argon (owner-excluded) drop, sol and grok stay")
    (is (= ["cx/gpt-6.2-sol" "grok/grok-4.7"] (:stale res)))
    (is (true? (:isStale res))
        "current and stale D1 snapshots cannot smuggle in a model the parameters no longer admit — an unscored member, an excluded family, or a foreign chain's cheap member")))

(deftest single-seller-books-are-admitted-and-flagged-thin
  (let [res (run-node-eval
             (str board-js model-fn "
              import { buildAutoSnapshot, chainDiagnostics } from './src/router/auto.js';
              const feed = {data: [
                model('cx/gpt-6.2-sol', [0.19, 2]),
                model('ali/qwen4-max', [0.27, 0.3]),
                model('zai/glm-5.4', [0.88, 4])
              ]};
              const snapshot = buildAutoSnapshot(feed, 7200000, undefined, board);
              console.log(JSON.stringify({snapshot, diagnostics: chainDiagnostics(snapshot)}));"))]
    (let [snapshot (:snapshot res)]
      (is (= ["cx/gpt-6.2-sol" "ali/qwen4-max" "zai/glm-5.4"]
             (mapv :modelId (:models snapshot)))
          "a lone in-cap ask is a real price the router can buy: the model stays in the chain")
      (is (= [true false true] (mapv :thinBook (:models snapshot)))
          "one-seller books are flagged thin so the report can name them")
      (is (= [1 2 1] (mapv :inCapDepth (:models snapshot))))
      (is (empty? (filter #(= "bait_priced" (:reason %)) (:excluded snapshot)))
          "no candidate is excluded for a thin book — supply is never banned on a belief"))
    (is (= ["cx/gpt-6.2-sol" "zai/glm-5.4"] (get-in res [:diagnostics :thinBook])))
    (is (some #(re-find #"thin book" %) (get-in res [:diagnostics :warnings]))
        "the diagnostics name the thin books instead of silently shrinking the chain")))

(deftest strict-auto-ranking-ignores-unbounded-ladder-exemptions-and-pins
  (let [res (run-node-eval
             "import { rankCandidates } from './src/router/pareto.js';
              import { createPriceCache } from './src/router/pricing.js';
              import { createMetricsStore } from './src/router/metrics.js';
              const snap = {models:[
                {modelId:'cx/gpt-6.2-sol',prompt:0.1,completion:1,score:55},
                {modelId:'ali/qwen4-max',prompt:0.01,completion:0.1,score:52},
                {modelId:'zai/glm-5.4',prompt:0.1,completion:0.9,score:50},
                {modelId:'cb/deepseek-v4',prompt:0.001,completion:0.004}
              ]};
              const rank = (cap) => rankCandidates({model:'infered/auto',autoSnapshot:snap,
                priceCache:createPriceCache(),metricsStore:createMetricsStore(),maxFallbackPrice:cap,
                sessionAffinityModel:'ali/qwen4-max'});
              console.log(JSON.stringify({normal:rank(null).map(c=>({id:c.modelId,bid:c.maxOutputPrice})),
                capped:rank(0.2).map(c=>({id:c.modelId,bid:c.maxOutputPrice})),
                missing:rank(null).length && rank(0.001).length}));")]
    (is (= ["cx/gpt-6.2-sol" "ali/qwen4-max" "zai/glm-5.4"] (mapv :id (:normal res)))
        "a $1.00/M ask sits exactly on the ceiling, not one rounding step outside it, and the snapshot's own order holds at serve time")
    (is (= ["ali/qwen4-max"] (mapv :id (:capped res))))
    (is (every? #(= 1 (:bid %)) (:normal res))
        "an uncapped request is still bounded at the $1.00/M ceiling")
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
                maxOutputPrice:1,savingsPct:90,blendedPrice:0.03};
              for (const stream of [false,true]) {
                const r=await executeWithFallback({candidates:[candidate],requestBody:{stream,messages:[{role:'user',content:'hi'}]},
                  apiKey:'test',metricsStore:createMetricsStore(),fetchFn});
                if (r.stream) await new Response(r.stream).text();
              }
              console.log(JSON.stringify({bids}));")]
    (is (= ["1" "1"] (:bids res)) "bid enforced by marketplace on both wire paths")))

(deftest cache-adjusted-effective-input-orders-and-surfaces
  (let [res (run-node-eval
             (str board-js "
              import { buildAutoSnapshot } from './src/router/auto.js';
              const mk = (id, asksOut, asksIn, cache) => ({id, supports_cache: cache,
                pricing: {asks_in: asksIn, asks_out: asksOut}});
              const head = mk('cx/gpt-6.2-sol', [0.24], [0.10], true);
              const tail = mk('ali/qwen4-max', [0.19], [0.10], false);
              const withCache = buildAutoSnapshot({data: [head, tail]}, 7200000, undefined, board);
              const without = buildAutoSnapshot({data: [{...head, supports_cache: false}, tail]}, 7200000, undefined, board);
              const pick = (s, id) => s.models.filter(m => m.modelId === id)[0];
              console.log(JSON.stringify({
                orderWithCache: withCache.models.map(m => m.modelId),
                orderWithout: without.models.map(m => m.modelId),
                head: ((m) => ({supportsCache: m.supportsCache, prompt: m.prompt,
                  effectiveInput: m.effectiveInput, effectiveBlended: m.effectiveBlended}))(pick(withCache, 'cx/gpt-6.2-sol')),
                qwen: ((m) => ({supportsCache: m.supportsCache,
                  effectiveInput: m.effectiveInput, effectiveBlended: m.effectiveBlended}))(pick(withCache, 'ali/qwen4-max')),
                headNoCache: ((m) => ({supportsCache: m.supportsCache,
                  effectiveInput: m.effectiveInput, effectiveBlended: m.effectiveBlended}))(pick(without, 'cx/gpt-6.2-sol')),
                assumptions: withCache.cache}));"))]
    (is (= 0.5 (get-in res [:assumptions :hitRate])))
    (is (= 0.1 (get-in res [:assumptions :tokenCost]))
        "cached tokens bill at 0.10x the fresh input ask (measured 2026-11-11: 5265/5276 prefix tokens cached, cache-read cost 5.25e-3 vs 5.7e-2)")
    (is (= ["cx/gpt-6.2-sol" "ali/qwen4-max"] (:orderWithout res))
        "the score-led build order survives a cache-less member: intelligence still decides the sequence")
    (is (= ["cx/gpt-6.2-sol" "ali/qwen4-max"] (:orderWithCache res))
        "cache support discounts the input share without moving the member")
    (is (= {:supportsCache true :prompt 0.10 :effectiveInput 0.055 :effectiveBlended 0.19375}
           (:head res))
        "effective input = ask * (1 - hitRate*(1 - tokenCost)) = 0.10 * 0.55")
    (is (= {:supportsCache false :effectiveInput 0.10 :effectiveBlended 0.1675}
           (:qwen res))
        "models without the flag pay the full input ask: effective = raw")
    (is (= {:supportsCache false :effectiveInput 0.10 :effectiveBlended 0.205}
           (:headNoCache res))
        "strip the flag and the same ask costs 0.205 blended, not 0.19375 — the discount is real, not cosmetic")))

(deftest quota-health-demotes-saturated-members
  (let [res (run-node-eval
             (str board-js model-fn "
              import * as auto from './src/router/auto.js';
              const apply = auto.applyQuotaHealth || ((s) => s);
              const feed = {data: [
                mk2('cx/gpt-6.2-sol', [0.19], [0.10]),
                mk2('ali/qwen4-max', [0.27], [0.06]),
                mk2('zai/glm-5.4', [0.88], [0.10])
              ]};
              function mk2(id, asksOut, asksIn) { return {id, supports_cache: true,
                pricing: {asks_in: asksIn, asks_out: asksOut}}; }
              const snapshot = auto.buildAutoSnapshot(feed, 7200000, undefined, board);
              const quota = {hour: 2, probedAt: 7200000, results: {
                'cx/gpt-6.2-sol': {status: 'quota', sellersTotal: 2221, sellersOut: 2215, ms: 412},
                'ali/qwen4-max': {status: 'ok', ms: 890}
              }};
              const demoted = apply(snapshot, quota);
              const untouched = apply(snapshot, {hour: 2, probedAt: 7200000});
              const head = demoted.models.filter(m => m.modelId === 'cx/gpt-6.2-sol')[0] || null;
              console.log(JSON.stringify({
                order: demoted.models.map(m => m.modelId),
                saturated: head && head.saturated,
                headQuota: head && head.quota,
                untouchedOrder: untouched.models.map(m => m.modelId)}));"))]
    (is (= ["ali/qwen4-max" "zai/glm-5.4" "cx/gpt-6.2-sol"] (:order res))
        "a saturated member demotes below every available member: seller quota gates the head slot, it is not a tie-break")
    (is (true? (:saturated res)) "the demoted member is labeled saturated")
    (is (= {:status "quota" :sellersTotal 2221 :sellersOut 2215 :ms 412} (:headQuota res))
        "the live 429's seller evidence (N of M out of quota) rides on the member")
    (is (= ["cx/gpt-6.2-sol" "ali/qwen4-max" "zai/glm-5.4"] (:untouchedOrder res))
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
                const members = [{modelId: 'ag/gemini-4-carbon'}, {modelId: 'cx/gpt-6.1-terra'},
                  {modelId: 'grok/grok-4.7'}, {modelId: 'cx/gpt-6-astra'}];
                const bodies = [];
                const fetchFn = async (url, opts) => {
                  const body = JSON.parse(opts.body);
                  bodies.push({model: body.model, maxTokens: body.max_tokens, bid: opts.headers['x-max-output-price']});
                  if (body.model === 'ag/gemini-4-carbon')
                    return new Response(JSON.stringify({error: {code: 'capacity_saturated',
                      message: '2,215 of 2,221 sellers out of quota or cooling down'}}), {status: 429});
                  if (body.model === 'cx/gpt-6.1-terra')
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
                  probed: bodies.map(b => b.model), bid: bodies.every(b => b.bid === '1'),
                  tokens: bodies.every(b => b.maxTokens === 8),
                  persistedResults: persisted && slim(persisted.results), persistedHour: persisted && persisted.hour}));}")]
    (is (true? (:exported res)) "probeQuotaHealth is exported from auto.js")
    (is (= {:ag/gemini-4-carbon {:status "quota" :sellersTotal 2221 :sellersOut 2215}
            :cx/gpt-6.1-terra {:status "refused"}
            :grok/grok-4.7 {:status "ok"}
            :cx/gpt-6-astra {:status "unknown"}}
           (:results res))
        "a 429 parses to quota with seller evidence, a 403 to refused, a 200 to ok, anything else stays unknown")
    (is (= ["ag/gemini-4-carbon" "cx/gpt-6.1-terra" "grok/grok-4.7" "cx/gpt-6-astra"] (:probed res))
        "every member is probed sequentially")
    (is (true? (:bid res)) "probes carry the output bid header: measurement can never overspend")
    (is (true? (:tokens res)) "probes are tiny: max_tokens 8")
    (is (= (:results res) (:persistedResults res))
        "the probe payload persists to the auto-quota row verbatim")
    (is (= 2 (:persistedHour res)))))

(deftest discovered-chain-says-what-it-fixes-and-who-ranked-it
  (let [res (run-node-eval
             (str board-js model-fn "
              import { buildAutoSnapshot, chainDiagnostics } from './src/router/auto.js';
              import { MARKET_CHAINS, chainParameters } from './src/router/chains.js';
              const feed = {data: [
                model('cx/gpt-6.2-sol', [0.19, 0.21]),
                model('ali/qwen4-max', [0.27, 0.3]),
                model('zai/glm-5.4', [0.88, 0.9]),
                model('unranked/model', [0.001, 0.002])
              ]};
              const chain = MARKET_CHAINS['infered/auto'];
              const snapshot = buildAutoSnapshot(feed, 7200000, chain, board);
              console.log(JSON.stringify({order: snapshot.models.map(m=>m.modelId),
                diagnostics: chainDiagnostics(snapshot, chain),
                parameters: chainParameters(chain),
                unscoredWarning: chainDiagnostics(snapshot, chain).warnings
                  .find(w => w.indexOf('no intelligence score') !== -1) || null}));"))]
    (is (= ["cx/gpt-6.2-sol" "ali/qwen4-max" "zai/glm-5.4"] (:order res)))
    (is (= "measured-value" (get-in res [:diagnostics :orderSource]))
        "the diagnostics name the order's author: the sequence was measured, not declared")
    (is (= 1 (get-in res [:diagnostics :bid])))
    (is (= 3 (get-in res [:diagnostics :chainDepth])))
    (is (= "cx/gpt-6.2-sol" (get-in res [:diagnostics :head :modelId])))
    (is (= {:maxOutputPrice 1 :order "measured-value" :refreshMs 300000 :exclude ["gemini4argon"]}
           (:parameters res))
        "the chain states its parameters where a declared chain once stated its member list")
    (is (some #(re-find #"no intelligence score" %) (get-in res [:diagnostics :warnings]))
        "the diagnostics count and name the models it could not rank, cheapest first")))
