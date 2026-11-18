(ns repair-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]))

(defn run-node-eval [js-code]
  (let [{:keys [exit out err]} (sh "node" "--input-type=module" "-e" js-code)]
    (if (zero? exit)
      (json/parse-string out true)
      (throw (ex-info (str "Node eval error: " err) {:err err :out out})))))

(deftest test-400-naming-a-field-is-repaired-then-served
  (testing "A 400 that names reasoning effort must be retried on the SAME candidate without the field, not spent as a chain failure"
    (let [res (run-node-eval
               "import { executeWithFallback } from './src/router/client.js';
                import { createMetricsStore } from './src/router/metrics.js';

                const calls = [];
                const fetchFn = async (url, opts) => {
                  const body = JSON.parse(opts.body);
                  calls.push({ model: body.model, hasEffort: 'reasoning_effort' in body });
                  if ('reasoning_effort' in body) {
                    return new Response(JSON.stringify({ error: { code: 'invalid-argument', message: 'Invalid reasoning effort.' } }),
                      { status: 400, headers: { 'Content-Type': 'application/json' } });
                  }
                  return new Response(JSON.stringify({
                    choices: [{ message: { content: 'served without the bad field' } }],
                    usage: { prompt_tokens: 5, completion_tokens: 4 }
                  }), { status: 200, headers: { 'Content-Type': 'application/json' } });
                };

                const result = await executeWithFallback({
                  candidates: [{ providerId: 'inferhub-auto', modelId: 'grok/grok-4.7', savingsPct: 90, blendedPrice: 0.02 }],
                  requestBody: { model: 'auto', reasoning_effort: 'minimal', messages: [{ role: 'user', content: 'hi' }] },
                  apiKey: 'test-key',
                  metricsStore: createMetricsStore(),
                  fetchFn
                });

                console.log(JSON.stringify({
                  success: result.success,
                  served: result.selectedCandidate && result.selectedCandidate.modelId,
                  calls: calls.length,
                  firstHadEffort: calls[0] ? calls[0].hasEffort : null,
                  secondHadEffort: calls[1] ? calls[1].hasEffort : null
                }));")]
      (is (:success res) "the request is served instead of dying on an optional parameter")
      (is (= "grok/grok-4.7" (:served res)) "the repaired candidate answers — it was never broken")
      (is (= 2 (:calls res)) "exactly one repair attempt, no retry storm")
      (is (true? (:firstHadEffort res)) "the caller's body goes upstream unchanged first")
      (is (false? (:secondHadEffort res)) "the retry drops only the field upstream named"))))

(deftest test-stream-400-naming-a-field-is-repaired-then-served
  (testing "The splice path repairs the body too: a single-member chain that 400s on effort must still serve"
    (let [res (run-node-eval
               "import { executeWithFallback } from './src/router/client.js';
                import { createMetricsStore } from './src/router/metrics.js';

                const enc = new TextEncoder();
                const sse = (delta) => 'data: ' + JSON.stringify({ choices: [{ delta }] }) + '\\n\\n';
                let calls = 0;
                const fetchFn = async (url, opts) => {
                  calls += 1;
                  const body = JSON.parse(opts.body);
                  if ('reasoning_effort' in body) {
                    return new Response(JSON.stringify({ error: { code: 'invalid-argument', message: 'Invalid reasoning effort.' } }),
                      { status: 400, headers: { 'Content-Type': 'application/json' } });
                  }
                  return new Response(new ReadableStream({
                    async start(c) {
                      c.enqueue(enc.encode(sse({ role: 'assistant', content: '' })));
                      await new Promise(r => setTimeout(r, 10));
                      c.enqueue(enc.encode(sse({ content: 'repaired stream' })));
                      c.enqueue(enc.encode('data: [DONE]\\n\\n'));
                      c.close();
                    }
                  }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
                };

                const result = await executeWithFallback({
                  candidates: [{ providerId: 'inferhub-auto', modelId: 'grok/grok-4.7', savingsPct: 90, blendedPrice: 0.02 }],
                  requestBody: { stream: true, reasoning_effort: 'minimal', messages: [{ role: 'user', content: 'hi' }] },
                  apiKey: 'test-key',
                  metricsStore: createMetricsStore(),
                  fetchFn
                });

                const reader = result.stream.getReader();
                const dec = new TextDecoder();
                let received = '';
                try { for (;;) { const { done, value } = await reader.read(); if (done) break; received += dec.decode(value, { stream: true }); } } catch (e) {}
                await new Promise(r => setTimeout(r, 30));

                console.log(JSON.stringify({
                  success: result.success,
                  calls,
                  hasContent: received.includes('repaired stream'),
                  doneMarkers: (received.match(/\\[DONE\\]/g) || []).length,
                  servedModel: result.getMetrics().servedModel
                }));")]
      (is (:success res) "the repair closes the gap instead of 503-ing a one-member chain")
      (is (= 2 (:calls res)) "one refused open, one repaired open")
      (is (:hasContent res) "content reached the client")
      (is (= 1 (:doneMarkers res)) "exactly one DONE — no duplicate terminator from the retry")
      (is (= "grok/grok-4.7" (:servedModel res))))))

(deftest test-400-that-names-nothing-is-not-repaired
  (testing "A 400 with no field name must not mutate the body: guessing which parameter to drop is a silent behaviour change"
    (let [res (run-node-eval
               "import { repairBodyFor } from './src/router/repair.js';
                const body = { model: 'auto', temperature: 0.2, reasoning_effort: 'minimal', messages: [] };
                console.log(JSON.stringify({
                  plain: repairBodyFor(body, 400, 'Upstream rejected the request') === null,
                  forbidden: repairBodyFor(body, 403, 'Invalid reasoning effort.') === null,
                  okStatus: repairBodyFor(body, 200, 'Invalid reasoning effort.') === null,
                  repaired: repairBodyFor(body, 400, 'Provider x returned 400: {\\\"message\\\":\\\"Invalid reasoning effort.\\\"}'),
                  originalUntouched: body.reasoning_effort === 'minimal'
                }));")]
      (is (true? (:plain res)) "a fieldless 400 is left alone")
      (is (true? (:forbidden res)) "a 403 is an account verdict, never a repair trigger")
      (is (true? (:okStatus res)) "only 400 triggers repair")
      (is (false? (contains? (:repaired res) :reasoning_effort)) "the named field is the one removed")
      (is (true? (:originalUntouched res)) "the caller's body is never mutated in place"))))
