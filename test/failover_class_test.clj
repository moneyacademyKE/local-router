(ns failover-class-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]))

(defn run-node-eval [js-code]
  (let [{:keys [exit out err]} (sh "node" "--input-type=module" "-e" js-code)]
    (if (zero? exit)
      (json/parse-string out true)
      (throw (ex-info (str "Node eval error: " err) {:err err :out out})))))

(deftest test-model-scoped-400-fails-over
  (testing "A 400 is MODEL-scoped on this gateway ('Invalid reasoning effort'): it must fail over, never abort the chain"
    (let [res (run-node-eval
               "import { executeWithFallback } from './src/router/client.js';
                import { createMetricsStore } from './src/router/metrics.js';

                const metricsStore = createMetricsStore();
                const enc = new TextEncoder();
                const sse = (delta) => 'data: ' + JSON.stringify({ choices: [{ delta }] }) + '\\n\\n';

                const fetchFn = async (url, opts) => {
                  const prov = opts.headers['X-InferHub-Provider'];
                  if (prov === 'picky-node') {
                    // Exact body served live 2026-11-17: grok rejected the reasoning effort 84 times.
                    return new Response(JSON.stringify({ error: { code: 'invalid-argument', message: 'Invalid reasoning effort.' } }),
                      { status: 400, headers: { 'Content-Type': 'application/json' } });
                  }
                  return new Response(new ReadableStream({
                    async start(c) {
                      c.enqueue(enc.encode(sse({ role: 'assistant', content: '' })));
                      await new Promise(r => setTimeout(r, 10));
                      c.enqueue(enc.encode(sse({ content: 'served anyway' })));
                      c.enqueue(enc.encode('data: [DONE]\\n\\n'));
                      c.close();
                    }
                  }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
                };

                const result = await executeWithFallback({
                  candidates: [
                    { providerId: 'picky-node', modelId: 'grok/grok-4.7', savingsPct: 90, blendedPrice: 0.02 },
                    { providerId: 'patient-node', modelId: 'cx/gpt-6.1-terra', savingsPct: 70, blendedPrice: 0.31 }
                  ],
                  requestBody: { stream: true, messages: [{ role: 'user', content: 'hi' }] },
                  apiKey: 'test-key',
                  metricsStore,
                  fetchFn
                });

                const reader = result.stream.getReader();
                const dec = new TextDecoder();
                let received = '';
                try { for (;;) { const { done, value } = await reader.read(); if (done) break; received += dec.decode(value, { stream: true }); } } catch (e) {}
                await new Promise(r => setTimeout(r, 30));

                console.log(JSON.stringify({
                  success: result.success,
                  hasContent: received.includes('served anyway'),
                  hasDone: received.includes('[DONE]'),
                  servedModel: result.getMetrics().servedModel,
                  attempts: result.getMetrics().attempts
                }));")]
      (is (:success res) "the 400 must not end the request")
      (is (:hasContent res) "the second candidate's answer reaches the client")
      (is (:hasDone res))
      (is (= "cx/gpt-6.1-terra" (:servedModel res)) "ledger names the model that answered")
      (is (= 2 (:attempts res))))))

(deftest test-all-candidates-400-is-not-called-account-wide
  (testing "When every candidate 400s, the ledger must not claim 'every node answers the same 4xx' — that was a false account-wide verdict"
    (let [res (run-node-eval
               "import { executeWithFallback } from './src/router/client.js';
                import { createMetricsStore } from './src/router/metrics.js';

                const metricsStore = createMetricsStore();
                const outcomes = [];
                const fetchFn = async () => new Response(JSON.stringify({ error: { code: 'invalid-argument', message: 'Invalid reasoning effort.' } }),
                  { status: 400, headers: { 'Content-Type': 'application/json' } });

                const result = await executeWithFallback({
                  candidates: [
                    { providerId: 'a', modelId: 'grok/grok-4.7', savingsPct: 10, blendedPrice: 0.1 },
                    { providerId: 'b', modelId: 'cx/gpt-6.1-terra', savingsPct: 10, blendedPrice: 0.2 }
                  ],
                  requestBody: { stream: true, messages: [{ role: 'user', content: 'hi' }] },
                  apiKey: 'test-key',
                  metricsStore,
                  fetchFn,
                  onStreamOutcome: (o) => outcomes.push(o)
                });

                console.log(JSON.stringify({
                  committed: result.success,
                  status: result.status,
                  outcomeError: outcomes[0] ? outcomes[0].error : null,
                  attempts: result.attempts
                }));")]
      (is (false? (:committed res)) "nothing committed — the request fails honestly")
      (is (= 2 (:attempts res)) "both candidates were actually tried")
      (is (not= "non_retryable_candidate_open" (:outcomeError res))
          "a 400 is model-scoped: the ledger must not record it as an account-wide refusal"))))
