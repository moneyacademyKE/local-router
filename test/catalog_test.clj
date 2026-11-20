(ns catalog-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]))

(defn run-node-eval [js-code]
  (let [{:keys [exit out err]} (sh "node" "--input-type=module" "-e" js-code)]
    (if (zero? exit)
      (json/parse-string out true)
      (throw (ex-info (str "Node eval error: " err) {:err err :out out})))))

(deftest test-catalog-tiers
  (testing "Virtual models resolve to appropriate candidate models and tiers"
    (let [res (run-node-eval
               "import { resolveVirtualModel, MODEL_TIERS, VIRTUAL_ALIASES } from './src/router/catalog.js';
                console.log(JSON.stringify({
                  autoModels: resolveVirtualModel('infered/auto'),
                  fastModels: resolveVirtualModel('infered/fast'),
                  smartModels: resolveVirtualModel('infered/smart'),
                  reasoningModels: resolveVirtualModel('infered/reasoning'),
                  cascadeModels: resolveVirtualModel('infered/sol-budget'),
                  solExact: resolveVirtualModel('cx/gpt-5.6-sol'),
                  hasAliases: Object.keys(VIRTUAL_ALIASES).length > 0
                }));")]
      (is (:hasAliases res))
      (is (vector? (:autoModels res)))
      (is (> (count (:autoModels res)) 0))
      ;; Sol and the retired tail are absent; the surviving four-model chain is canonical.
      (is (= ["ali/glm-5.3", "grok/grok-4.7", "ali/kimi-k3.1-pro", "cx/gpt-6.1-terra"]
             (:cascadeModels res)))
      (is (not-any? #(= "cx/gpt-5.6-sol" %) (:solExact res))
          "removed sol must resolve to a sol-free fallback, never to itself"))))

(deftest test-bare-alias-names-mean-the-same-product
  (testing "A client that omits the infered/ prefix lands on the same chain, never the default cascade"
    (let [res (run-node-eval
               "import { canonicalModelName, resolveVirtualModel, VIRTUAL_ALIASES } from './src/router/catalog.js';
                console.log(JSON.stringify({
                  autoCanonical: canonicalModelName('auto'),
                  autoBare: resolveVirtualModel('auto'),
                  autoPrefixed: resolveVirtualModel('infered/auto'),
                  glmBare: resolveVirtualModel('glm-budget'),
                  glmPrefixed: resolveVirtualModel('infered/glm-budget'),
                  rawId: canonicalModelName('grok/grok-4.7'),
                  rawResolved: resolveVirtualModel('grok/grok-4.7'),
                  unknown: canonicalModelName('zai/nonexistent'),
                  unknownResolved: resolveVirtualModel('zai/nonexistent'),
                  defaultChain: VIRTUAL_ALIASES['infered/glm-budget']
                }));")]
      (is (= "infered/auto" (:autoCanonical res)))
      (is (= (:autoPrefixed res) (:autoBare res))
          "bare 'auto' must resolve to the measured auto pool, not the default cascade")
      (is (not= (:autoBare res) (:defaultChain res))
          "the auto pool and the default glm-budget cascade are different products")
      (is (= (:glmPrefixed res) (:glmBare res)))
      (is (= "grok/grok-4.7" (:rawId res)) "raw model ids are untouched")
      (is (= ["grok/grok-4.7"] (:rawResolved res)))
      (is (= "zai/nonexistent" (:unknown res)))
      (is (= (:defaultChain res) (:unknownResolved res))
          "an unknown name still behaves exactly like no model at all"))))

(deftest test-official-pricing
  (testing "Official pricing provides baseline for all models including 2026 frontier models"
    (let [res (run-node-eval
               "import { getOfficialPrice, OFFICIAL_PRICES } from './src/router/catalog.js';
                console.log(JSON.stringify({
                  solPrice: getOfficialPrice('cx/gpt-5.6-sol'),
                  aliGlmPrice: getOfficialPrice('ali/glm-5.3'),
                  kimi31Price: getOfficialPrice('ali/kimi-k3.1-pro'),
                  terra61Price: getOfficialPrice('cx/gpt-6.1-terra'),
                  flashRemoved: getOfficialPrice('zai/glm-5.3-flash'),
                  hasPrices: Object.keys(OFFICIAL_PRICES).length >= 4
                }));")]
      (is (:hasPrices res))
      ;; sol removed from catalog: lookup falls to the generic $1/$2 baseline
      (is (= 1 (get-in res [:solPrice :prompt])))
      ;; ali/glm-5.3 official list price verified against the InferHub market
      ;; feed 2026-09-15 (official_in 1.40 / official_out 4.40) — a different
      ;; listing from a retired provider is intentionally not part of the catalog.
      (is (= 1.4 (get-in res [:aliGlmPrice :prompt])))
      (is (= 4.4 (get-in res [:aliGlmPrice :completion])))
      (is (= 2 (get-in res [:kimi31Price :prompt]))
          "kimi-k3.1-pro official list 2/10 per the live feed")
      (is (= 1 (get-in res [:terra61Price :prompt]))
          "gpt-6.1-terra official list 1/5 per the live feed")
      (is (= 1 (get-in res [:flashRemoved :prompt]))
          "excised flash falls to the generic $1/$2 baseline, never a real price"))))

(deftest test-glm-budget-chain
  (testing "glm-budget resolves to the sol-excluded cascade chain and is listed as a virtual model"
    (let [res (run-node-eval
               "import { resolveVirtualModel, VIRTUAL_ALIASES, CASCADE_CHAINS } from './src/router/catalog.js';
                console.log(JSON.stringify({
                  glmBudgetModels: resolveVirtualModel('infered/glm-budget'),
                  isListed: Boolean(VIRTUAL_ALIASES['infered/glm-budget']),
                  cascadeLookup: Boolean(CASCADE_CHAINS['infered/glm-budget']),
                  solBudgetStillReroutes: resolveVirtualModel('infered/sol-budget')
                }));")]
      (is (= ["ali/glm-5.3", "grok/grok-4.7", "ali/kimi-k3.1-pro", "cx/gpt-6.1-terra"]
             (:glmBudgetModels res)))
      (is (not-any? #(= "cx/gpt-5.6-sol" %) (:glmBudgetModels res))
          "glm-budget must never include sol")
      (is (not-any? #(= "zai/glm-5.3-flash" %) (:glmBudgetModels res))
          "flash excised 2026-11-09 (owner directive): never in any chain")
      (is (:isListed res) "must appear in VIRTUAL_ALIASES (drives /v1/models)")
      (is (:cascadeLookup res) "must be a registered cascade chain")
      ;; slimmed 2026-09-05: sol names left the listing but old callers still
      ;; reroute to the glm-budget chain instead of erroring
      (is (= ["ali/glm-5.3", "grok/grok-4.7", "ali/kimi-k3.1-pro", "cx/gpt-6.1-terra"]
             (:solBudgetStillReroutes res))
          "legacy sol-budget callers reroute to the glm-budget chain (flash-free since 2026-11-09)"))))

(deftest test-astra-budget-chain
  (testing "astra-budget resolves to the astra-headed chain; gm5.3 typo must never appear"
    (let [res (run-node-eval
               "import { resolveVirtualModel, VIRTUAL_ALIASES, CASCADE_CHAINS, CEILING_EXEMPT_MODELS } from './src/router/catalog.js';
                console.log(JSON.stringify({
                  astraBudgetModels: resolveVirtualModel('infered/astra-budget'),
                  isListed: Boolean(VIRTUAL_ALIASES['infered/astra-budget']),
                  cascadeLookup: Boolean(CASCADE_CHAINS['infered/astra-budget']),
                  glmBudgetStillListed: Boolean(VIRTUAL_ALIASES['infered/glm-budget']),
                  ceilingExempt: CEILING_EXEMPT_MODELS
                }));")]
      (is (= ["cx/gpt-6-astra", "ali/kimi-k3.1-pro"]
             (:astraBudgetModels res)))
      (is (= ["cx/gpt-6-astra"] (:ceilingExempt res))
          "astra head is price-exempt: no output ceiling can disqualify it")
      (is (not-any? #(= "zai/gm5.3" %) (:astraBudgetModels res))
          "gm5.3 does not exist on the market; the surviving chain must remain flash-free")
      (is (:isListed res) "must appear in VIRTUAL_ALIASES (drives /v1/models)")
      (is (:cascadeLookup res) "must be a registered cascade chain")
      (is (:glmBudgetStillListed res) "must not disturb glm-budget"))))

(deftest test-unknown-names-reroute
  (testing "unknown model names (typos, removed models) resolve to the default budget chain, never the wide pool"
    (let [res (run-node-eval
               "import { resolveVirtualModel } from './src/router/catalog.js';
                console.log(JSON.stringify({
                  gm53Typo: resolveVirtualModel('zai/gm5.3'),
                  rawSol: resolveVirtualModel('cx/gpt-5.6-sol'),
                  removedPinned: resolveVirtualModel('infered/claude-3.5-sonnet'),
                  removedFlash: resolveVirtualModel('zai/glm-5.3-flash'),
                  removedTerra: resolveVirtualModel('cx/gpt-5.6-terra'),
                  removedKimi: resolveVirtualModel('ali/kimi-k3'),
                  chainMemberDirect: resolveVirtualModel('cx/gpt-6.1-terra')
                }));")]
      ;; bind-order note: the default chain is glm-budget's four-model cascade
      (is (= ["ali/glm-5.3" "grok/grok-4.7" "ali/kimi-k3.1-pro" "cx/gpt-6.1-terra"]
             (:gm53Typo res))
          "the famous gm5.3 typo must land on the budget chain")
      (is (not-any? #(= "cx/gpt-5.6-sol" %) (:rawSol res))
          "raw sol resolves to a sol-free chain")
      (is (not-any? #(= "claude-3.5-sonnet" %) (:removedPinned res))
          "removed pinned virtuals reroute to the budget chain")
      (is (= ["cx/gpt-6.1-terra"] (:chainMemberDirect res))
          "current chain members stay directly addressable (they are the chains)")
      (is (= ["ali/glm-5.3" "grok/grok-4.7" "ali/kimi-k3.1-pro" "cx/gpt-6.1-terra"]
             (:removedFlash res))
          "excised flash falls to the default chain (never 403-chases the dead pool)")
      (is (= ["ali/glm-5.3" "grok/grok-4.7" "ali/kimi-k3.1-pro" "cx/gpt-6.1-terra"]
             (:removedTerra res))
          "excised terra-5.6 falls to the default chain")
      (is (= ["ali/glm-5.3" "grok/grok-4.7" "ali/kimi-k3.1-pro" "cx/gpt-6.1-terra"]
             (:removedKimi res))
          "excised kimi-k3 falls to the default chain"))))

(deftest test-three-new-chains
  (testing "astra-terra, terra-kimi, kimi-glm resolve to their exact ordered chains"
    (let [res (run-node-eval
               "import { resolveVirtualModel, CASCADE_CHAINS, VIRTUAL_ALIASES } from './src/router/catalog.js';
                console.log(JSON.stringify({
                  astraTerra: resolveVirtualModel('infered/astra-terra'),
                  terraKimi: resolveVirtualModel('infered/terra-kimi'),
                  kimiGlm: resolveVirtualModel('infered/kimi-glm'),
                  registered: ['infered/astra-terra','infered/terra-kimi','infered/kimi-glm'].every(k => Boolean(CASCADE_CHAINS[k] && VIRTUAL_ALIASES[k]))
                }));")]
      (is (= ["cx/gpt-6-astra" "cx/gpt-6.1-terra" "ali/kimi-k3.1-pro"]
             (:astraTerra res)))
      (is (= ["cx/gpt-6.1-terra" "ali/kimi-k3.1-pro"]
             (:terraKimi res)))
      (is (= ["ali/kimi-k3.1-pro"]
             (:kimiGlm res)) "kimi-glm keeps the remaining reasoner as its sole member")
      (is (:registered res) "all three registered in CASCADE_CHAINS and VIRTUAL_ALIASES"))))

(deftest test-strong-links
  (testing "each chain names a designated strong link for X-Infered-Tier: strong; unknown names fall to the default chain's"
    (let [res (run-node-eval
               "import { resolveStrongLink } from './src/router/catalog.js';
                console.log(JSON.stringify({
                  glmBudget: resolveStrongLink('infered/glm-budget'),
                  astraBudget: resolveStrongLink('infered/astra-budget'),
                  astraTerra: resolveStrongLink('infered/astra-terra'),
                  terraKimi: resolveStrongLink('infered/terra-kimi'),
                  kimiGlm: resolveStrongLink('infered/kimi-glm'),
                  unknownName: resolveStrongLink('zai/gm5.3'),
                  empty: resolveStrongLink('')
                }));")]
      (is (= "ali/kimi-k3.1-pro" (:glmBudget res)) "the pro reasoner carries glm-budget's strong link")
      (is (= "cx/gpt-6-astra" (:astraBudget res)) "the premium head IS astra-budget's strong link")
      (is (= "cx/gpt-6-astra" (:astraTerra res)))
      (is (= "ali/kimi-k3.1-pro" (:terraKimi res)))
      (is (= "ali/kimi-k3.1-pro" (:kimiGlm res)) "kimi-glm uses its sole remaining member")
      (is (= "ali/kimi-k3.1-pro" (:unknownName res)) "unrecognized names inherit the default chain's strong link")
      (is (= "ali/kimi-k3.1-pro" (:empty res))))))

(deftest test-retired-model-metadata
  (testing "retired raw names have no catalog metadata and reroute to the default chain"
    (let [res (run-node-eval
               "import { resolveVirtualModel, MODEL_TIERS, OFFICIAL_PRICES, getOfficialPrice, getModelMetadata } from './src/router/catalog.js';
                const retired = ['ali/qwen3.8-max-' + '0902', 'ag/claude-sonnet-' + '4-6', 'zai/' + 'glm-5.3'];
                console.log(JSON.stringify(retired.map(id => ({
                  hasTier: Boolean(MODEL_TIERS[id]), hasPrice: Boolean(OFFICIAL_PRICES[id]),
                  resolved: resolveVirtualModel(id), price: getOfficialPrice(id), tier: getModelMetadata(id)
                }))));")]
      (is (every? #(not (:hasTier %)) res))
      (is (every? #(not (:hasPrice %)) res))
      (is (every? #(= ["ali/glm-5.3" "grok/grok-4.7" "ali/kimi-k3.1-pro" "cx/gpt-6.1-terra"] (:resolved %)) res))
      (is (every? #(= {:prompt 1 :completion 2} (:price %)) res))
      (is (every? #(= "general" (get-in % [:tier :tier])) res)))))

(deftest test-solterra-chain
  (testing "solterra resolves to the exact oai Sol then Terra cascade with verified prices and strong head"
    (let [res (run-node-eval
               "import { resolveVirtualModel, resolveStrongLink, OFFICIAL_PRICES, MODEL_TIERS, CASCADE_CHAINS } from './src/router/catalog.js';
                console.log(JSON.stringify({
                  resolved: resolveVirtualModel('infered/solterra'),
                  strong: resolveStrongLink('infered/solterra'),
                  registered: Boolean(CASCADE_CHAINS['infered/solterra']),
                  solRaw: resolveVirtualModel('oai/gpt-6.1-sol'),
                  terraRaw: resolveVirtualModel('oai/gpt-6.1-terra'),
                  solPrice: OFFICIAL_PRICES['oai/gpt-6.1-sol'],
                  terraPrice: OFFICIAL_PRICES['oai/gpt-6.1-terra'],
                  solContext: MODEL_TIERS['oai/gpt-6.1-sol']?.context,
                  terraContext: MODEL_TIERS['oai/gpt-6.1-terra']?.context
                }));")]
      (is (= ["oai/gpt-6.1-sol" "oai/gpt-6.1-terra"] (:resolved res)))
      (is (= "oai/gpt-6.1-sol" (:strong res)))
      (is (:registered res))
      (is (= ["oai/gpt-6.1-sol"] (:solRaw res)))
      (is (= ["oai/gpt-6.1-terra"] (:terraRaw res)))
      (is (= {:prompt 2 :completion 10} (:solPrice res)))
      (is (= {:prompt 1 :completion 5} (:terraPrice res)))
      (is (= 1050000 (:solContext res)))
      (is (= 1050000 (:terraContext res))))))

(deftest test-grok-and-retired-sonnet
  (testing "grok remains wired while the retired Sonnet entry stays absent"
    (let [res (run-node-eval
               "import { resolveVirtualModel, OFFICIAL_PRICES } from './src/router/catalog.js';
                import { AUTO_CHAIN } from './src/router/auto.js';
                console.log(JSON.stringify({
                  glmBudget: resolveVirtualModel('infered/glm-budget'),
                  grokPrice: OFFICIAL_PRICES['grok/grok-4.7'],
                  sonnetPrice: OFFICIAL_PRICES['ag/claude-sonnet-' + '4-6'],
                  poolIds: AUTO_CHAIN
                }));")]
      (is (some #{"grok/grok-4.7"} (:glmBudget res))
          "grok-4.7 is a glm-budget chain member (owner directive 2026-11-11)")
      (is (= 4 (count (:glmBudget res))) "glm-budget is the surviving four-model cascade")
      (is (= {:prompt 2 :completion 6} (:grokPrice res))
          "grok official list 2/6 per the live feed")
      (is (nil? (:sonnetPrice res))
          "retired sonnet has no official price entry")
      (is (= ["cx/gpt-6.2-astra" "cx/gpt-6.2-sol" "ali/qwen4-max"] (:poolIds res))
          "the auto chain is the owner-declared three, in the declared sequence")
      (is (not-any? #(= (str "ag/" "4-6") %) (:poolIds res)) "retired sonnet is absent from the auto chain")
      (is (not-any? #{"grok/grok-4.7"} (:poolIds res))
          "grok left the auto chain when the owner declared the three (2026-11-21)")
      (is (not-any? #{"ag/gemini-4-argon"} (:poolIds res))
          "gemini-4-argon stays OUT of the pool (owner directive 2026-11-11: do not add argon)"))))
