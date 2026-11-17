# Local Router Soak — Restarted Window (2026-11-17)

**Bead:** bk-3893 (soak of record) · hardening parent bk-3060
**Window started:** 2026-11-17 15:39:57 EAT
**Window ends (not before):** 2026-11-20 15:39:57 EAT
**Runtime:** `/Users/moe/Projects/infered-local` · service `dev.moneyacad.infered`
**Commit at baseline:** `30fd76b` (tree clean, matches `local-router/main`)

## Why the window restarted

The prior window (started 2026-11-17 03:10:49 EAT) is **not usable as a stability
measurement**. Between roughly 08:03 and 11:43 UTC the upstream provider `inferhub`
returned HTTP 402 `balance too low for this request` for every candidate. That produced
a long run of consecutive failures that measure the upstream account balance, not the
router's stability.

The window was re-baselined on owner instruction. The outage is **excluded from the
stability assessment but is not erased** — the prior state and probe log are archived:

- `soak-bk-3060.json.pre-restart-2026-11-17T153943`
- `soak-probes.jsonl.pre-restart-2026-11-17T153943`

## Baseline captured

| Field | Value |
|---|---|
| D1 routing_decisions rows | 4300 |
| Historical failure rows | 445 |
| Last failure (UTC) | 2026-11-17 11:43:23 |
| Failures in prior 15 min | 0 |
| Service PID | 76366 |
| watchdog.log bytes | 77863 |
| dev-server.log bytes | 335648 |

## Checkpoints rescheduled

Moved from 03:15 EAT on 18/19/20 Nov to **15:45 EAT on 18/19/20 Nov** to match the new
window. Cron rows verified in `cron_jobs.next_run_at` (12:45 UTC = 15:45 EAT), all
enabled. Prompts rewritten to name the new window start and the excluded 402 outage.

## Post-restart verification

| Check | Result |
|---|---|
| `POST /v1/chat/completions` (`infered/auto`) | 200, 3319 ms, content `pong` |
| `GET /v1/health` | 200, 14 ms |
| `GET /v1/auto` | 200, 16 ms |
| `scripts/local-router-soak.bb` probe 1 | exit 0, 200, 1 `[DONE]`, 5073 ms |
| `scripts/local-router-soak.bb` probe 2 | exit 0, 200, 1 `[DONE]`, 4359 ms |
| Ledger rows for `bk-3893-soak` | 2 new rows, `ok=1` |
| Watchdog | healthy, 0 restarts |

## Open issue: the auto chain is effectively degraded to Grok

Both probes recorded **`attempts=3`** — two candidates failed before Grok served the
request. Direct probing of each candidate explains it:

| Candidate | Result |
|---|---|
| `cx/gpt-6-astra` | 200 non-stream (5.1 s); **stream took 31.4 s** with heartbeats before first content |
| `cx/gpt-6.1-terra` | **503** — `no available provider for cx/gpt-6.1-terra (all offers exhausted or in cooldown)` after 10 upstream attempts |
| `grok/grok-4.7` | 200, 3.6 s |

So a typical auto request pays: Astra pre-content attempt budget exhausted (slow
stream), then Terra 503, then Grok succeeds. The request succeeds but costs two wasted
upstream attempts and the wall-clock of both.

This is **upstream capacity and latency, not router logic**. It is recorded as the
residual defect to watch across the soak, not as a pass.

### Not the cause

The `Uncaught Error: upstream stream died mid-content` lines in `dev-server.log` are
**old**. The last occurrence sits at log line 4759; the post-recovery request region
begins around line 5760. They predate this window and are not implicated in the current
`attempts=3` behaviour.

## Caveat on this receipt

This is a restart record, not a soak verdict. Nothing here establishes 72-hour
stability. A PASS requires 72 hours of actual elapsed time after 2026-11-17 15:39:57 EAT
with adequate sample coverage, zero unexplained watchdog restarts, no TCC/build/telemetry
failures, and successful recorded stream probes.
