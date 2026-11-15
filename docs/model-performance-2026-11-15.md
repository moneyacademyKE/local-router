# Local Router Model and Chain Performance

**Window:** 2026-11-11 07:28 EAT to 2026-11-15 09:09 EAT
**Evidence:** 750 local D1 routing decisions, current auto snapshot, live health, tests, and launchd state.

## Verdict

`infered/auto` is the clear winner: **717/717 successful (100%)** and about 97% of router traffic. Keep it as the device default. The adaptive machinery is doing better than fixed chains.

The sample is not evenly distributed, so small model samples are directional rather than final benchmarks.

## Chain performance

| Chain | Requests | Success | Avg latency | Avg attempts |
|---|---:|---:|---:|---:|
| `infered/auto` | 717 | 100.0% | 36.7s | 1.57 |
| `infered/terra-kimi` | 17 | 94.1% | 24.5s | 1.12 |
| `infered/glm-budget` | 12 | 91.7% | 23.1s | 2.42 |

The two failed decisions were `client_disconnected`, not upstream exhaustion. Fixed-chain samples are too small to justify redesigning them.

## Model performance

| Model | Serves | Success | Avg latency | Avg attempts | Recommendation |
|---|---:|---:|---:|---:|---|
| `cx/gpt-6.1-terra` | 342 | 99.7% | 41.3s | 1.62 | Keep; reliable capacity, but slow |
| `ali/kimi-k3.1-pro` | 242 | 100.0% | 29.8s | 1.60 | Keep; best high-volume balance |
| `cx/gpt-6-astra` | 109 | 100.0% | 35.3s | 1.06 | Keep; reliable when eligible |
| `ali/qwen3.8-max-0902` | 27 | 100.0% | 43.1s | 2.30 | Keep as tail; reliable but slow |
| `ag/claude-sonnet-4-6` | 7 | 100.0% | 15.8s | 1.57 | Promote observation; fastest meaningful sample |
| `ali/glm-5.3` | 6 | 100.0% | 27.8s | 2.17 | Keep dynamic only; sample too small |
| `zai/glm-5.3` | 10 | 90.0% | 27.1s | 2.70 | Remove from fixed chains; leave auto-gated |
| `oai/gpt-6.1-terra` | 2 | 100.0% | 49.4s | 1.00 | Do not promote; sample too small and slow |
| `grok/grok-4.7` | 1 | 100.0% | 4.4s | 1.00 | Keep in auto; gather more evidence |

## Current auto snapshot

At 09:00 EAT, six models were eligible:

- `ag/claude-sonnet-4-6`: score 51, 11 in-cap sellers
- `cx/gpt-6.1-terra`: score 51, 10 in-cap sellers
- `ali/kimi-k3.1-pro`: score 46, 2 in-cap sellers
- `grok/grok-4.7`: score 35.5, 7 in-cap sellers
- `ali/qwen3.8-max-0902`: score 34, 8 in-cap sellers
- `zai/glm-5.3`: score 32, 7 in-cap sellers

Excluded at that refresh: Carbon absent from the feed; Astra bait-priced with one in-cap seller; both OAI 6.1 models over the $1 output cap; ali GLM over cap.

## Recommendations

1. **Keep auto as default.** It has the only large, clean sample and adapts to price, depth, quota, and cache evidence.
2. **Remove `zai/glm-5.3` from fixed chains.** It is the only model with an observed failed decision and has the highest attempts. Leave it available to auto only while it remains sufficiently deep and healthy.
3. **Do not remove Terra, Kimi, or Qwen 0902.** Together they carry the bulk of successful traffic; Qwen is a valuable tail even though it is slow.
4. **Let Sonnet receive more auto traffic, not a hard-coded head.** Seven successes at 15.8s is promising; the adaptive pool should collect a larger sample before changing scores.
5. **Keep Grok in auto and observe.** One 4.4s success plus seven in-cap sellers is promising, not proof.
6. **Do not re-add Carbon manually.** It should be re-admitted by the hourly snapshot only when it returns with a real feed entry and quota evidence. Argon remains excluded by owner directive.

## Stability task status

The local service is currently running as one launchd process; `/v1/health` returned 200 with live market data. The local D1 database currently contains all three migrations and the required tables. Earlier `no such table: routing_decisions` errors came from the restored/fresh local D1 state before migrations were applied, not from the request router itself. The durable remaining risk is bootstrap: launchd starts Wrangler directly, so a future state reset can recreate that schema gap. A migration-first wrapper should be the next stability change.

Current verification: full suite green across 20 namespaces, lint clean, all files under 500 LOC, local health 200.

## Rich Hickey conclusion

Do not add another scoring layer. Auto already composes market depth, quota, cache economics, and price. Prune the one fixed-chain underperformer, keep uncertain models adaptive, and make local startup apply migrations before serving. The simplest system that survives the evidence is the one to keep.
