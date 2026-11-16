# Local runtime hardening witness

**Time:** 2026-11-17 02:50 EAT
**Verdict:** PARTIAL — implementation and bounded checks passed; the 72-hour soak and completion-failure investigation remain open.

## What changed

- Wrangler `4.163.0` runs from `~/Projects/infered-local`, outside Desktop, with explicit persistent D1 state.
- A launchd-triggered Babashka watchdog checks health every minute and caps automatic restart attempts.
- Routing failures without a served model are stored as `unserved`; dashboard failure totals use `ok=0` and exclude that sentinel from model-switch statistics.
- The existing local D1 state was copied during a controlled stop. The original remains untouched; 3,231 decision rows were verified at cutover.

## Evidence

- `bb test-all`: 23 namespaces, zero failures or errors. `bb lint`, startup shell syntax and `git diff --check` passed.
- Local health, auto and model-list endpoints returned HTTP 200. A streamed completion returned HTTP 200 with exactly one `[DONE]`.
- A deliberately unroutable local request returned HTTP 400 and produced an `unserved`, `ok=0`, `attempts=0` D1 row.
- Dashboard failure count matched `SELECT COUNT(*) FROM routing_decisions WHERE ok=0` at verification.
- The bounded health check observed 18 healthy samples, zero watchdog restarts and 49 ms p95 through 02:44 EAT. D1 integrity check returned `ok`.
- A 72-hour observation is in progress from 17 November 02:26:58 EAT, with streaming checkpoints and a review scheduled for 20 November 02:40 EAT. This is not yet a 72-hour result.

## Open risks

- At 02:49 EAT the post-baseline ledger showed 106 successful completions, 18 timeout failures and two `non_retryable_candidate_open` failures. Their root cause is unproven and tracked as `bk-795e`.
- A watchdog cannot observe when the Mac is asleep or launchd is not scheduling jobs; the final report must count missing intervals.
- The Worker telemetry and dashboard changes were applied locally. No cloud deployment was performed as part of this local-runtime work.

The machine-readable companion is `20261117-local-runtime-bk3060.json`.

**Simplicity check:** Wrangler remains the sole router implementation; the watchdog supervises it rather than duplicating routing logic.
