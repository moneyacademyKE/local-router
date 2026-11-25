/** The market chains — plain data. A market chain is one hourly snapshot of
 * live marketplace asks, served under ONE output bid per attempt. The
 * mechanism that snapshots, serves and diagnoses a chain lives in auto.js;
 * this file only says WHICH models, at WHAT bid, and in WHOSE order.
 *
 * Two chains exist:
 *   infered/auto         — membership and sequence DECLARED by the owner
 *                          (2026-11-21). Measured health annotates each member
 *                          and never reorders it.
 *   infered/auto-budget  — a SCORED POOL searched for the highest
 *                          intelligence per dollar (owner directive
 *                          2026-11-25). The served order is measured, not
 *                          declared: valueScore = intelligence x reliability
 *                          x speed / effectiveBlended (see ./health.js). */

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
// Intelligence scores are the Artificial Analysis snapshot from 2026-11-10,
// not live scores — the recorded values the auto chain ranked by before it
// became owner-declared on 2026-11-21. A model with no score on record is NOT
// in the pool: an intelligence-per-dollar search cannot rank intelligence it
// does not know, and an invented score would be a belief wearing a number.
// Admitting a model is recording its score here; that is the whole change.
// Argon stays out (owner directive 2026-11-11).
export const AUTO_BUDGET_POOL = [
  { id: "ag/gemini-4-carbon", score: 60 },
  { id: "cx/gpt-6-astra", score: 57 },
  { id: "oai/gpt-6.1-sol", score: 55 },
  { id: "cx/gpt-6.1-terra", score: 51 },
  { id: "oai/gpt-6.1-terra", score: 51 },
  { id: "ali/kimi-k3.1-pro", score: 46 },
  { id: "grok/grok-4.7", score: 35.5 },
  { id: "ali/glm-5.3", score: 32 }
];

/** Everything the mechanism needs to serve one chain. Each chain keeps its own
 * D1 rows (`rowId` for the hourly snapshot, `quotaRowId` for the measured
 * seller quota), so one chain's book can never leak into the other's.
 * `order`: "declared" tries the members in the listed sequence (measured health
 * annotates, never reorders); "value" re-sorts them by measured value at serve
 * time. */
export const MARKET_CHAINS = {
  [AUTO_MODEL]: {
    model: AUTO_MODEL, rowId: "auto", quotaRowId: "auto-quota", bid: AUTO_OUTPUT_BID,
    members: AUTO_CHAIN.map(id => ({ id })), order: "declared",
    orderSource: "owner-declared", noun: "declared member"
  },
  [AUTO_BUDGET_MODEL]: {
    model: AUTO_BUDGET_MODEL, rowId: "auto-budget", quotaRowId: "auto-budget-quota",
    bid: AUTO_BUDGET_OUTPUT_BID, members: AUTO_BUDGET_POOL, order: "value",
    orderSource: "measured-value", noun: "pool member"
  }
};
