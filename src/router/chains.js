/** The market chains — plain data. A market chain is one hourly snapshot of
 * live marketplace asks, served under ONE output bid per attempt. The
 * mechanism that snapshots, serves and diagnoses a chain lives in auto.js;
 * this file only says WHICH models, at WHAT bid, and in WHOSE order.
 *
 * Two chains exist:
 *   infered/auto         — membership and sequence DECLARED by the owner
 *                          (2026-11-21). Measured health annotates each member
 *                          and never reorders it.
 *   infered/auto-budget  — PARAMETERS, not a list (owner directive
 *                          2026-11-27): the highest intelligence per dollar
 *                          the market offers under the output bid. Membership
 *                          is discovered from the live feed on every snapshot
 *                          (./auto.js buildAutoSnapshot), and the served order
 *                          is measured, not declared: valueScore =
 *                          intelligence x reliability x speed /
 *                          effectiveBlended (see ./health.js). */

// ---- infered/auto ----------------------------------------------------------

// Output-price ceiling per attempt (owner directive 2026-11-18). The previous
// fence sat one millionth of a dollar below cx/gpt-6-astra's only ask ($1.00),
// so a rounding artifact — not a spending decision — collapsed the chain to
// a single member. The ceiling is now exactly $1.00/M output; anything above
// stays out.
export const AUTO_MODEL = "infered/auto";
export const AUTO_OUTPUT_BID = 1;

// The auto chain is DECLARED by the owner, in order (owner directive
// 2026-11-21), amended by two owner directives of 2026-11-24: astra 6.2
// REMOVED, zai/glm-5.4 appended last. These three are attempted in this
// sequence. The previous pool was a set of Artificial Analysis scores that the
// value ranking then re-sorted at serve time, which meant the owner could not
// read the chain he was buying — the order was an inference. A declared order
// is a decision; the ranking machinery now only ANNOTATES each member
// (reliability, speed, value tier), it no longer decides membership or
// sequence. That value-ranked pool returns, on purpose, as infered/auto-budget
// below: auto is the order the owner reads, auto-budget is the order the
// router finds.
//
// A member whose cheapest ask sits above the bid is excluded with reason
// `over_cap` and named in diagnostics — silently buying outside the ceiling is
// the one thing an owner order must never do. Live failure still outranks the
// order: pareto.js pushes a circuit-tripped member to the back, so a head that
// is actively failing is skipped rather than retried in the owner's name.
//
// Feed checks (sellers whose output ask sits at or under the $1.00 bid):
// 2026-11-21 — sol 6.2 from $0.19 (9); qwen4-max from $0.27 (3).
// 2026-11-25 00:02 EAT — glm-5.4 from $0.0044 (5 of 7 asks); sol 6.2 then
// asked $2.50 on every seller, i.e. over_cap until its book comes back down.
export const AUTO_CHAIN = [
  "cx/gpt-6.2-sol",
  "ali/qwen4-max",
  // Tail member, appended by owner directive 2026-11-24. The $1.00/M output
  // bid adjudicates it like any other member — an over-cap ask names it
  // over_cap in diagnostics rather than buying silently.
  "zai/glm-5.4"
];

// ---- infered/auto-budget ---------------------------------------------------

export const AUTO_BUDGET_MODEL = "infered/auto-budget";
// "under $0.5 output" (owner directive 2026-11-25). Hard for every member: the
// CEILING_EXEMPT_MODELS escape belongs to the astra-headed cascade products,
// and the owner named this ceiling explicitly. An ask exactly at $0.50 is
// inside it — the same on-the-ceiling rule the $1.00 bid follows.
export const AUTO_BUDGET_OUTPUT_BID = 0.5;
// How long one snapshot of this chain is served before the live feed is read
// again. An hour was right while membership was a list; membership is now
// whatever the market offers inside the bid (owner directive 2026-11-27: the
// chain must keep changing to meet its parameters), so it is re-read often.
// The per-attempt bid still enforces the ceiling between two reads.
export const AUTO_BUDGET_TTL_MS = 5 * 60 * 1000;
// Families the owner keeps out of the chain, by family key. Argon stays out
// (owner directive 2026-11-11).
export const AUTO_BUDGET_EXCLUDE = ["gemini4argon"];
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
    members: AUTO_CHAIN.map(id => ({ id })), order: "declared",
    orderSource: "owner-declared", noun: "declared member"
  },
  [AUTO_BUDGET_MODEL]: {
    model: AUTO_BUDGET_MODEL, rowId: "auto-budget", quotaRowId: "auto-budget-quota",
    bid: AUTO_BUDGET_OUTPUT_BID, order: "value",
    orderSource: "measured-value", noun: "candidate",
    // No `members`: the chain's candidates are discovered on every snapshot.
    discover: true, ttlMs: AUTO_BUDGET_TTL_MS, exclude: AUTO_BUDGET_EXCLUDE
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
