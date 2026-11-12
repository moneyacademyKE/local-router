/**
 * Usage economics — token accounting derived from the serving candidate.
 *
 * Extracted from stream.js (2026-11-12) at the ceiling the audit predicted:
 * cost derivation is a pricing concern, not a relay concern. Two jobs:
 *  - streamUsage: spot & official cost for a token pair on a candidate
 *  - finalizeUsage: normalize a raw upstream usage frame (trailing SSE frame
 *    or usage embedded in an adapted JSON chunk) into the ledger shape,
 *    preferring the seller-reported cost when one exists.
 */

import { getOfficialPrice } from "./catalog.js";

const USAGE_NEEDLE = '"usage":';

export { USAGE_NEEDLE };

export function streamUsage(candidate, promptTokens, completionTokens) {
  const official = getOfficialPrice(candidate.modelId);
  const spot = candidate.quote || { prompt: candidate.blendedPrice, completion: candidate.blendedPrice };
  return {
    costUsd: Number((((promptTokens / 1e6) * (spot.prompt || 0.1)) +
                     ((completionTokens / 1e6) * (spot.completion || 0.1))).toFixed(7)),
    officialCostUsd: Number((((promptTokens / 1e6) * official.prompt) +
                             ((completionTokens / 1e6) * official.completion)).toFixed(7))
  };
}

/**
 * Ledger usage view: the raw upstream usage object normalized to the D1
 * record shape (promptTokens / completionTokens / cachedTokens / costUsd).
 * Cost prefers the upstream-reported number — sellers bill what they bill —
 * and falls back to a spot-price computation from the serving candidate's
 * quotes. Accepts both OpenAI shapes.
 */
export function finalizeUsage(u, candidate) {
  if (!u || typeof u !== "object") return null;
  const promptTokens = Number.isFinite(u.prompt_tokens) ? u.prompt_tokens : null;
  const completionTokens = Number.isFinite(u.completion_tokens) ? u.completion_tokens : null;
  const details = (u.prompt_tokens_details && typeof u.prompt_tokens_details === "object") ? u.prompt_tokens_details : {};
  const cachedTokens = Number.isFinite(details.cached_tokens) ? details.cached_tokens : null;
  let costUsd = Number.isFinite(u.cost) ? u.cost : null;
  if (costUsd === null && candidate && promptTokens !== null && completionTokens !== null) {
    costUsd = streamUsage(candidate, promptTokens, completionTokens).costUsd;
  }
  return { promptTokens, completionTokens, cachedTokens, costUsd };
}
