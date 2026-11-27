/**
 * Model Catalog & Virtual Tier Mappings
 * Pure data structures and resolution logic for Infered router.
 */

import { AUTO_CHAIN, TABLE } from "./chains.js";

// Model capabilities and quality benchmarks (normalized 0.0 - 1.0).
// Slimmed 2026-09-05 (owner directive): chain members plus owner-designated
// raw models are catalogued — the router sells the cascade chains, not a
// model zoo. MODEL_TIERS presence is what makes a raw request resolve to
// the model itself; removed names reroute to the default budget chain.
// (qwen and other retired names are intentionally absent from the catalog).
export const MODEL_TIERS = TABLE({
  "cx/gpt-6.1-terra": { tier: "balanced-frontier", quality: 0.91, family: "openai", context: 500000 },
  "oai/gpt-6.1-sol": { tier: "frontier-flagship", quality: 0.96, family: "openai", context: 1050000 },
  "oai/gpt-6.1-terra": { tier: "balanced-frontier", quality: 0.91, family: "openai", context: 1050000 },
  "ali/kimi-k3.1-pro": { tier: "moe-frontier", quality: 0.94, family: "moonshot", context: 1000000 },
  // Market-gap wiring 2026-11-11 (owner directive; argon excluded): grok-4.7
  // (AA 35.5, 8 in-cap sellers at $0.006/M out, 500K ctx) and the
  "grok/grok-4.7": { tier: "balanced-frontier", quality: 0.93, family: "xai", context: 500000 },
  // Market's top score (AA 60.0) under the seller-quota rule 2026-11-11: the
  // feed gives it 1M context and a two-ask book ($0.01/$0.50 out) that
  // saturates hard — measured quota health demotes it while sellers are out.
  "ag/gemini-4-carbon": { tier: "frontier-flagship", quality: 0.97, family: "google", context: 1048576 },
  // Owner-declared auto chain 2026-11-21. Context windows are the live feed's
  // input_token_limit (272K / 500K / 1M). Quality is the router's unknown-model
  // default, NOT a measurement: no intelligence snapshot exists for the 6.2
  // generation yet, and the auto chain no longer ranks by quality — membership
  // and order are declared, so inventing scores here would only feed a report a
  // belief. These entries exist so a RAW request for these ids resolves to
  // itself instead of silently rerouting to the default budget chain — which
  // is also why astra 6.2 keeps its entry after leaving the auto chain
  // (owner directive 2026-11-24): removal from a chain is not retirement.
  "cx/gpt-6.2-astra": { tier: "frontier-flagship", quality: 0.70, family: "openai", context: 272000, unmeasuredQuality: true },
  "cx/gpt-6.2-sol": { tier: "frontier-flagship", quality: 0.70, family: "openai", context: 500000, unmeasuredQuality: true },
  "ali/qwen4-max": { tier: "moe-frontier", quality: 0.70, family: "qwen", context: 1000000, unmeasuredQuality: true }
});

// Official provider list prices in USD per 1M tokens [Prompt, Completion]
export const OFFICIAL_PRICES = {
  "cx/gpt-6-astra": { prompt: 10, completion: 50 },
  // ali/glm-5.3 list price per the InferHub market feed (official_in/out,
  // verified 2026-09-15).
  "ali/glm-5.3": { prompt: 1.40, completion: 4.40 },
  "cx/gpt-6.1-terra": { prompt: 1, completion: 5 },
  "oai/gpt-6.1-sol": { prompt: 2, completion: 10 },
  "oai/gpt-6.1-terra": { prompt: 1, completion: 5 },
  // Refreshed 2026-11-09 (owner directive): flash/terra-5.6/kimi-k3 excised;
  // 6.1-terra + kimi-k3.1-pro list prices verified against the live feed.
  "ali/kimi-k3.1-pro": { prompt: 2, completion: 10 },
  // Market-gap wiring 2026-11-11 (owner directive; argon excluded). List
  // prices per the live feed (official_in/out, verified this turn).
  "grok/grok-4.7": { prompt: 2, completion: 6 },
  // Carbon joined the auto pool 2026-11-11 (owner directive). List price per
  // the live feed (official_in/out, verified this turn).
  "ag/gemini-4-carbon": { prompt: 2, completion: 10 },
  // Owner-declared auto chain 2026-11-21: official_in/out straight from the
  // live market feed (8/40, 2/10, 1.5/4.5 per 1M). These are LIST prices, used
  // for savings comparison only — the spot asks in the hourly snapshot decide
  // what is actually buyable inside the $1.00 output bid.
  "cx/gpt-6.2-astra": { prompt: 8, completion: 40 },
  "cx/gpt-6.2-sol": { prompt: 2, completion: 10 },
  "ali/qwen4-max": { prompt: 1.5, completion: 4.5 }
};

