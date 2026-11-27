(ns auto-budget-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]))

;; infered/auto-budget: PARAMETERS, not a list (owner directive 2026-11-27).
;; Membership is every text model the live feed quotes inside the $0.50 bid
;; that carries a known intelligence score. The served order is measured value
;; (intelligence x reliability x speed / effectiveBlended). The same snapshot,
;; staleness, quota and diagnostics mechanism serves infered/auto — that chain's
;; order is declared, this one is found.

(defn run-node-eval [js]
  (let [{:keys [exit out err]} (sh "node" "--input-type=module" "-e" js)]
    (if (zero? exit) (json/parse-string out true)
        (throw (ex-info (str "Node eval error: " err) {:out out})))))

(def ^:private model-fn
  "const model = (id, asks, extra = {}) => ({id, pricing: {asks_in: asks.map(x => x/5), asks_out: asks}, ...extra});")

(def ^:private board-fn
  "const board = {board: {publishedOn: '2026-11-26'}, ranking: [
     {model: 'gemini-4-carbon', score: 60}, {model: 'kimi-k3-1-pro', score: 46},
     {model: 'grok-4-7', score: 35}, {model: 'glm-5-4', score: 40},
     {model: 'gemini-4-argon', score: 70}]};")

(deftest pool-admits-only-scored-text-asks-inside-the-budget
  (let [res (run-node-eval
             (str
              "import { buildAutoSnapshot, chainDiagnostics } from './src/router/auto.js';
               import { MARKET_CHAINS } from './src/router/chains.js';
               import { intelligenceFromBoard } from './src/router/intelligence.js';
               import { CEILING_EXEMPT_MODELS } from './src/router/catalog.js';
               const chain = MARKET_CHAINS['infered/auto-budget'];
               " model-fn board-fn "
               const feed = {data: [
                 model('ag/gemini-4-carbon', [0.10, 0.50, 0.60]),
                 model('cx/gpt-6-astra', [0.60]),
                 model('ali/kimi-k3.1-pro', [0.20, 0.5001]),
                 model('grok/grok-4.7', [0.006, 0.008]),
                 model('zai/glm-5.4', [0.0044]),
                 model('ag/gemini-4-argon', [0.05]),
                 model('alitp/wan2.7-image', [0.02], {output_modality: 'image'}),
                 model('cb/deepseek-v4', [0.04]),
                 {id: 'canonical-no-book'}
               ]};
               const snapshot = buildAutoSnapshot(feed, 7200000, chain, intelligenceFromBoard(board));
               const pick = (m) => ({completion: m.completion, askCount: m.askCount, inCapDepth: m.inCapDepth, depthPrice: m.depthPrice});
               console.log(JSON.stringify({model: snapshot.model, bid: snapshot.bid, periodMs: snapshot.periodMs,
                 order: snapshot.models.map(m => m.modelId),
                 sources: snapshot.models.map(m => [m.modelId, m.score, m.scoreSource]),
                 carbon: pick(snapshot.models[0]), kimi: pick(snapshot.models.find(m => m.modelId === 'ali/kimi-k3.1-pro')),
                 excluded: snapshot.excluded, unscored: snapshot.unscored,
                 diagnostics: chainDiagnostics(snapshot, chain),
                 astraExemptElsewhere: CEILING_EXEMPT_MODELS.includes('cx/gpt-6-astra')}));"))]
    (is (= "infered/auto-budget" (:model res)))
    (is (= 0.5 (:bid res)) "the chain's bid is the owner's ceiling: half a dollar per million output tokens")
    (is (= 300000 (:periodMs res)) "a discovered chain re-reads the market every five minutes, not every hour")
    (is (= ["ag/gemini-4-carbon" "ali/kimi-k3.1-pro" "zai/glm-5.4" "grok/grok-4.7"] (:order res))
        "a cold snapshot lists every scored text model inside the bid, best score first")
    (is (= [["ag/gemini-4-carbon" 60 "board"] ["ali/kimi-k3.1-pro" 46 "board"]
            ["zai/glm-5.4" 40 "board"] ["grok/grok-4.7" 35 "board"]] (:sources res))
        "the live board's number wins on the family, including a maker's own spelling of the same model")
    (is (= {:completion 0.1 :askCount 3 :inCapDepth 2 :depthPrice 0.5} (:carbon res))
        "an ask exactly at $0.50 is inside the ceiling")
    (is (= 1 (:inCapDepth (:kimi res))) "half a cent above the ceiling is outside it")
    (is (= [{:modelId "cx/gpt-6-astra" :reason "over_cap" :ask 0.6}] (:excluded res))
        "an over-cap scored model is excluded WITH its disqualifying ask")
    (is (= ["cb/deepseek-v4"] (:unscored res))
        "a model quoted inside the bid with no score is counted, not served and not called excluded")
    (is (not-any? #(#{"ag/gemini-4-argon" "alitp/wan2.7-image" "canonical-no-book"} %)
                  (concat (:order res) (:unscored res) (mapv :modelId (:excluded res))))
        "an owner exclusion, an image model and an unpriced canonical id are none of the three lists")
    (is (true? (:astraExemptElsewhere res))
        "context: astra is ceiling-exempt on the cascade products, and that exemption does not reach here")
    (is (= {:bid 0.5 :chainDepth 4 :gateCounts {:over_cap 1} :orderSource "measured-value"}
           (select-keys (:diagnostics res) [:bid :chainDepth :gateCounts :orderSource])))
    (is (= {:count 1 :cheapest ["cb/deepseek-v4"]} (get-in res [:diagnostics :unscored])))))

(deftest served-order-is-measured-intelligence-per-dollar
  (let [res (run-node-eval
             (str
              "import { getAutoSnapshot, buildAutoSnapshot } from './src/router/auto.js';
               import { MARKET_CHAINS } from './src/router/chains.js';
               import { intelligenceFromBoard } from './src/router/intelligence.js';
               " model-fn board-fn "
               const now = 7200000, period = Math.floor(now / 300000);
               const budget = MARKET_CHAINS['infered/auto-budget'], declared = MARKET_CHAINS['infered/auto'];
               const scored = intelligenceFromBoard(board);
               const serve = async (payload, health, chain) => {
                 const row = {hour: period, payload: JSON.stringify(payload)};
                 const db = {prepare(sql) { return {bind() { return {
                   async first() {
                     if (sql.includes('WHERE id = ?1')) return health ? {payload: JSON.stringify(health)} : null;
                     return sql.includes(`'${chain.rowId}'`) ? row : null;
                   },
                   async all() { return {results: []}; }, async run() {}, async batch() { return []; }
                 }; }}; }};
                 return getAutoSnapshot({ROUTING_DB: db, INFERHUB_API_KEY: 'k'}, async () => { throw Error('a current row is served, never refreshed'); }, now, chain);
               };
               const slim = (s) => s.models.map(m => [m.modelId, m.valueTier, m.valueScore]);
               const cheapGrok = {data: [
                 model('ag/gemini-4-carbon', [0.40, 0.50]),
                 model('ali/kimi-k3.1-pro', [0.20, 0.30]),
                 model('grok/grok-4.7', [0.006, 0.008])
               ]};
               const cheapCarbon = {data: [
                 model('ag/gemini-4-carbon', [0.10, 0.12]),
                 model('ali/kimi-k3.1-pro', [0.20, 0.30]),
                 model('grok/grok-4.7', [0.30, 0.44])
               ]};
               const unmeasured = await serve(buildAutoSnapshot(cheapGrok, now, budget, scored), null, budget);
               const priced = await serve(buildAutoSnapshot(cheapCarbon, now, budget, scored), null, budget);
               const health = {windowHours: 24, computedAt: now, fleet: {ok: 21, fail: 6, rate: 0.777, fastestMs: 2000, failureClasses: {}}, models: {
                 'grok/grok-4.7': {ok: 20, fail: 0, avgMs: 2000, lastOkAt: now - 1000, lastFailAt: null},
                 'ali/kimi-k3.1-pro': {ok: 1, fail: 0, avgMs: 8000, lastOkAt: now - 1000, lastFailAt: null},
                 'ag/gemini-4-carbon': {ok: 0, fail: 6, avgMs: null, lastOkAt: null, lastFailAt: now - 20 * 60000},
                 'cx/gpt-6.2-sol': {ok: 0, fail: 6, avgMs: null, lastOkAt: null, lastFailAt: now - 20 * 60000}
               }};
               const measured = await serve(buildAutoSnapshot(cheapCarbon, now, budget, scored), health, budget);
               const foreign = buildAutoSnapshot(cheapCarbon, now, budget, scored);
               foreign.models.push({modelId: 'cb/deepseek-v4', prompt: 0.001, completion: 0.004});
               const filtered = await serve(foreign, null, budget);
               const declaredMeasured = await serve(buildAutoSnapshot({data: [
                 model('cx/gpt-6.2-sol', [0.19]), model('ali/qwen4-max', [0.27]), model('zai/glm-5.4', [0.88])
               ]}, now, declared), health, declared);
               console.log(JSON.stringify({unmeasured: slim(unmeasured), priced: slim(priced), measured: slim(measured),
                 filtered: filtered.models.map(m => m.modelId), declared: slim(declaredMeasured),
                 sources: [unmeasured.diagnostics.orderSource, declaredMeasured.diagnostics.orderSource],
                 parameters: unmeasured.parameters, attempts: unmeasured.models.map(m => m.measuredAttempts)}));"))]
    (is (= [["grok/grok-4.7" "unproven"] ["ali/kimi-k3.1-pro" "unproven"] ["ag/gemini-4-carbon" "unproven"]]
           (mapv #(subvec % 0 2) (:unmeasured res)))
        "with no attempt history the order is intelligence per dollar")
    (is (> (nth (first (:unmeasured res)) 2) (nth (nth (:unmeasured res) 2) 2))
        "the cheapest member leads on value even though it is the least intelligent")
    (is (= ["ag/gemini-4-carbon" "ali/kimi-k3.1-pro" "grok/grok-4.7"] (mapv first (:priced res)))
        "the same models at other prices serve score order — the order follows the book, not a list")
    (is (= [["grok/grok-4.7" "proven"] ["ali/kimi-k3.1-pro" "proven"] ["ag/gemini-4-carbon" "disproven"]]
           (mapv #(subvec % 0 2) (:measured res)))
        "what a member has done outranks what it promises")
    (is (zero? (nth (nth (:measured res) 2) 2)) "a member that never answered has a value of zero")
    (is (= ["cx/gpt-6.2-sol" "ali/qwen4-max" "zai/glm-5.4"] (mapv first (:declared res)))
        "the SAME health data leaves the declared chain in the owner's sequence")
    (is (= "disproven" (nth (first (:declared res)) 1)))
    (is (= ["measured-value" "owner-declared"] (:sources res)))
    (is (= ["ag/gemini-4-carbon" "ali/kimi-k3.1-pro" "grok/grok-4.7"] (:filtered res))
        "a persisted row cannot smuggle in a model the chain cannot score")
    (is (= {:maxOutputPrice 0.5 :order "measured-value" :refreshMs 300000 :exclude ["gemini4argon"]}
           (:parameters res))
        "a discovered chain states its parameters where a declared chain states its member list")
    (is (= [0 0 0] (:attempts res)))))

(deftest ranking-holds-the-chain-bid-and-never-loosens-it
  (let [res (run-node-eval
             "import { rankCandidates } from './src/router/pareto.js';
              import { createPriceCache } from './src/router/pricing.js';
              import { createMetricsStore } from './src/router/metrics.js';
              const snap = {models:[
                {modelId:'ag/gemini-4-carbon',prompt:0.2,completion:0.5,score:60},
                {modelId:'cx/gpt-6-astra',prompt:0.2,completion:0.51,score:57},
                {modelId:'grok/grok-4.7',prompt:0.01,completion:0.3,score:35},
                {modelId:'ali/kimi-k3.1-pro',prompt:0.08,completion:0.4,score:46},
                {modelId:'cb/deepseek-v4',prompt:0.1,completion:0.01}
              ]};
              const rank = (cap) => rankCandidates({model:'infered/auto-budget',autoSnapshot:snap,
                priceCache:createPriceCache(),metricsStore:createMetricsStore(),maxFallbackPrice:cap});
              const slim = (cands) => cands.map(c => [c.modelId, c.maxOutputPrice, c.budgetTier]);
              console.log(JSON.stringify({normal:slim(rank(null)), loosened:slim(rank(5)),
                tightened:slim(rank(0.35)), nothing:rank(0.001).length}));")]
    (is (= [["ag/gemini-4-carbon" 0.5 0.5] ["grok/grok-4.7" 0.5 0.5] ["ali/kimi-k3.1-pro" 0.5 0.5]] (:normal res))
        "the snapshot's order is the try order, and every candidate carries the chain's bid")
    (is (not-any? #(= "cx/gpt-6-astra" (first %)) (concat (:normal res) (:loosened res)))
        "a 51-cent ask stays out: the cascade products' ceiling exemption does not reach this chain")
    (is (not-any? #(= "cb/deepseek-v4" (first %)) (:normal res))
        "a stale snapshot naming an unscored model at any price cannot serve it")
    (is (= (:normal res) (:loosened res))
        "a caller's higher X-Infered-Max-Price cannot loosen the owner's ceiling")
    (is (= [["grok/grok-4.7" 0.35 0.35]] (:tightened res))
        "a caller's lower cap tightens the bid and drops what no longer fits")
    (is (zero? (:nothing res)) "no bid, no candidates")))

(deftest worker-serves-the-budget-chain-from-its-own-row
  (let [res (run-node-eval
             (str
              "import worker from './src/worker.js';
               import { buildAutoSnapshot } from './src/router/auto.js';
               import { MARKET_CHAINS } from './src/router/chains.js';
               import { intelligenceFromBoard } from './src/router/intelligence.js';
               " model-fn board-fn "
               const now = Date.now(), period = Math.floor(now / 300000);
               const scored = intelligenceFromBoard(board);
               const budget = buildAutoSnapshot({data: [
                 model('ag/gemini-4-carbon', [0.10, 0.12]),
                 model('ali/kimi-k3.1-pro', [0.20, 0.30]),
                 model('grok/grok-4.7', [0.30, 0.44])
               ]}, now, MARKET_CHAINS['infered/auto-budget'], scored);
               const other = buildAutoSnapshot({data: [model('cb/deepseek-v4', [0.0044, 0.005])]}, now, MARKET_CHAINS['infered/auto']);
               const rows = {'auto-budget': {hour: period, payload: JSON.stringify(budget)},
                 'auto': {hour: Math.floor(now / 3600000), payload: JSON.stringify(other)}};
               const db = {prepare(sql) { return {bind() { return {
                 async first() {
                   if (sql.includes('WHERE id = ?1')) return null;
                   for (const id of Object.keys(rows)) if (sql.includes(`'${id}'`)) return rows[id];
                   return null;
                 },
                 async all() { return {results: []}; }, async run() {}, async batch() { return []; }
               }; }}; }};
               const sent = [];
               globalThis.fetch = async (url, opts) => {
                 if (url.endsWith('/models')) throw new Error('a current row is served, never refreshed');
                 const body = JSON.parse(opts.body);
                 sent.push([body.model, opts.headers['x-max-output-price'], body.max_tokens]);
                 if (body.model === 'ag/gemini-4-carbon') return new Response('no capacity', {status: 503});
                 return new Response(JSON.stringify({choices: [{index: 0, message: {role: 'assistant', content: 'budget answer'}, finish_reason: 'stop'}],
                   usage: {prompt_tokens: 5, completion_tokens: 1, total_tokens: 6}}), {status: 200, headers: {'Content-Type': 'application/json'}});
               };
               const env = {ROUTING_DB: db, INFERHUB_API_KEY: 'key-from-test', INFERHUB_BASE_URL: 'https://example.test/v1'};
               const ctx = {waitUntil() {}};
               const post = (name, extra) => worker.fetch(new Request('https://worker.test/v1/chat/completions', {method: 'POST',
                 headers: {'Content-Type': 'application/json'},
                 body: JSON.stringify({model: name, max_tokens: 8, stream: false, messages: [{role: 'user', content: 'Reply with OK'}], ...extra})}), env, ctx);
               const response = await post('infered/auto-budget', {});
               const body = await response.json();
               const inspect = async (path) => (await worker.fetch(new Request('https://worker.test' + path), env, ctx)).json();
               const budgetView = await inspect('/v1/auto-budget');
               const autoView = await inspect('/v1/auto');
               const cache = [];
               for (let i = 0; i < 2; i++) {
                 const before = sent.length;
                 const r = await post('auto-budget', {temperature: 0});
                 await r.text();
                 cache.push([r.headers.get('x-infered-cache'), r.status, sent.length - before]);
               }
               console.log(JSON.stringify({status: response.status, selected: response.headers.get('x-infered-selected-model'),
                 budgetTier: response.headers.get('x-infered-budget-tier'), content: body.choices[0].message.content,
                 sent: sent.slice(0, 2), leaked: sent.some(s => s[0] === 'cb/deepseek-v4'),
                 budget: [budgetView.diagnostics.bid, budgetView.diagnostics.orderSource, budgetView.models.map(m => m.modelId)],
                 auto: [autoView.diagnostics.bid, autoView.diagnostics.orderSource, autoView.models.map(m => m.modelId)],
                 declared: [budgetView.declared, autoView.declared], parameters: budgetView.parameters,
                 cache}));"))]
    (is (= 200 (:status res)))
    (is (= "ali/kimi-k3.1-pro" (:selected res))
        "the measured head fails, so the chain tries the next member")
    (is (= "0.5" (:budgetTier res)))
    (is (= "budget answer" (:content res)))
    (is (= [["ag/gemini-4-carbon" "0.5" 8] ["ali/kimi-k3.1-pro" "0.5" 8]] (:sent res))
        "every upstream attempt carries the chain's bid")
    (is (false? (:leaked res))
        "the request is served from the auto-budget row; the other chain's cheaper member never leaks in")
    (is (= [0.5 "measured-value" ["ag/gemini-4-carbon" "ali/kimi-k3.1-pro" "grok/grok-4.7"]] (:budget res)))
    (is (= [1 "owner-declared" []] (:auto res))
        "GET /v1/auto still inspects the declared chain, and an unscored model is not a member of it")
    (is (nil? (first (:declared res))) "a discovered chain has no declared member list")
    (is (= ["cx/gpt-6.2-sol" "ali/qwen4-max" "zai/glm-5.4"] (second (:declared res))))
    (is (= {:maxOutputPrice 0.5 :order "measured-value" :refreshMs 300000 :exclude ["gemini4argon"]}
           (:parameters res)))
    (is (= [["MISS" 200 2] ["MISS" 200 2]] (:cache res))
        "a market chain is never answered from the deterministic cache")))

(deftest budget-chain-fails-closed-and-never-escapes-to-another-chain
  (let [res (run-node-eval
             "import worker from './src/worker.js';
              const hour = Math.floor(Date.now() / 300000);
              const sent = [];
              globalThis.fetch = async (url) => {
                if (url.endsWith('/models')) throw new Error('a current row is served, never refreshed');
                sent.push(url);
                return new Response('{}', {status: 200});
              };
              const post = (env) => worker.fetch(new Request('https://worker.test/v1/chat/completions', {method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({model: 'infered/auto-budget', max_tokens: 8,
                  messages: [{role: 'user', content: 'hi'}]})}), env, {waitUntil() {}});
              const noRow = await post({});
              const noRowBody = await noRow.json();
              const pricedOutRow = {hour, payload: JSON.stringify({hour, models: [], excluded: []})};
              const db = {prepare(sql) { return {bind() { return {
                async first() { return sql.includes(`'auto-budget'`) ? pricedOutRow : null; },
                async all() { return {results: []}; }, async run() {}, async batch() { return []; }
              }; }}; }};
              const pricedOut = await post({ROUTING_DB: db, INFERHUB_API_KEY: 'key-from-test', INFERHUB_BASE_URL: 'https://example.test/v1'});
              const pricedOutBody = await pricedOut.json();
              console.log(JSON.stringify({noRow: noRow.status, noRowMessage: noRowBody.error.message,
                pricedOut: pricedOut.status, pricedOutType: pricedOutBody.error.type, upstreamCalls: sent.length}));")]
    (is (= 503 (:noRow res)))
    (is (.contains (:noRowMessage res) "no safe route")
        "no durable snapshot, no route")
    (is (= 400 (:pricedOut res)))
    (is (= "invalid_request_error" (:pricedOutType res)))
    (is (zero? (:upstreamCalls res))
        "a book with nothing under $0.50 fails rather than escaping to a chain allowed to serve above it")))

(deftest heartbeat-measures-the-head-of-every-chain-under-its-own-bid
  (let [res (run-node-eval
             "import worker from './src/worker.js';
              import { QUOTA_PROBE_CAP } from './src/router/auto.js';
              const run = async (firstFeedDies) => {
                const rows = {}, written = [], probes = [], errors = [], waits = [];
                let feedCalls = 0;
                console.error = (...a) => errors.push(a.join(' '));
                globalThis.fetch = async (url, opts) => {
                  if (String(url).endsWith('/models')) {
                    feedCalls++;
                    if (firstFeedDies && feedCalls === 1) throw new Error('feed down');
                    const many = Array.from({length: 20}, (_, i) =>
                      ({id: 'grok/grok-4-' + i, pricing: {asks_in: [0.01], asks_out: [0.01 + i / 1000]}}));
                    return new Response(JSON.stringify({data: feedCalls === 1 ? [
                      {id: 'cx/gpt-6.2-sol', pricing: {asks_in: [0.1], asks_out: [0.19]}},
                      {id: 'ali/qwen4-max', pricing: {asks_in: [0.01], asks_out: [0.27]}}]
                      : [{id: 'grok/grok-4.7', pricing: {asks_in: [0.012], asks_out: [0.006]}}, ...many]}), {status: 200});
                  }
                  if (String(url).includes('models.fru.dev')) {
                    const ranking = [{model: 'grok-4-7', score: 35}];
                    for (let i = 0; i < 20; i++) ranking.push({model: 'grok-4-' + i, score: 10});
                    return new Response(JSON.stringify({ranking}), {status: 200});
                  }
                  const body = JSON.parse(opts.body);
                  probes.push([body.model, opts.headers['x-max-output-price'], body.max_tokens]);
                  return new Response(JSON.stringify({choices: [{message: {content: 'OK'}}]}), {status: 200});
                };
                const rowIdOf = (sql) => {
                  const at = sql.indexOf('VALUES (');
                  return at < 0 || sql[at + 8] !== \"'\" ? null : sql.slice(at + 9, sql.indexOf(\"'\", at + 9));
                };
                const db = {prepare(sql) { return {bind(...args) { return {
                  async first() {
                    for (const id of Object.keys(rows)) if (sql.includes(`'${id}'`)) return rows[id];
                    return null;
                  },
                  async all() { return {results: []}; },
                  async run() { const id = rowIdOf(sql); if (id) { rows[id] = {hour: args[args.length - 2], payload: args[args.length - 1]}; written.push(id); } },
                  async batch() { return []; }
                }; }}; }};
                await worker.scheduled({}, {ROUTING_DB: db, INFERHUB_API_KEY: 'k', INFERHUB_BASE_URL: 'https://example.test/v1'}, {waitUntil: p => waits.push(p)});
                await Promise.all(waits);
                return {probes, written, errors};
              };
              const both = await run(false);
              console.log(JSON.stringify({both, oneDead: await run(true), cap: QUOTA_PROBE_CAP}));")]
    (is (= 12 (:cap res)))
    (is (= [["cx/gpt-6.2-sol" "1" 8] ["ali/qwen4-max" "1" 8]]
           (take 2 (:probes (:both res))))
        "the declared chain is probed in full, under its own bid, before the budget chain")
    (let [budget (drop 2 (:probes (:both res)))]
      (is (= 12 (count budget)) "a discovered chain of 21 scored members probes only its served head")
      (is (every? #(= ["0.5" 8] (rest %)) budget) "every budget probe carries the $0.50 bid")
      (is (= "grok/grok-4.7" (ffirst budget)) "the head is the best value, probed first"))
    (is (= ["auto" "auto-quota" "auto-intelligence" "auto-budget" "auto-budget-quota"]
           (:written (:both res)))
        "each chain keeps its own rows, and the board is read before the chain that needs it")
    (is (empty? (:errors (:both res))))
    (is (= 12 (count (:probes (:oneDead res)))))
    (is (= ["auto-intelligence" "auto-budget" "auto-budget-quota"] (:written (:oneDead res))))
    (is (= 1 (count (:errors (:oneDead res)))))
    (is (.contains (first (:errors (:oneDead res))) "infered/auto skipped")
        "a dead book is logged and skipped; the other chain is still measured")))

(deftest budget-chain-is-a-listed-product-with-no-member-list
  (let [res (run-node-eval
             "import { canonicalModelName, resolveVirtualModel, resolveStrongLink, CHAIN_FALLBACKS, VIRTUAL_ALIASES } from './src/router/catalog.js';
              import { MARKET_CHAINS, chainParameters } from './src/router/chains.js';
              const chain = MARKET_CHAINS['infered/auto-budget'];
              console.log(JSON.stringify({bare: canonicalModelName(' Auto-Budget '), prefixed: canonicalModelName('infered/auto-budget'),
                members: resolveVirtualModel('infered/auto-budget'), discover: chain.discover, hasMembers: 'members' in chain,
                parameters: chainParameters(chain),
                strongLink: resolveStrongLink('infered/auto-budget'), chainFallback: CHAIN_FALLBACKS['infered/auto-budget'] ?? null,
                listed: Object.keys(VIRTUAL_ALIASES).includes('infered/auto-budget')}));")]
    (is (= "infered/auto-budget" (:bare res))
        "the bare name is the same product")
    (is (= "infered/auto-budget" (:prefixed res)))
    (is (= [] (:members res)) "the catalog lists the name and no members: membership is discovered")
    (is (true? (:discover res)))
    (is (false? (:hasMembers res)) "a discovered chain has no member list to go stale")
    (is (= {:maxOutputPrice 0.5 :order "measured-value" :refreshMs 300000 :exclude ["gemini4argon"]}
           (:parameters res)))
    (is (nil? (:strongLink res)))
    (is (nil? (:chainFallback res))
        "terminal on purpose — a declared fallback chain could serve above the ceiling")
    (is (true? (:listed res)))))
