(ns diagnostics-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]))

(defn run-node-eval [js]
  (let [{:keys [exit out err]} (sh "node" "--input-type=module" "-e" js)]
    (if (zero? exit) (json/parse-string out true)
        (throw (ex-info (str "Node eval error: " err) {:out out})))))

;; A D1 stand-in: answers each prepared statement from `fixtures`, keyed by a
;; substring of the SQL. Every query is recorded so a test can assert WHICH
;; window/limit the diagnostic actually asked for.
(def fake-db-js
  "const mkDb = (fixtures = {}) => {
     const seen = [];
     const answer = (sql) => {
       seen.push(sql.replace(/\\s+/g, ' ').trim());
       const hit = Object.keys(fixtures).find(k => sql.includes(k));
       return hit ? fixtures[hit] : null;
     };
     const stmt = (sql) => {
       const res = () => answer(sql);
       const bound = {
         first: async () => { const r = res(); return r ? (r.first ?? null) : null; },
         all: async () => ({ results: (res()?.all ?? []) }),
         run: async () => ({ success: true })
       };
       return { bind: () => bound, first: bound.first, all: bound.all, run: bound.run };
     };
     return { seen, prepare: stmt };
   };")

(deftest failure-classes-group-by-model-and-status
  (let [res (run-node-eval
             (str fake-db-js
                  "import { failureClasses } from './src/router/diagnostics.js';
                   const db = mkDb({'FROM routing_attempts': {all: [
                     {model: 'grok/grok-4.7', status: 503, n: 3, last_at: '2026-11-17 23:51:02'},
                     {model: 'ag/gemini-4-carbon', status: 403, n: 2, last_at: '2026-11-17 23:49:59'}
                   ]}});
                   const classes = await failureClasses(db);
                   console.log(JSON.stringify({classes, sql: db.seen[0]}));"))]
    (is (= 2 (count (:classes res))))
    (is (= {:model "grok/grok-4.7" :status 503 :n 3}
           (select-keys (first (:classes res)) [:model :status :n])))
    (is (re-find #"ok = 0" (:sql res)) "only failures count — a success is not a failure class")
    (is (re-find #"-24 hours" (:sql res)) "the window is 24h, not all time")))

(deftest recent-failures-carry-the-upstream-error-string
  (let [res (run-node-eval
             (str fake-db-js
                  "import { recentFailures } from './src/router/diagnostics.js';
                   const db = mkDb({'FROM routing_attempts': {all: [
                     {ts: '2026-11-17 23:51:02', model: 'grok/grok-4.7', status: 503,
                      error: 'no available provider for grok/grok-4.7'}
                   ]}});
                   const recent = await recentFailures(db);
                   console.log(JSON.stringify({recent, sql: db.seen[0]}));"))]
    (is (= "no available provider for grok/grok-4.7" (:error (first (:recent res))))
        "the raw upstream message survives into diagnostics — the whole point of the endpoint")
    (is (re-find #"LIMIT 10" (:sql res)) "bounded: the last 10, never the whole ledger")))

(deftest a-broken-chain-read-still-yields-health-and-failures
  ;; No market key means getAutoSnapshot throws. Diagnostics must degrade to a
  ;; named chain error and STILL answer the health + failure questions.
  (let [res (run-node-eval
             (str fake-db-js
                  "import { buildDiagnostics } from './src/router/diagnostics.js';
                   const db = mkDb({
                     'SELECT payload FROM auto_chain_snapshot': {first: {payload: JSON.stringify({
                       computedAt: 1000, windowHours: 24,
                       fleet: {ok: 9, fail: 1, rate: 0.9, fastestMs: 1200},
                       models: {'a/one': {ok: 9, fail: 1, avgMs: 1200}}
                     })}},
                     'FROM routing_attempts': {all: []}
                   });
                   const out = await buildDiagnostics({}, db, 2000);
                   console.log(JSON.stringify(out));"))]
    (is (string? (get-in res [:chain :error]))
        "a chain read failure is reported, not thrown at the caller")
    (is (= 0.9 (get-in res [:health :fleet :rate])) "health is independent of the chain read")
    (is (= [] (get-in res [:failures :classes])))
    (is (= 2000 (:timestamp res)))))

(deftest served-chain-carries-order-gates-and-diagnostics
  ;; Cached chain row for THIS hour, cached health row: buildDiagnostics must
  ;; compose them into the served order plus the diagnostics block.
  (let [res (run-node-eval
             (str fake-db-js
                  "import { buildDiagnostics } from './src/router/diagnostics.js';
                   const now = 3600000 * 100;
                   const hour = Math.floor(now / 3600000);
                   const payload = {models: [
                     {modelId: 'grok/grok-4.7', score: 50, effectiveBlended: 0.5, bid: 1},
                     {modelId: 'cx/gpt-6.1-terra', score: 40, effectiveBlended: 0.4, bid: 1, thinBook: true}
                   ]};
                   const db = mkDb({
                     'SELECT hour, payload FROM auto_chain_snapshot': {first: {hour, payload: JSON.stringify(payload)}},
                     'SELECT payload FROM auto_chain_snapshot': {first: {payload: JSON.stringify({
                       computedAt: now, windowHours: 24,
                       fleet: {ok: 20, fail: 2, rate: 0.909, fastestMs: 900},
                       models: {'grok/grok-4.7': {ok: 18, fail: 0, avgMs: 900},
                                'cx/gpt-6.1-terra': {ok: 2, fail: 0, avgMs: 1800}}
                     })}},
                     'FROM routing_attempts': {all: []}
                   });
                   const out = await buildDiagnostics({ROUTING_DB: db, INFERHUB_API_KEY: 'k'}, db, now);
                   console.log(JSON.stringify(out));"))]
    (is (= ["grok/grok-4.7" "cx/gpt-6.1-terra"] (get-in res [:chain :order]))
        "declared members are admitted in value order, thin book included")
    (is (false? (get-in res [:chain :stale]))
        "the chain window is judged against the same clock as the payload timestamp")
    (is (contains? (get-in res [:chain :diagnostics]) :warnings)
        "the chain diagnostics block rides along, so /v1/diagnostics is self-contained")
    (is (= "grok/grok-4.7" (get-in res [:chain :models 0 :modelId]))
        "the full model rows ship too — the caller should not need a second call")))

(deftest circuit-tripped-members-move-to-the-back-of-the-attempt-order
  ;; pareto.js sorts a tripped circuit to the END of the chain, so the served
  ;; order and the tried order diverge exactly when a member is failing. The
  ;; diagnostic has to say so, or the head model looks like it answered.
  (let [res (run-node-eval
             "import { circuitState } from './src/router/diagnostics.js';
              import { createMetricsStore } from './src/router/metrics.js';
              const store = createMetricsStore();
              store.providers['inferhub-auto::grok/grok-4.7'] = {
                circuitState: 'open', circuitTrippedAt: Date.now(),
                emaLatency: 400, emaTtft: 200, totalRequests: 3,
                successfulRequests: 0, failedRequests: 3, recentSamples: []
              };
              const models = [{modelId: 'grok/grok-4.7'}, {modelId: 'ali/kimi-k3.1-pro'}];
              const out = circuitState(models, store);
              const none = circuitState(models, null);
              console.log(JSON.stringify({out, none}));")]
    (is (= ["grok/grok-4.7"] (:tripped (:out res)))
        "a tripped circuit is named, not silently reordered")
    (is (= ["ali/kimi-k3.1-pro" "grok/grok-4.7"] (:attemptedOrder (:out res)))
        "the attempted order puts the tripped member last — the order a request really tries")
    (is (= [] (:tripped (:none res)))
        "no metrics store means unknown, never a guess")
    (is (= ["grok/grok-4.7" "ali/kimi-k3.1-pro"] (:attemptedOrder (:none res)))
        "with no circuit evidence the served order stands")))
