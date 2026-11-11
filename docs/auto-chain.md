# `infered/auto`

`infered/auto` selects from the ten models in its roster (`src/router/auto.js`). Its fixed intelligence scores are an Artificial Analysis benchmark snapshot, not live or task-specific quality scores. The roster grew from the 10 November 2026 comparison: `grok/grok-4.7` (score 35.5, 9 in-cap sellers at $0.006/M out) and `ag/claude-sonnet-4-6` (score 51, 7+ in-cap sellers at $0.285/M out) joined on 11 November 2026 — the latter once its account toggle was flipped. `ag/gemini-4-argon` is market-eligible but **excluded by owner directive (2026-11-11)**; a test pin keeps it out of the roster.

## Hourly market refresh

The Worker refreshes a D1 snapshot at the top of each hour through a Cloudflare Cron Trigger. If a request arrives without the current hourly snapshot, it fetches an authenticated InferHub `/models` feed and creates one. The snapshot contains only models with an **in-cap seller depth of at least two** — at least two distinct asks at or below the output bid — sorted by intelligence score; equal scores are ordered by in-cap seller depth first, then cache-adjusted blended price (25% effective input + 75% sustained in-cap output), never by one seller's cheapest ask. `GET /v1/auto` exposes the snapshot and refresh time, a `stale` label, and an `excluded` list naming every roster model that dropped out with its reason (`not_in_feed`, `unpriced`, `over_cap`, or `bait_priced` — a lone in-cap seller, reported with its `ask` and the `nextAsk` cliff behind it). Each member reports `askCount` (total asks), `inCapDepth` (sellers within the bid), `depthPrice` (sustained in-cap price), `supportsCache`, `effectiveInput`, and `effectiveBlended` — the cache-adjusted prices described below.

## Availability weighting

A model's real price is what several sellers can sustain inside the bid, not what one lone ask advertises. A book with a single in-cap seller — one provider at $0.001 with the next ask at $2.50 — is bait: the cheap ask is a lottery ticket, not a market. Such models are excluded from the chain entirely, and depth is the first tie-break among equal scores, because provider count in the low price tier is a stability measure, not a vanity metric. The per-attempt bid still enforces the ceiling regardless of what the snapshot says.

A missing or stale snapshot never falls back to seeded or official prices. If the fresh feed is unavailable at an hour boundary, auto serves the last live hourly snapshot labeled `stale: true` instead of failing the chain — the per-attempt output bid still enforces the ceiling, so staleness only ages the ordering, never the limit. With no snapshot at all, auto returns an error. The snapshot's prices are observations, not a capacity guarantee. Market changes can occur between hourly refreshes.

## Cache-aware effective pricing (2026-11-11)

Prompt caching is real and priced on this market. A live double-probe (identical 5,276-token prefix, sent twice) showed the repeat serving 5,265 tokens (99.8%) from cache and billing them at **0.10× the fresh input ask** — InferHub passes the upstream cache-read discount through (the serving node's asks: input $0.057, cached $0.0057, output $0.285).

The feed carries a `supports_cache` flag per model but publishes no cache ask prices, so the snapshot applies measured constants:

- `tokenCost: 0.1` — measured: cached tokens bill at one tenth of the fresh input ask.
- `hitRate: 0.5` — assumed share of a conversation's input tokens served from cache. The immediate-repeat probe hit 99.8%, but cache is node-local and the book reprices hourly, so real traffic sits well below that ceiling; 0.5 is the conservative ranking assumption.

A cache-capable member's `effectiveInput = prompt × (1 − hitRate × (1 − tokenCost))` — 55% of its raw ask at today's constants. The price tie-break ranks on `effectiveBlended = 25% effective input + 75% sustained in-cap output`, the same input/output mix the router uses everywhere (`calculateBlendedPrice`). Models without the flag pay the full input ask. Both constants surface in `/v1/auto` under `cache` alongside a `measured` provenance string; measured D1 rates will replace the assumptions once usage rows record `cached_tokens`.

The assumption covers ranking economics only. What a request actually pays depends on which node serves it and whether its prefix is warm there.

## Hard output-rate boundary

Every auto attempt, streaming or nonstreaming, carries InferHub's documented `x-max-output-price: 0.999999` bid. The router also filters by that cap, ignores the ordinary unlimited fallback and Astra's exemption for auto, and honors a caller's lower `X-Infered-Max-Price`. If no eligible model is available, the request fails rather than raising the ceiling. InferHub enforces the bid at seller selection time.

The limit is **per million output tokens**, not per request. It does not cap input-token prices, total tokens, minimum charges, or other service fees. It also does not govern a separate OpenCrabs provider fallback after a router request fails; that provider must have its own budget controls.

## Codex credit exhaustion

For both JSON and SSE responses, a leading, complete `[Codex error: credit_balance_exhausted: You have no credits remaining ...]` refusal is treated as a failed model attempt. The router skips remaining attempts for the refused model and continues the existing chain. Streaming buffers only the possible error prefix; ordinary output and heartbeats flow normally. Once genuine answer content has been sent, it cannot be retracted and replaced with another model's answer. If all models share the same exhausted account, switching models cannot create credits.

## Operations

1. Apply `migrations/0002_auto_chain.sql` before deploying the Worker.
2. Deploy `wrangler.jsonc` together with the Worker; its hourly cron expression is `0 * * * *`.
3. Inspect the current membership at `/v1/auto` and the product list at `/v1/models`.
4. Use `bb test-all`, `bb lint` and `bb scripts/smoke.bb` as the local and post-deployment gates.
5. The chain is available in the OpenCrabs `tupesa` model picker; adding it does not change the default model.
6. On the local door (`wrangler dev`), the launchd agent `dev.moneyacad.infered.auto` fires the refresh via `/__scheduled` at minute 2 of every hour (`StartCalendarInterval`; `RunAtLoad` warms it at boot). Control: `launchctl kickstart -k gui/501/dev.moneyacad.infered.auto`.

Verification witness: `verification/witnesses/20261110-143200-auto-quota.md` and `.json`.