// Budget cascade ordering: zai/glm-5.4 leads (owner directive 2026-11-24),
// then glm-5.3, grok, kimi, and terra. The head is skipped, not fatal, when
// no seller quotes it yet (eligibility skips unquoted models).
export const GLM_BUDGET_FALLBACK_CHAIN = [
  "zai/glm-5.4",
  "ali/glm-5.3",
  "grok/grok-4.7",
  "ali/kimi-k3.1-pro",
  "cx/gpt-6.1-terra"
];

// Astra-headed budget cascade: the head activates automatically once
// cx/gpt-6-astra lists live spot asks on InferHub (eligibility skips
// unquoted models, so kimi-3.1-pro carries traffic until then). The head is
// price-exempt (see CEILING_EXEMPT_MODELS): its ask can never disqualify it.
export const ASTRA_BUDGET_FALLBACK_CHAIN = [
  "cx/gpt-6-astra",
  "ali/kimi-k3.1-pro"
];

// Models exempt from the output-price CEILING: any ask price is acceptable.
// Verified-spot-ask still required (no ask = no route, never the $2 placeholder).
// Owner directive 2026-09-06: the astra head must never be price-disqualified.
export const CEILING_EXEMPT_MODELS = ["cx/gpt-6-astra"];

// Three ordered chains (owner directive 2026-09-08): astra-terra, terra-kimi,
// kimi-glm. Same budget-cascade mechanics as the -budget products.
export const ASTRA_TERRA_CHAIN = [
  "cx/gpt-6-astra",
  "cx/gpt-6.1-terra",
  "ali/kimi-k3.1-pro"
];

export const TERRA_KIMI_CHAIN = [
  "cx/gpt-6.1-terra",
  "ali/kimi-k3.1-pro"
];

// kimi-glm rides the pro reasoner as its sole remaining member.

export const KIMI_GLM_CHAIN = [
  "ali/kimi-k3.1-pro"
];

// Explicit two-model OpenAI cascade (owner directive 2026-11-09). Sol leads;
// Terra is the second link, with no implicit retired tail.
export const SOLTERRA_CHAIN = [
  "oai/gpt-6.1-sol",
  "oai/gpt-6.1-terra"
];

// The product list, and the single source of truth for what a model name
// means: the router recognises a request by lookup here, never by string
// comparison. Eight products — six ordered budget cascades (2026-11-09) and
// the two market chains, which list membership only. Every other name a
// client sends reroutes to the default budget chain.
export const CASCADE_CHAINS = TABLE({
  "infered/glm-budget": GLM_BUDGET_FALLBACK_CHAIN,
  "infered/astra-budget": ASTRA_BUDGET_FALLBACK_CHAIN,
  "infered/astra-terra": ASTRA_TERRA_CHAIN,
  "infered/terra-kimi": TERRA_KIMI_CHAIN,
  "infered/kimi-glm": KIMI_GLM_CHAIN,
  "infered/solterra": SOLTERRA_CHAIN,
  // Declared membership in declared order (owner directive 2026-11-21); the
  // hourly market snapshot supplies each member's live asks.
  "infered/auto": [...AUTO_CHAIN],
  // A discovered chain (owner directive 2026-11-25): no list. The entry
  // exists so the NAME resolves to this product; its candidates are whatever
  // the live market offers inside its parameters, read from its snapshot.
  "infered/auto-budget": []
});

