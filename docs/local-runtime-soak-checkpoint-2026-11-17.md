# Local runtime soak — checkpoint 1 (2026-11-17, 13:45 EAT)

Status: **incomplete by design**. The bounded soak ends 2026-11-20 03:10:49 EAT.
Nothing here is a 72-hour verdict.

Runtime under test: `dev.moneyacad.infered` (Wrangler 4.163.0 pinned), watchdog
`dev.moneyacad.infered.watchdog` (60s interval), checkout `~/Projects/infered-local`,
D1 state `~/.opencrabs/state/infered-local/wrangler-state`.

## 1. Process stability — measured, good

Source: `watchdog.log`, 696 samples from 01:49:46 EAT to 13:42:20 EAT (11h52m).

| Metric | Value |
|---|---|
| Health samples | 696 |
| Healthy samples | 695 |
| Unhealthy samples | 1 (02:25:05 EAT, single sample, self-recovered) |
| Watchdog restarts | 0 |
| Health latency min / p50 / p95 / max | 21 / 36 / 57 / 198 ms |
| Service PID at checkpoint | 76366 (launchd, last exit 143 from the pre-soak restart) |

The runtime did not wedge, crash, or restart during the window. The single
unhealthy sample did not repeat on the next tick, so it was not a process failure.

## 2. Request success — degraded, and now fully down

Source: read-only copy of the D1 ledger, window `ts >= 2026-11-17 00:10:49`
(soak start 03:10:49 EAT).

| Metric | Value |
|---|---|
| Routing decisions | 634 |
| Succeeded | 377 (59.46%) |
| Failed | 257 |
| Last successful request | 08:36:47Z = **11:36:47 EAT** |
| Consecutive failures since | **220** |

Model share in the same window:

| selected_model | attempts | ok | fail | avg latency |
|---|---|---|---|---|
| cx/gpt-6.1-terra | 382 | 377 | 5 | 16,345 ms |
| unserved | 250 | 0 | 250 | 5,345 ms |
| grok/grok-4.7 | 2 | 0 | 2 | 49,539 ms |

## 3. Failure classes in the window

| Class | Count | Window |
|---|---|---|
| 402 insufficient_balance (cx/gpt-6-astra) | 180 | 09:07Z → 10:39Z |
| 402 insufficient_balance (cx/gpt-6.1-terra) | 39 | 08:36Z → 08:39Z |
| Request timed out | 29 | 00:12Z → 08:34Z |
| non_retryable_candidate_open | 3 | 04:09Z → 06:20Z |
| client_disconnected | 2 | 04:10Z → 04:13Z |
| all_candidates_exhausted_pre_content | 2 | 07:16Z → 08:13Z |
| stream_errored_pre_content | 1 | 08:36Z |
| Network connection lost | 1 | 04:14Z |

**219 of 257 failures are upstream billing, not runtime.** The upstream provider
`inferhub` returns HTTP 402 `insufficient_balance` ("deposit USDC to continue")
for every model on the account.

## 4. Live confirmation at checkpoint

| Probe | Result |
|---|---|
| `GET /v1/health` | 200, ~6 ms |
| `POST /v1/chat/completions` (`infered/auto`) | **503**, 1.58 s |
| auto failovers | cx/gpt-6-astra 402, cx/gpt-6.1-terra 402, grok/grok-4.7 402 |
| `oai/gpt-6.1-sol` direct | 503, 402 on `inferhub-node-oai-gpt-6-1-sol-*` |
| `ali/glm-5.3` direct | 503, 402 after 10 upstream attempts |

`.dev.vars` defines exactly one upstream: `INFERHUB_API_KEY` / `INFERHUB_BASE_URL`.
There is no funded alternate provider to fail over to, which is why every
candidate dies with 402 rather than rerouting.

## 5. Latency — upstream-bound, unchanged by hardening

Successful requests average **16.3 s** with a **74.3 s** worst case on
`cx/gpt-6.1-terra`. Failed attempts fail fast (~2.3 s for 402, ~5.3 s for
`unserved` rows). The latency ledger fix from `77e7fb7` is working: recorded
values match client-observed wall clock instead of per-attempt slices.

The 29 timeouts between 00:12Z and 08:34Z are the pre-existing upstream slowness
already tracked as `bk-795e`; the hardening made them visible, it did not cause
them.

## 6. Verdict

- Runtime stability: **pass** (0 restarts, 99.86% health samples over ~12 h).
- Service availability: **fail since 11:36:47 EAT**, cause is upstream balance, not code.
- Latency: **poor but attributable upstream** (16.3 s mean success).
- Soak: **continuing**, not concluded. Next checkpoint 2026-11-18 03:15 EAT.

The honest distinction: the process is stable, the product is down. A green
watchdog proves the first and says nothing about the second, which is exactly
why health checks alone were never enough evidence.
