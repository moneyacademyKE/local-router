/**
 * Chain-exhaustion verdict — one place that decides what a dead chain MEANS.
 *
 * Both executors (streaming splice and non-streaming fallback) end the same
 * three ways, and they used to describe them differently: the stream path
 * answered 503 "capacity exhausted" even when every attempt had merely timed
 * out, so a retry-happy client was told "the book is dry, come back later"
 * for a problem time alone would not fix. The status is part of the
 * measurement: mislabelling it feeds the wrong bucket in every downstream
 * count, and the ledger is the only thing anyone can audit later.
 *
 * Pure data in, pure data out — no Response, no metrics, no clock reads.
 *
 *   status 504 — the chain ran out of TIME (all timeouts, or the silence
 *                budget that bounds the silent chase)
 *   status 503 — the chain ran out of CANDIDATES (capacity, retry budget
 *                spent) or the account refuses the model (4xx)
 */

const TIMEOUT_STATUS = 504;
const UNAVAILABLE_STATUS = 503;

export function classifyExhaustion({
  errors = [],
  attempts = 0,
  elapsedMs = 0,
  budgetCut = false,
  nonRetryableOpen = false,
  maxAttempts = 0
}) {
  const timedOutOnly = errors.length > 0 && errors.every((e) => e.status === TIMEOUT_STATUS);

  if (nonRetryableOpen) {
    const lastStatus = errors[errors.length - 1]?.status || "4xx";
    return {
      status: UNAVAILABLE_STATUS,
      message: `Non-retryable upstream rejection (${lastStatus}): the account refuses this model — every node answers the same 4xx.`,
      reason: "non_retryable_candidate_open"
    };
  }
  if (budgetCut) {
    return {
      status: TIMEOUT_STATUS,
      message: `Pre-commit budget exhausted after ${attempts} attempts in ${elapsedMs}ms of client-facing silence.`,
      reason: "pre_commit_budget_exhausted"
    };
  }
  if (timedOutOnly) {
    return {
      status: TIMEOUT_STATUS,
      message: `Upstream timed out before any content after ${attempts} attempts.`,
      reason: "upstream_timeout"
    };
  }
  if (maxAttempts > 0 && errors.length >= maxAttempts) {
    return {
      status: UNAVAILABLE_STATUS,
      message: `Retry budget exhausted after ${errors.length} upstream attempts.`,
      reason: "retry_budget_exhausted"
    };
  }
  return {
    status: UNAVAILABLE_STATUS,
    message: "All candidate nodes failed or capacity exhausted.",
    reason: "all_candidates_exhausted"
  };
}
