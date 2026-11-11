(ns swr-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]))

(defn run-node-eval [js-code]
  (let [{:keys [exit out err]} (sh "node" "--input-type=module" "-e" js-code)]
    (if (zero? exit)
      (json/parse-string out true)
      (throw (ex-info (str "Node eval error: " err) {:err err :out out})))))

(deftest test-dynamic-threshold-and-swr-sync
  (testing "Switches models immediately when max_price is passed dynamically in body or headers"
    (let [res (run-node-eval
               "import { createPriceCache, updateSpotPrices } from './src/router/pricing.js';
                import { createMetricsStore } from './src/router/metrics.js';
                import { rankCandidates } from './src/router/pareto.js';

                const priceCache = createPriceCache();
                const metricsStore = createMetricsStore();

                // glm-budget is an ordered cascade: head ali/glm-5.3 (seeded
                // $0.132) is eligible at the 0.50 tier-0 ceiling, so the
                // head serves (sol-budget reroutes to glm-budget)
                const candDefault = rankCandidates({
                  model: 'infered/sol-budget',
                  priceCache,
                  metricsStore,
                  maxFallbackPrice: 0.50
                });

                // User tightens threshold to 0.10 -> the glm pair (0.132/0.176)
                // is priced out; grok-4.7 (wired 2026-11-11, $0.006) is first eligible
                const candTight = rankCandidates({
                  model: 'infered/sol-budget',
                  priceCache,
                  metricsStore,
                  maxFallbackPrice: 0.10
                });

                // Live market price update: Sol ask increases to $0.14
                updateSpotPrices(priceCache, [
                  { providerId: 'inferhub-alpha', modelId: 'cx/gpt-5.6-sol', prompt: 0.02, completion: 0.14 },
                  { providerId: 'inferhub-beta', modelId: 'cx/gpt-5.6-sol', prompt: 0.02, completion: 0.15 },
                  { providerId: 'inferhub-gamma', modelId: 'cx/gpt-5.6-sol', prompt: 0.02, completion: 0.16 }
                ]);

                // Query with standard tier-0 ceiling (0.50) -> sol fixture
                // stays invisible (excised), so the head keeps serving
                const candAfterSpike = rankCandidates({
                  model: 'infered/sol-budget',
                  priceCache,
                  metricsStore,
                  maxFallbackPrice: 0.50
                });

                console.log(JSON.stringify({
                  defaultModel: candDefault[0]?.modelId,
                  tightModel: candTight[0]?.modelId,
                  afterSpikeModel: candAfterSpike[0]?.modelId,
                  afterSpikePrice: candAfterSpike[0]?.outputTokenPrice
                }));")]
      ;; glm-budget is an ordered cascade (head = preferred): ali/glm-5.3
      ;; ($0.132 seeded) is eligible at the 0.50 tier-0 ceiling, so the head
      ;; serves. grok-4.7 (wired 2026-11-11, $0.006, position 3) takes over
      ;; the moment the glm pair is priced out — the 0.10 tight ceiling
      ;; proves exactly that. The sol fixture asks stay invisible (excised)
      ;; through the price spike.
      (is (= "ali/glm-5.3" (:defaultModel res)))
      (is (= "grok/grok-4.7" (:tightModel res))
          "0.10 ceiling prices out the glm pair; grok ($0.006) is the first eligible member")
      (is (= "ali/glm-5.3" (:afterSpikeModel res)))
      (is (<= (:afterSpikePrice res) 0.50)))))
