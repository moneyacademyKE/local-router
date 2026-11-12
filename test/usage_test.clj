(ns usage-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]))

(defn run-node-eval [js-code]
  (let [{:keys [exit out err]} (sh "node" "--input-type=module" "-e" js-code)]
    (if (zero? exit)
      (json/parse-string out true)
      (throw (ex-info (str "Node eval error: " err) {:err err :out out})))))

(deftest test-usage-frame-captured
  (testing "usage frames (trailing or embedded) are captured for the ledger, never hardcoded 20/30"
    (let [res (run-node-eval
               "import { executeWithFallback } from './src/router/client.js';

                const frames = [
                  'data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"Hi\"}}]}\\n\\n',
                  'data: {\"id\":\"x\",\"choices\":[],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7,\"total_tokens\":18,\"prompt_tokens_details\":{\"cached_tokens\":4}}}\\n\\n',
                  'data: [DONE]\\n\\n'
                ].join('');
                const metricsStore = { usage: {}, latency: {}, market: { samples: {}, success: {} } };
                const fetchFn = async () => new Response(frames, { status: 200, headers: { 'Content-Type': 'text/event-stream' } });

                const result = await executeWithFallback({
                  candidates: [{ providerId: 'u-node', modelId: 'zai/glm-5.3', savingsPct: 95, blendedPrice: 0.06, quote: { prompt: 0.1, completion: 0.05 } }],
                  requestBody: { stream: true, messages: [{ role: 'user', content: 'hi' }] },
                  apiKey: 'test-key',
                  metricsStore,
                  fetchFn
                });
                const reader = result.stream.getReader();
                const dec = new TextDecoder();
                let received = '';
                try { for (;;) { const { done, value } = await reader.read(); if (done) break; received += dec.decode(value, { stream: true }); } } catch (e) {}
                const m = result.getMetrics();
                console.log(JSON.stringify({
                  success: result.success,
                  hasDone: received.includes('[DONE]'),
                  usageRelayed: received.includes('\\\"prompt_tokens\\\":11'),
                  promptTokens: m.usage ? m.usage.promptTokens : 'absent',
                  completionTokens: m.usage ? m.usage.completionTokens : 'absent',
                  cachedTokens: m.usage ? m.usage.cachedTokens : 'absent',
                  costUsd: m.usage ? m.usage.costUsd : 'absent'
                }));")]
      (is (:success res) "stream completes")
      (is (:hasDone res))
      (is (:usageRelayed res) "the usage frame relays to the client untouched")
      (is (= 11 (:promptTokens res)) "prompt tokens captured from the usage frame")
      (is (= 7 (:completionTokens res)) "completion tokens captured from the usage frame")
      (is (= 4 (:cachedTokens res)) "cached tokens captured from prompt_tokens_details")
      (is (number? (:costUsd res)) "cost computed from the spot quote when upstream reports none"))))
