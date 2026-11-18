(ns value-ranking-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]))

(defn run-node-eval [js]
  (let [{:keys [exit out err]} (sh "node" "--input-type=module" "-e" js)]
    (if (zero? exit) (json/parse-string out true)
        (throw (ex-info (str "Node eval error: " err) {:out out})))))

(deftest value-orders-by-intelligence-reliability-speed-and-cost
  ;; Fleet: 180 ok / 40 fail (rate 0.818), fastest measured member 3000ms.
  ;; X: score 60, $0.60/M, 90 ok / 10 fail, 10s  -> reliability 0.893, speed 0.3
  ;; Y: score 40, $0.40/M, 90 ok / 10 fail,  3s  -> reliability 0.893, speed 1.0
  ;; Z: score 50, $0.50/M,  0 ok / 20 fail,  --  -> reliability 0.273, speed 0
  (let [res (run-node-eval
             "import { applyValueRanking } from './src/router/health.js';
              const snapshot = {models: [
                {modelId: 'x/smart-slow', score: 60, effectiveBlended: 0.6},
                {modelId: 'y/cheap-fast', score: 40, effectiveBlended: 0.4},
                {modelId: 'z/dead', score: 50, effectiveBlended: 0.5}
              ]};
              const health = {windowHours: 24, computedAt: 1,
                fleet: {ok: 180, fail: 40, rate: 0.818, fastestMs: 3000},
                models: {
                  'x/smart-slow': {ok: 90, fail: 10, avgMs: 10000},
                  'y/cheap-fast': {ok: 90, fail: 10, avgMs: 3000},
                  'z/dead': {ok: 0, fail: 20, avgMs: null}
                }};
              const ranked = applyValueRanking(snapshot, health);
              const pick = id => ranked.models.filter(m => m.modelId === id)[0];
              console.log(JSON.stringify({
                order: ranked.models.map(m => m.modelId),
                fleetRate: ranked.health.fleetRate,
                fastestMs: ranked.health.fastestMs,
                x: pick('x/smart-slow'), y: pick('y/cheap-fast'), z: pick('z/dead')
              }));")]
    (is (= ["y/cheap-fast" "x/smart-slow" "z/dead"] (:order res))
        "a cheap, fast, reliable member outranks a smarter but 3.3x slower and 1.5x dearer one")
    (is (= 0.818 (:fleetRate res)))
    (is (= 3000 (:fastestMs res)))
    (is (= {:reliability 0.893 :speed 0.3 :valueScore 26.776 :valueTier "proven"}
           (select-keys (:x res) [:reliability :speed :valueScore :valueTier]))
        "value = score x reliability x speed / effectiveBlended = 60 x 0.893 x 0.3 / 0.6")
    (is (= {:reliability 0.893 :speed 1 :valueScore 89.255 :valueTier "proven"}
           (select-keys (:y res) [:reliability :speed :valueScore :valueTier]))
        "the fastest measured member carries no speed penalty: 40 x 0.893 x 1 / 0.4")
    (is (= {:reliability 0.273 :speed 0 :valueScore 0 :valueTier "disproven"}
           (select-keys (:z res) [:reliability :speed :valueScore :valueTier]))
        "a member that was tried and never once answered has zero value — it cannot serve")))

(deftest unproven-members-rank-between-proven-and-disproven
  (let [res (run-node-eval
             "import { applyValueRanking } from './src/router/health.js';
              const snapshot = {models: [
                {modelId: 'a/proven', score: 30, effectiveBlended: 0.3},
                {modelId: 'b/untested', score: 90, effectiveBlended: 0.9},
                {modelId: 'c/dead', score: 80, effectiveBlended: 0.8}
              ]};
              const health = {windowHours: 24, computedAt: 1,
                fleet: {ok: 90, fail: 10, rate: 0.9, fastestMs: 2000},
                models: {
                  'a/proven': {ok: 90, fail: 10, avgMs: 2000},
                  'c/dead': {ok: 0, fail: 12, avgMs: null}
                }};
              const ranked = applyValueRanking(snapshot, health);
              console.log(JSON.stringify({
                order: ranked.models.map(m => m.modelId),
                tiers: ranked.models.map(m => m.valueTier),
                untested: ranked.models.filter(m => m.modelId === 'b/untested')[0]
              }));")]
    (is (= ["a/proven" "b/untested" "c/dead"] (:order res))
        "no evidence is not evidence of absence: an untested member sits behind measured winners and ahead of proven failures")
    (is (= ["proven" "unproven" "disproven"] (:tiers res)))
    (is (= {:reliability 0.9 :speed 1 :measuredAttempts 0}
           (select-keys (:untested res) [:reliability :speed :measuredAttempts]))
        "an untested member carries the fleet rate and no speed penalty")))

(deftest attempts-are-recorded-one-row-per-upstream-try
  (let [res (run-node-eval
             "import { recordAttempts } from './src/router/health.js';
              const writes = [];
              const db = {prepare(sql) { return {bind(...args) { return {sql, args, async run() {}}; }}; },
                async batch(stmts) { writes.push(...stmts); }};
              const n = await recordAttempts({ROUTING_DB: db}, {
                requestedModel: 'infered/auto',
                failoverErrors: [
                  {modelId: 'cx/gpt-6.1-terra', providerId: 'inferhub-auto', status: 503, latencyMs: 410, error: 'no available provider'},
                  {modelId: 'ag/gemini-4-carbon', providerId: 'inferhub-auto', status: 403, latencyMs: 385, error: 'model disabled'}
                ],
                served: {modelId: 'grok/grok-4.7', providerId: 'inferhub-auto'},
                servedLatencyMs: 3011.4
              });
              console.log(JSON.stringify({n, rows: writes.map(w => w.args)}));")]
    (is (= 3 (:n res)) "two failed attempts plus the one that answered")
    (is (= [["infered/auto" "cx/gpt-6.1-terra" "inferhub-auto" 0 503 410 "no available provider"]
            ["infered/auto" "ag/gemini-4-carbon" "inferhub-auto" 0 403 385 "model disabled"]
            ["infered/auto" "grok/grok-4.7" "inferhub-auto" 1 200 3011 nil]]
           (mapv vec (:rows res)))
        "every attempt carries its own model, status and latency — the fact the decision row could not hold")))

(deftest one-failure-is-an-anecdote-not-a-verdict
  (let [res (run-node-eval
             "import { applyValueRanking } from './src/router/health.js';
              const snapshot = {models: [
                {modelId: 'a/proven', score: 30, effectiveBlended: 0.3},
                {modelId: 'b/one-bad-try', score: 90, effectiveBlended: 0.9},
                {modelId: 'c/three-bad-tries', score: 80, effectiveBlended: 0.8}
              ]};
              const health = {windowHours: 24, computedAt: 1,
                fleet: {ok: 90, fail: 10, rate: 0.9, fastestMs: 2000},
                models: {
                  'a/proven': {ok: 90, fail: 10, avgMs: 2000},
                  'b/one-bad-try': {ok: 0, fail: 1, avgMs: null},
                  'c/three-bad-tries': {ok: 0, fail: 3, avgMs: null}
                }};
              const ranked = applyValueRanking(snapshot, health);
              console.log(JSON.stringify({
                order: ranked.models.map(m => m.modelId),
                tiers: ranked.models.map(m => m.valueTier)}));")]
    (is (= ["proven" "unproven" "disproven"] (:tiers res))
        "one refused attempt is an anecdote; three with no answer is a verdict")
    (is (= ["a/proven" "b/one-bad-try" "c/three-bad-tries"] (:order res)))))

(deftest health-aggregate-splits-failure-classes-and-streaks
  ;; One ratio cannot tell "503 no capacity" from "403 model disabled" from a
  ;; network death, so the aggregate keeps the class, the newest verdict, and
  ;; the tail streak alongside it.
  (let [res (run-node-eval
             "import { aggregateHealth } from './src/router/health.js';
              const rows = {
                agg: [
                  {model:'grok/grok-4.7', ok:3, fail:2, avg_ms:2500, last_ok_s:1700000000, last_fail_s:1700000600},
                  {model:'ag/gemini-4-carbon', ok:0, fail:2, avg_ms:null, last_ok_s:null, last_fail_s:1700000500}
                ],
                classes: [
                  {model:'grok/grok-4.7', status:200, n:3},
                  {model:'grok/grok-4.7', status:503, n:2},
                  {model:'ag/gemini-4-carbon', status:403, n:1},
                  {model:'ag/gemini-4-carbon', status:null, n:1}
                ],
                tail: [
                  {model:'grok/grok-4.7', ok:0, status:503, error:'no available provider', rn:1},
                  {model:'grok/grok-4.7', ok:0, status:503, error:'no available provider', rn:2},
                  {model:'grok/grok-4.7', ok:1, status:200, error:null, rn:3},
                  {model:'ag/gemini-4-carbon', ok:0, status:403, error:'model disabled', rn:1}
                ]
              };
              const db = {prepare(sql) { return {bind() { return {
                async all() {
                  if (sql.includes('ROW_NUMBER')) return {results: rows.tail};
                  if (sql.includes('GROUP BY model, status')) return {results: rows.classes};
                  return {results: rows.agg};
                },
                async first() { return null; }, async run() {}
              }; }}; }};
              const h = await aggregateHealth(db, 1700000700000);
              const g = h.models['grok/grok-4.7'], c = h.models['ag/gemini-4-carbon'];
              console.log(JSON.stringify({
                grok: {ok:g.ok, fail:g.fail, avgMs:g.avgMs, lastOkAt:g.lastOkAt,
                  lastFailAt:g.lastFailAt, lastStatus:g.lastStatus, lastError:g.lastError,
                  streak:g.streak, s503:g.statusCounts['503'], s200:g.statusCounts['200']},
                carbon: {lastStatus:c.lastStatus, lastError:c.lastError, streak:c.streak,
                  network:c.statusCounts.network, avgMs:c.avgMs},
                fleet: h.fleet
              }));")]
    (is (= {:ok 3 :fail 2 :avgMs 2500 :lastOkAt 1700000000000 :lastFailAt 1700000600000
            :lastStatus 503 :lastError "no available provider" :streak 2 :s503 2 :s200 3}
           (:grok res))
        "the newest verdict, its class counts, and the tail streak all survive aggregation")
    (is (= {:lastStatus 403 :lastError "model disabled" :streak 1 :network 1 :avgMs nil}
           (:carbon res))
        "a network death and a 403 are counted separately, and a member that never answered has no speed")
    (is (= {:ok 3 :fail 4 :rate 0.429 :fastestMs 2500
            :failureClasses {:200 3 :503 2 :403 1 :network 1}}
           (:fleet res))
        "fleet totals carry the failure-class split, not just a success rate")))

(deftest a-recent-failure-cools-a-model-without-exiling-it
  ;; computedAt is the reference instant: the cooldown must be reproducible
  ;; from the cached payload, not from wall-clock time at read.
  (let [res (run-node-eval
             "import { applyValueRanking } from './src/router/health.js';
              const snapshot = {models: [
                {modelId: 'a/just-broke', score: 60, effectiveBlended: 0.6, inCapDepth: 3},
                {modelId: 'b/steady', score: 60, effectiveBlended: 0.6, inCapDepth: 3},
                {modelId: 'c/untested', score: 90, effectiveBlended: 0.9, inCapDepth: 3}
              ]};
              const base = {windowHours: 24, computedAt: 1700000600000,
                fleet: {ok: 90, fail: 10, rate: 0.9, fastestMs: 2000},
                models: {
                  'a/just-broke': {ok: 50, fail: 5, avgMs: 2000,
                    lastOkAt: 1699990000000, lastFailAt: 1700000540000},
                  'b/steady': {ok: 50, fail: 5, avgMs: 2000,
                    lastOkAt: 1700000400000, lastFailAt: 1699990000000}
                }};
              const now = applyValueRanking(snapshot, base);
              const cooled = applyValueRanking(snapshot, {...base, models: {
                ...base.models,
                'a/just-broke': {...base.models['a/just-broke'], lastFailAt: 1700000600000 - 20 * 60 * 1000}
              }});
              const pick = (r, id) => r.models.filter(m => m.modelId === id)[0];
              console.log(JSON.stringify({
                order: now.models.map(m => m.modelId),
                tiers: now.models.map(m => m.valueTier),
                brokeValue: pick(now, 'a/just-broke').valueScore,
                coolingFlag: pick(now, 'a/just-broke').cooling,
                afterCooldownTiers: cooled.models.map(m => m.valueTier),
                afterCooldownOrder: cooled.models.map(m => m.modelId)
              }));")]
    (is (= ["b/steady" "c/untested" "a/just-broke"] (:order res))
        "a member that failed 60s ago drops behind a steady peer and an untested one")
    (is (= ["proven" "unproven" "cooling"] (:tiers res)))
    (is (pos? (:brokeValue res))
        "cooling is an ordering decision, not a rewrite of the measured value")
    (is (true? (:coolingFlag res)))
    (is (= ["proven" "proven" "unproven"] (:afterCooldownTiers res))
        "the same failure older than the cooldown is history, not a verdict")
    (is (= ["a/just-broke" "b/steady" "c/untested"] (:afterCooldownOrder res))
        "back in the proven tier it leads again on value")))

(deftest thin-books-rank-behind-deep-books-once-both-have-proven-themselves
  (let [res (run-node-eval
             "import { applyValueRanking } from './src/router/health.js';
              const snapshot = {models: [
                {modelId: 'a/deep', score: 40, effectiveBlended: 0.8, inCapDepth: 4},
                {modelId: 'b/thin', score: 60, effectiveBlended: 0.2, inCapDepth: 1}
              ]};
              const health = {windowHours: 24, computedAt: 1700000600000,
                fleet: {ok: 90, fail: 10, rate: 0.9, fastestMs: 2000},
                models: {
                  'a/deep': {ok: 40, fail: 4, avgMs: 2000, lastOkAt: 1700000500000, lastFailAt: null},
                  'b/thin': {ok: 40, fail: 4, avgMs: 2000, lastOkAt: 1700000500000, lastFailAt: null}
                }};
              const ranked = applyValueRanking(snapshot, health);
              const pick = id => ranked.models.filter(m => m.modelId === id)[0];
              console.log(JSON.stringify({
                order: ranked.models.map(m => m.modelId),
                tiers: ranked.models.map(m => m.valueTier),
                thin: pick('b/thin'), deep: pick('a/deep')
              }));")]
    (is (= ["a/deep" "b/thin"] (:order res))
        "both answered; the four-seller book still takes the head slot over the one-seller book")
    (is (= ["proven" "thin"] (:tiers res)))
    (is (true? (get-in res [:thin :thinBook])))
    (is (> (get-in res [:thin :valueScore]) (get-in res [:deep :valueScore]))
        "the thin book's measured value is not falsified to justify the order — the tier carries the supply risk")))
