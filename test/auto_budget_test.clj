(ns auto-budget-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]))

;; infered/auto-budget: a SCORED POOL whose served order is measured value
;; (intelligence x reliability x speed / effectiveBlended) under a hard $0.50/M
;; output bid (owner directive 2026-11-25). It rides the same snapshot,
;; staleness, quota and diagnostics mechanism as infered/auto — that chain's
;; order is declared, this one is found.

(defn run-node-eval [js]
  (let [{:keys [exit out err]} (sh "node" "--input-type=module" "-e" js)]
    (if (zero? exit) (json/parse-string out true)
        (throw (ex-info (str "Node eval error: " err) {:out out})))))

(def ^:private model-fn
  "const model = (id, asks) => ({id, pricing: {asks_in: asks.map(x => x/5), asks_out: asks}});")

(deftest pool-admits-only-asks-inside-the-budget-with-no-exemption
  (let [res (run-node-eval
             (str
              "import { buildAutoSnapshot, chainDiagnostics } from './src/router/auto.js';
               import { MARKET_CHAINS } from './src/router/chains.js';
               import { CEILING_EXEMPT_MODELS } from './src/router/catalog.js';
               const chain = MARKET_CHAINS['infered/auto-budget'];
               " model-fn "
               const feed = {data: [
                 model('ag/gemini-4-carbon', [0.10, 0.50, 0.60]),
                 model('cx/gpt-6-astra', [0.60]),
                 model('ali/kimi-k3.1-pro', [0.20, 0.5001]),
                 model('grok/grok-4.7', [0.006, 0.008]),
                 model('zai/glm-5.4', [0.0044]),
                 model('cx/gpt-6.2-sol', [0.19])
               ]};
               const snapshot = buildAutoSnapshot(feed, 7200000, chain);
               const pick = (m) => ({completion: m.completion, askCount: m.askCount, inCapDepth: m.inCapDepth, depthPrice: m.depthPrice});
               console.log(JSON.stringify({model: snapshot.model, bid: snapshot.bid,
                 order: snapshot.models.map(m => m.modelId), scores: snapshot.models.map(m => [m.modelId, m.score]),
                 carbon: pick(snapshot.models[0]), kimi: pick(snapshot.models[1]),
                 excluded: snapshot.excluded, diagnostics: chainDiagnostics(snapshot, chain),
                 astraExemptElsewhere: CEILING_EXEMPT_MODELS.includes('cx/gpt-6-astra')}));"))]
    (is (= "infered/auto-budget" (:model res)))
    (is (= 0.5 (:bid res)) "the chain's bid is the owner's ceiling: half a dollar per million output tokens")
    (is (= ["ag/gemini-4-carbon" "ali/kimi-k3.1-pro" "grok/grok-4.7"] (:order res))
        "a cold snapshot lists the pool best score first — the sequence measured health then re-sorts")
    (is (= [["ag/gemini-4-carbon" 60] ["ali/kimi-k3.1-pro" 46] ["grok/grok-4.7" 35.5]] (:scores res))
        "each member carries its RECORDED intelligence score; nothing is invented at build time")
    (is (= {:completion 0.1 :askCount 3 :inCapDepth 2 :depthPrice 0.5} (:carbon res))
        "an ask exactly at $0.50 is inside the ceiling, the same on-the-line rule the $1.00 bid follows")
    (is (= 1 (:inCapDepth (:kimi res)))
        "half a cent above the ceiling is outside it: 0.5001 does not count as an in-cap seller")
    (is (= [{:modelId "cx/gpt-6-astra" :reason "over_cap" :ask 0.6}
            {:modelId "oai/gpt-6.1-sol" :reason "not_in_feed"}
            {:modelId "cx/gpt-6.1-terra" :reason "not_in_feed"}
            {:modelId "oai/gpt-6.1-terra" :reason "not_in_feed"}
            {:modelId "ali/glm-5.3" :reason "not_in_feed"}]
           (:excluded res))
        "an over-cap member is excluded WITH its disqualifying ask, and a pool member this feed does not quote is named — neither is ever dropped silently")
    (is (true? (:astraExemptElsewhere res))
        "context for the assertion above: astra is ceiling-exempt on the cascade products")
    (is (not-any? #(#{"zai/glm-5.4" "cx/gpt-6.2-sol"} (:modelId %)) (concat (:excluded res) (mapv #(array-map :modelId %) (:order res))))
        "a cheap model with no recorded score is neither served nor named: it is not in the pool")
    (is (= {:bid 0.5 :chainDepth 3 :gateCounts {:over_cap 1 :not_in_feed 4} :orderSource "measured-value"}
           (select-keys (:diagnostics res) [:bid :chainDepth :gateCounts :orderSource]))
        "diagnostics report the chain's own bid and say the order was measured, not declared")))

(deftest served-order-is-measured-intelligence-per-dollar
  (let [res (run-node-eval
             (str
              "import { getAutoSnapshot, buildAutoSnapshot } from './src/router/auto.js';
               import { MARKET_CHAINS } from './src/router/chains.js';
               " model-fn "
               const now = 7200000, hour = 2;
               const budget = MARKET_CHAINS['infered/auto-budget'], declared = MARKET_CHAINS['infered/auto'];
               // a D1 that holds one current row for the chain under test, and an optional health payload
               const serve = async (payload, health, chain) => {
                 const row = {hour, payload: JSON.stringify(payload)};
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
               const unmeasured = await serve(buildAutoSnapshot(cheapGrok, now, budget), null, budget);
               const priced = await serve(buildAutoSnapshot(cheapCarbon, now, budget), null, budget);
               const health = {windowHours: 24, computedAt: now, fleet: {ok: 21, fail: 6, rate: 0.777, fastestMs: 2000, failureClasses: {}}, models: {
                 'grok/grok-4.7': {ok: 20, fail: 0, avgMs: 2000, lastOkAt: now - 1000, lastFailAt: null},
                 'ali/kimi-k3.1-pro': {ok: 1, fail: 0, avgMs: 8000, lastOkAt: now - 1000, lastFailAt: null},
                 'ag/gemini-4-carbon': {ok: 0, fail: 6, avgMs: null, lastOkAt: null, lastFailAt: now - 20 * 60000},
                 'cx/gpt-6.2-sol': {ok: 0, fail: 6, avgMs: null, lastOkAt: null, lastFailAt: now - 20 * 60000}
               }};
               const measured = await serve(buildAutoSnapshot(cheapCarbon, now, budget), health, budget);
               // a row written before a pool change still names a model the pool no longer does
               const foreign = buildAutoSnapshot(cheapCarbon, now, budget);
               foreign.models.push({modelId: 'zai/glm-5.4', prompt: 0.0014, completion: 0.0044});
               const filtered = await serve(foreign, null, budget);
               const declaredMeasured = await serve(buildAutoSnapshot({data: [
                 model('cx/gpt-6.2-sol', [0.19]), model('ali/qwen4-max', [0.27]), model('zai/glm-5.4', [0.88])
               ]}, now, declared), health, declared);
               console.log(JSON.stringify({unmeasured: slim(unmeasured), priced: slim(priced), measured: slim(measured),
                 filtered: filtered.models.map(m => m.modelId), declared: slim(declaredMeasured),
                 sources: [unmeasured.diagnostics.orderSource, declaredMeasured.diagnostics.orderSource],
                 attempts: unmeasured.models.map(m => m.measuredAttempts)}));"))]
    (is (= [["grok/grok-4.7" "unproven" 5634.921]
            ["ali/kimi-k3.1-pro" "unproven" 195.745]
            ["ag/gemini-4-carbon" "unproven" 151.899]]
           (:unmeasured res))
        "with no attempt history the order is pure intelligence per dollar: the least intelligent member leads on price and the most intelligent is last")
    (is (= ["ag/gemini-4-carbon" "ali/kimi-k3.1-pro" "grok/grok-4.7"] (mapv first (:priced res)))
        "the same pool at other prices serves score order — the order follows the book, not a list")
    (is (= [["grok/grok-4.7" "proven"] ["ali/kimi-k3.1-pro" "proven"] ["ag/gemini-4-carbon" "disproven"]]
           (mapv #(subvec % 0 2) (:measured res)))
        "what a member has done outranks what it promises: a cheap score-60 model with six failures and no answer drops below every member that answers")
    (is (pos? (nth (nth (:measured res) 0) 2)))
    (is (zero? (nth (nth (:measured res) 2) 2)) "a member that never answered has a value of zero, however cheap")
    (is (= ["cx/gpt-6.2-sol" "ali/qwen4-max" "zai/glm-5.4"] (mapv first (:declared res)))
        "the SAME health data leaves the declared chain in the owner's sequence")
    (is (= "disproven" (nth (first (:declared res)) 1))
        "...while still labelling its failing head, so one mechanism serves both kinds of order")
    (is (= ["measured-value" "owner-declared"] (:sources res))
        "each chain's diagnostics say who decided its order")
    (is (= ["ag/gemini-4-carbon" "ali/kimi-k3.1-pro" "grok/grok-4.7"] (:filtered res))
        "a persisted row cannot smuggle in a model the pool no longer names")
    (is (= [0 0 0] (:attempts res)))))

(deftest ranking-holds-the-chain-bid-and-never-loosens-it
  (let [res (run-node-eval
             "import { rankCandidates } from './src/router/pareto.js';
              import { createPriceCache } from './src/router/pricing.js';
              import { createMetricsStore } from './src/router/metrics.js';
              const snap = {models:[
                {modelId:'ag/gemini-4-carbon',prompt:0.2,completion:0.5,score:60},
                {modelId:'cx/gpt-6-astra',prompt:0.2,completion:0.51,score:57},
                {modelId:'grok/grok-4.7',prompt:0.01,completion:0.3,score:35.5},
                {modelId:'ali/kimi-k3.1-pro',prompt:0.08,completion:0.4,score:46},
                {modelId:'cx/gpt-6.2-sol',prompt:0.1,completion:0.01}
              ]};
              const rank = (cap) => rankCandidates({model:'infered/auto-budget',autoSnapshot:snap,
                priceCache:createPriceCache(),metricsStore:createMetricsStore(),maxFallbackPrice:cap});
              const slim = (cands) => cands.map(c => [c.modelId, c.maxOutputPrice, c.budgetTier]);
              console.log(JSON.stringify({normal:slim(rank(null)), loosened:slim(rank(5)),
                tightened:slim(rank(0.35)), nothing:rank(0.001).length}));")]
    (is (= [["ag/gemini-4-carbon" 0.5 0.5] ["grok/grok-4.7" 0.5 0.5] ["ali/kimi-k3.1-pro" 0.5 0.5]] (:normal res))
        "the snapshot's measured order is the try order, and every candidate carries the chain's bid")
    (is (not-any? #(= "cx/gpt-6-astra" (first %)) (concat (:normal res) (:loosened res)))
        "the cascade products' ceiling exemption does not reach this chain: a 51-cent ask stays out")
    (is (not-any? #(= "cx/gpt-6.2-sol" (first %)) (:normal res))
        "a stale snapshot naming a non-member at any price cannot serve it")
    (is (= (:normal res) (:loosened res))
        "a caller's higher X-Infered-Max-Price cannot loosen the owner's ceiling")
    (is (= [["grok/grok-4.7" 0.35 0.35]] (:tightened res))
        "a caller's lower cap tightens the bid and drops what no longer fits")
    (is (zero? (:nothing res)) "no bid, no candidates — there is no Infinity tier to fall back to")))

(deftest worker-serves-the-budget-chain-from-its-own-row
  (let [res (run-node-eval
             (str
              "import worker from './src/worker.js';
               import { buildAutoSnapshot } from './src/router/auto.js';
               import { MARKET_CHAINS } from './src/router/chains.js';
               " model-fn "
               const now = Date.now(), hour = Math.floor(now / 3600000);
               const budget = buildAutoSnapshot({data: [
                 model('ag/gemini-4-carbon', [0.10, 0.12]),
                 model('ali/kimi-k3.1-pro', [0.20, 0.30]),
                 model('grok/grok-4.7', [0.30, 0.44])
               ]}, now, MARKET_CHAINS['infered/auto-budget']);
               // the OTHER chain's row holds a model this pool must never serve
               const other = buildAutoSnapshot({data: [model('zai/glm-5.4', [0.0044, 0.005])]}, now, MARKET_CHAINS['infered/auto']);
               const rows = {'auto-budget': {hour, payload: JSON.stringify(budget)}, 'auto': {hour, payload: JSON.stringify(other)}};
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
                 sent: sent.slice(0, 2), glm54Served: sent.some(s => s[0] === 'zai/glm-5.4'),
                 budget: [budgetView.diagnostics.bid, budgetView.diagnostics.orderSource, budgetView.models.map(m => m.modelId)],
                 auto: [autoView.diagnostics.bid, autoView.diagnostics.orderSource, autoView.models.map(m => m.modelId)],
                 cache}));"))]
    (is (= 200 (:status res)))
    (is (= "ali/kimi-k3.1-pro" (:selected res))
        "the measured head fails, so the chain tries the next member — the x-infered-selected-model header names who answered")
    (is (= "0.5" (:budgetTier res)))
    (is (= "budget answer" (:content res)))
    (is (= [["ag/gemini-4-carbon" "0.5" 8] ["ali/kimi-k3.1-pro" "0.5" 8]] (:sent res))
        "every upstream attempt carries the chain's bid, so the marketplace enforces the ceiling at seller selection")
    (is (false? (:glm54Served res))
        "the request is served from the auto-budget row; the other chain's cheaper member never leaks in")
    (is (= [0.5 "measured-value" ["ag/gemini-4-carbon" "ali/kimi-k3.1-pro" "grok/grok-4.7"]] (:budget res))
        "GET /v1/auto-budget inspects the chain: its bid, who decided the order, and the order")
    (is (= [1 "owner-declared" ["zai/glm-5.4"]] (:auto res))
        "GET /v1/auto still inspects the declared chain — one endpoint shape per chain, one mechanism behind it")
    (is (= [["MISS" 200 2] ["MISS" 200 2]] (:cache res))
        "a market chain is never answered from the deterministic cache: its order is measured, so a repeat is a new decision, not a fixture")))

(deftest budget-chain-fails-closed-and-never-escapes-to-another-chain
  (let [res (run-node-eval
             "import worker from './src/worker.js';
              const hour = Math.floor(Date.now() / 3600000);
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
        "no durable snapshot, no route: the chain never falls back to seeded or official prices")
    (is (= 400 (:pricedOut res)))
    (is (= "invalid_request_error" (:pricedOutType res)))
    (is (zero? (:upstreamCalls res))
        "a pool with nothing under $0.50 fails rather than escaping to a chain allowed to serve above it")))

(deftest heartbeat-measures-every-chain-under-its-own-bid
  (let [res (run-node-eval
             "import worker from './src/worker.js';
              const run = async (firstFeedDies) => {
                const rows = {}, written = [], probes = [], errors = [], waits = [];
                let feedCalls = 0;
                console.error = (...a) => errors.push(a.join(' '));
                globalThis.fetch = async (url, opts) => {
                  if (url.endsWith('/models')) {
                    feedCalls++;
                    if (firstFeedDies && feedCalls === 1) throw new Error('feed down');
                    return new Response(JSON.stringify({data: feedCalls === 1 ? [
                      {id: 'cx/gpt-6.2-sol', pricing: {asks_in: [0.1], asks_out: [0.19]}},
                      {id: 'ali/qwen4-max', pricing: {asks_in: [0.01], asks_out: [0.27]}}]
                      : [
                      {id: 'ag/gemini-4-carbon', pricing: {asks_in: [0.02], asks_out: [0.10, 0.5]}},
                      {id: 'grok/grok-4.7', pricing: {asks_in: [0.012], asks_out: [0.006]}}]}), {status: 200});
                  }
                  const body = JSON.parse(opts.body);
                  probes.push([body.model, opts.headers['x-max-output-price'], body.max_tokens]);
                  return new Response(JSON.stringify({choices: [{message: {content: 'OK'}}]}), {status: 200});
                };
                // the row id each INSERT names: a literal in the SQL, so the text carries it
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
              console.log(JSON.stringify({both: await run(false), oneDead: await run(true)}));")]
    (is (= [["cx/gpt-6.2-sol" "1" 8] ["ali/qwen4-max" "1" 8]
            ["grok/grok-4.7" "0.5" 8] ["ag/gemini-4-carbon" "0.5" 8]]
           (:probes (:both res)))
        "one fire measures both chains, one after the other, each probe under its OWN chain's bid — the budget chain in its served order (grok is the better value)")
    (is (= ["auto" "auto-quota" "auto-budget" "auto-budget-quota"] (:written (:both res)))
        "each chain keeps its own snapshot row and its own quota row")
    (is (empty? (:errors (:both res))))
    (is (= [["grok/grok-4.7" "0.5" 8] ["ag/gemini-4-carbon" "0.5" 8]] (:probes (:oneDead res))))
    (is (= ["auto-budget" "auto-budget-quota"] (:written (:oneDead res))))
    (is (= 1 (count (:errors (:oneDead res)))))
    (is (.contains (first (:errors (:oneDead res))) "infered/auto skipped")
        "a dead book is logged and skipped; the other chain is still measured")))

(deftest budget-chain-is-a-listed-product-with-only-scored-members
  (let [res (run-node-eval
             "import { canonicalModelName, resolveVirtualModel, resolveStrongLink, CHAIN_FALLBACKS, VIRTUAL_ALIASES } from './src/router/catalog.js';
              import { AUTO_BUDGET_POOL, MARKET_CHAINS } from './src/router/chains.js';
              console.log(JSON.stringify({bare: canonicalModelName(' Auto-Budget '), prefixed: canonicalModelName('infered/auto-budget'),
                members: resolveVirtualModel('infered/auto-budget'), pool: AUTO_BUDGET_POOL.map(m => m.id),
                scores: AUTO_BUDGET_POOL.map(m => [m.id, m.score]),
                everyScoreRecorded: AUTO_BUDGET_POOL.every(m => Number.isFinite(m.score)),
                strongLink: resolveStrongLink('infered/auto-budget'), chainFallback: CHAIN_FALLBACKS['infered/auto-budget'] ?? null,
                listed: Object.keys(VIRTUAL_ALIASES).includes('infered/auto-budget')}));")]
    (is (= "infered/auto-budget" (:bare res))
        "the bare name is the same product: leaving off infered/ must not land on the default cascade")
    (is (= "infered/auto-budget" (:prefixed res)))
    (is (= (:pool res) (:members res)) "the catalog lists the pool in its recorded order")
    (is (true? (:everyScoreRecorded res))
        "every member has a RECORDED score — an intelligence-per-dollar search cannot rank intelligence it does not know")
    (is (= [["ag/gemini-4-carbon" 60] ["cx/gpt-6-astra" 57] ["oai/gpt-6.1-sol" 55] ["cx/gpt-6.1-terra" 51]
            ["oai/gpt-6.1-terra" 51] ["ali/kimi-k3.1-pro" 46] ["grok/grok-4.7" 35.5] ["ali/glm-5.3" 32]]
           (:scores res))
        "the Artificial Analysis snapshot of 2026-11-10, the values the auto chain ranked by until it was declared")
    (is (not-any? #(#{"zai/glm-5.4" "cx/gpt-6.2-sol" "ali/qwen4-max" "ag/gemini-4-argon"} %) (:pool res))
        "the 6.2 generation, qwen4-max and glm-5.4 have no score on record, and argon stays out (owner directive 2026-11-11)")
    (is (nil? (:strongLink res)) "no strong link: the order is measured, so X-Infered-Tier has nothing to start at")
    (is (nil? (:chainFallback res))
        "terminal on purpose — a declared fallback chain could serve above the ceiling")
    (is (true? (:listed res)) "GET /v1/models offers the product")))
