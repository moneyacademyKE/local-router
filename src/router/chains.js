/** A market chain — plain data. One hourly snapshot of live marketplace
 * asks, served under ONE output bid per attempt. The mechanism that
 * snapshots, serves and diagnoses a chain lives in auto.js; this file only
 * says WHICH models, at WHAT bid, in WHOSE order. The chain is an argument of
 * that mechanism, not a constant inside it.
 *
 *   infered/auto — membership and sequence DECLARED by the owner
 *                  (2026-11-21). Measured health annotates each member
 *                  and never reorders it. */

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
// sequence.
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
  }
};