// Default requested model — also where UNRECOGNIZED names land (typos like
// "zai/gm5.3", removed models like raw sol). Unknown must mean "budget-safe
// default chain", never the wide MODEL_TIERS pool where any quoted model can
// win. Matches worker.js's no-model default.
export const DEFAULT_MODEL = "infered/glm-budget";

// Designated strong link per chain — where X-Infered-Tier: strong starts the
// cascade. Chains are ECONOMIC orderings (head = preferred under spot prices),
// so the strong link is an explicit capability pick, not a position guess.
// Kimi carries the remaining reasoner link; Astra heads its premium chains.
export const CHAIN_STRONG_LINKS = TABLE({
  "infered/glm-budget": "ali/kimi-k3.1-pro",
  "infered/astra-budget": "cx/gpt-6-astra",
  "infered/astra-terra": "cx/gpt-6-astra",
  "infered/terra-kimi": "ali/kimi-k3.1-pro",
  "infered/kimi-glm": "ali/kimi-k3.1-pro",
  "infered/solterra": "oai/gpt-6.1-sol"
});

// Mirrors the cascade resolution rule (CASCADE_CHAINS hit, else default chain)
// so tier:strong on a typo'd name still lands on the default chain's muscle.
export function resolveStrongLink(model) {
  const clean = (model || "").trim().toLowerCase();
  const name = CASCADE_CHAINS[clean] ? clean : DEFAULT_MODEL;
  return CHAIN_STRONG_LINKS[name] || null;
}

// Declared sibling-chain fallback: when a chain prices out entirely or every
// candidate fails upstream, the executor may retry exactly once on this chain.
// glm-budget is terminal (no entry — no fallback, no loop).
export const CHAIN_FALLBACKS = TABLE({
  "infered/astra-budget": "infered/glm-budget"
});

// Virtual aliases mapping to candidate model sets — the two chains ARE the
// whole surface. Anything unlisted (raw members excepted) lands on the
// default budget chain via resolveVirtualModel.
export const VIRTUAL_ALIASES = TABLE({
  ...CASCADE_CHAINS
});

/**
 * Canonicalises a requested model name. The `infered/` prefix is a namespace,
 * not part of the product: `auto` and `infered/auto` are the same chain, and a
 * client that omits the prefix must not silently land on the default cascade.
 * (Measured 2026-11-19: `model: "auto"` fell through to the default glm-budget
 * chain and was served by grok at a $1.20 ask the auto pool excludes.)
 * Raw model ids and unknown names come back unchanged — lowercased only — so
 * their existing resolution path is untouched.
 */
export function canonicalModelName(modelName) {
  const clean = (modelName || "").trim().toLowerCase();
  if (!clean) return "";
  if (VIRTUAL_ALIASES[clean] || MODEL_TIERS[clean]) return clean;
  const prefixed = `infered/${clean}`;
  return VIRTUAL_ALIASES[prefixed] ? prefixed : clean;
}

/**
 * Resolves a requested model name to a list of candidate model IDs.
 */
export function resolveVirtualModel(modelName) {
  const name = canonicalModelName(modelName);
  if (!name) return VIRTUAL_ALIASES[DEFAULT_MODEL];

  if (VIRTUAL_ALIASES[name]) {
    return VIRTUAL_ALIASES[name];
  }

  const unaliased = name.replace(/^infered\//, "");
  if (MODEL_TIERS[unaliased]) {
    return [unaliased];
  }
  if (MODEL_TIERS[name]) {
    return [name];
  }

  // Unknown names resolve to the default budget chain, not the auto pool —
  // an unrecognized model behaves exactly like no model at all.
  return VIRTUAL_ALIASES[DEFAULT_MODEL];
}

export function getOfficialPrice(modelId) {
  return OFFICIAL_PRICES[modelId] || { prompt: 1.00, completion: 2.00 };
}

export function getModelMetadata(modelId) {
  return MODEL_TIERS[modelId] || { tier: "general", quality: 0.70, family: "unknown", context: 32000 };
}
