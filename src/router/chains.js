/** The market chains — plain data. A market chain is one snapshot of live
 * marketplace asks, served under ONE output bid per attempt. The mechanism
 * that snapshots, serves and diagnoses a chain lives in auto.js; this file
 * only says WHAT each chain fixes.
 *
 * Two chains exist, and both discover their members (owner directive
 * 2026-11-27 16:42 EAT, extending the 2026-11-25 auto-budget directive to
 * auto): membership is whatever the live feed offers inside the chain's
 * parameters, re-read every MARKET_READ_TTL_MS, and the served order is
 * measured, not declared — valueScore = intelligence x reliability x speed /
 * effectiveBlended (see ./health.js). The chains differ only in the bid:
 *   infered/auto         — $1.00/M output ceiling (owner 2026-11-18).
 *   infered/auto-budget  — $0.50/M output ceiling (owner 2026-11-25). */

// ---- infered/auto ----------------------------------------------------------

// Output-price ceiling per attempt (owner directive 2026-11-18). The previous
// fence sat one millionth of a dollar below cx/gpt-6-astra's only ask ($1.00),
// so a rounding artifact — not a spending decision — collapsed the chain to
// a single member. The ceiling is now exactly $1.00/M output; anything above
// stays out.
export const AUTO_MODEL = "infered/auto";
export const AUTO_OUTPUT_BID = 1;

// History, for the record: auto was DECLARED 2026-11-21 (astra/sol/qwen4-max;
// astra removed and glm-5.4 appended 2026-11-24) precisely so the owner could
// read the chain he was buying. The 2026-11-27 directive reverses that on
// purpose: the owner now reads what the market offers under $1.00, ranked by
// measured value. The declared sequence is gone; its members are not retired —
// raw requests for them still resolve (./catalog.js MODEL_TIERS), and each
// returns to the chain the moment the market and the board admit it.

// How long one snapshot is served before the live feed is read again. An hour
// was right while membership was a list; membership is now whatever the market
// offers inside the bid (owner directive 2026-11-27: the chains must keep
// changing to meet their parameters), so it is re-read often. The per-attempt
// bid still enforces the ceiling between two reads.
export const MARKET_READ_TTL_MS = 5 * 60 * 1000;
// Families the owner keeps out of every market chain, by family key. Argon
// stays out (owner directive 2026-11-11).
export const OWNER_EXCLUDED_FAMILIES = ["gemini4argon"];

// ---- infered/auto-budget ---------------------------------------------------

export const AUTO_BUDGET_MODEL = "infered/auto-budget";
// "under $0.5 output" (owner directive 2026-11-25). Hard for every member: the
// CEILING_EXEMPT_MODELS escape belongs to the astra-headed cascade products,
// and the owner named this ceiling explicitly. An ask exactly at $0.50 is
// inside it — the same on-the-ceiling rule the $1.00 bid follows.
export const AUTO_BUDGET_OUTPUT_BID = 0.5;
// Intelligence scores live in ./intelligence.js: a published index read
// live, with the scores this repo recorded on 2026-11-10/11 behind it. A model
// on neither is NOT a candidate — an intelligence-per-dollar search cannot
// rank intelligence it does not know, and an invented score would be a belief
// wearing a number. The chain's diagnostics name every such model it finds.

/** A lookup table with no inherited keys. These tables are keyed by names that
 * arrive in a request body, and a plain object literal answers "constructor"
 * and "__proto__" with Object.prototype's own members — truthy, and not
 * products. Measured 2026-11-25: `model: "constructor"` was read as a known
 * chain and crashed the worker (500) instead of falling to the default chain
 * like any other unknown name. */
export const TABLE = (entries) => Object.assign(Object.create(null), entries);

/** Everything the mechanism needs to serve one chain. Each chain keeps its own
 * D1 rows (`rowId` for the hourly snapshot, `quotaRowId` for the measured
 * seller quota), so one chain's book can never leak into the other's.
 * `order`: "declared" tries the members in the listed sequence (measured health
 * annotates, never reorders); "value" re-sorts them by measured value at serve
 * time. A chain with `discover` has no `members`: its candidates are whatever
 * the live feed offers inside its parameters (see `chainParameters`). */
export const MARKET_CHAINS = TABLE({
  [AUTO_MODEL]: {
    model: AUTO_MODEL, rowId: "auto", quotaRowId: "auto-quota", bid: AUTO_OUTPUT_BID,
    order: "value", orderSource: "measured-value", noun: "candidate",
    // No `members` (owner directive 2026-11-27): the chain's candidates are
    // discovered on every snapshot, under the $1.00 bid.
    discover: true, ttlMs: MARKET_READ_TTL_MS, exclude: OWNER_EXCLUDED_FAMILIES
  },
  [AUTO_BUDGET_MODEL]: {
    model: AUTO_BUDGET_MODEL, rowId: "auto-budget", quotaRowId: "auto-budget-quota",
    bid: AUTO_BUDGET_OUTPUT_BID, order: "value",
    orderSource: "measured-value", noun: "candidate",
    // No `members`: the chain's candidates are discovered on every snapshot.
    discover: true, ttlMs: MARKET_READ_TTL_MS, exclude: OWNER_EXCLUDED_FAMILIES
  }
});

/** What a discovered chain fixes. Membership is deliberately not among these:
 * it is whatever the live market offers inside them, so the chain changes as
 * the market does. Stated in the chain's inspect payload, where a declared
 * chain instead states its member list. */
export function chainParameters(chain) {
  if (!chain?.discover) return null;
  return { maxOutputPrice: chain.bid, order: chain.orderSource,
    refreshMs: chain.ttlMs, exclude: [...chain.exclude] };
}
