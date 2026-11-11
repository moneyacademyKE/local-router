# Market-Wide Model Gap Analysis — 2026-11-11

> **Question (owner):** analyse all models, even disabled ones — did we leave out anything very suitable?
> **Method:** full live feed pull (489 models), suitability filter (min output ask < $1, ≥2 sellers in the low tier, ctx ≥ 200K), intelligence from the Artificial Analysis Intelligence Index v5.0.1 (Nov 2026), account status by live probe (not feed presence).
> **Bead:** bk-0962 · **Scripts:** `scripts/market-sweep.bb`, `scripts/market-gap.bb`, `scripts/market-probe2.bb`

## TL;DR

The chains route 8 of 489 market models. **136 left-out models pass the suitability rules.** Two of them are enabled and could join a chain today; the eight best are one dashboard toggle away. The single biggest miss: **`ag/gemini-4-argon` — 50.4 intelligence at a $0.24–0.86 honest cluster with 7 in-cap sellers** — near-Terra intelligence at a quarter of Terra's price. And the market's #1 model overall (**Gemini 4 Carbon, 60.0**) has sellers under the $1 cap.

## Current catalog (reference)

| Model | Intelligence | Min out ask | In-cap depth | Status |
|---|---|---|---|---|
| cx/gpt-6-astra | 56.9 | $0.35 | 9 asks | enabled |
| oai/gpt-6.1-sol | 54.6 | $0.17* | 1* | enabled (bait shape) |
| cx + oai/gpt-6.1-terra | 51.3 | $0.09 / $0.50 | 4 / 2 | enabled |
| ali/kimi-k3.1-pro | 46.5 | $0.19 | 9 asks | enabled |
| ali/qwen3.8-max-0902 | 33.7 | $0.006 | 10 | enabled |
| ali/zai glm-5.3 | 31.7 | $0.035 / $0.0044 | 9 / 7 | enabled |

*Sol's cheap ask is the single-seller shape the depth rule exists to catch.

## Class A — ready today (already enabled, serving)

| Model | Intelligence | Honest cluster | In-cap | Verdict |
|---|---|---|---|---|
| **grok/grok-4.7** | 35.5 | $0.006 | 8 | **Strictly dominates every GLM member we route: +4 intelligence, ~6× cheaper than ali/glm-5.3, comparable depth.** Probe served "OK". 500K ctx. |
| **cb/deepseek-v4.1-flash** | 32.6 | $0.029 | **37** | Deepest book on the entire market. GLM-class intelligence at 1/7 the price. Probe hit 503 capacity (busy, not disabled) — retry or wire behind the cascade. |

Both slot naturally as chain tails (glm-budget after the glm pair) or as the `auto` low tier.

## Class B — one dashboard toggle away (disabled in preferences)

Ranked by fit under the auto chain's $0.999999 output bid:

| Model | Intelligence | Honest cluster (in-cap) | Fit |
|---|---|---|---|
| **ag/gemini-4-argon** | 50.4 | $0.24–0.86 (7) | The best value on the market: Terra-minus-1 intelligence at ~1/4 price. Would rank #2 in `auto` immediately. |
| **ag/claude-sonnet-4-6** | 51 | $0.285–0.75 (7) | AA's strongest *agentic* model (GDPval + Terminal-Bench leader). Terra-class, honest 7-seller cluster. |
| **cc/claude-opus-5-5** | 59.1 | $0.68–0.98 (4) | Astra-class **+2.2 points** at ~2× astra price. The strongest thing under the cap. |
| **ag/claude-sonnet-5-5** | 54.5 | $0.67 (2) | Sol-class for ~13% of Sol's price — but only 2 honest sellers (the $0.01 head ask is bait: 1 seller, then $0.67). |
| **ag/claude-opus-4-6-thinking** | 53† | $0.45–0.95 (4) | Above kimi-k3.1-pro (46.5) at half its price. 53 asks total — the deepest frontier book. |
| **cc/claude-haiku-5-5** | 36.5 | $0.024–0.24 (10) | Qwen-plus-3 at 4× qwen price, 236 tok/s output. Mid-tier filler, deep cheap cluster. |
| **ag/gemini-4-carbon** | 60.0 | $0.49–0.50 (2) | **#1 intelligence on the whole market, under the cap** — but the book is 2 sellers then a $5 cliff: bait-edge, enable and watch, don't trust the depth. |
| **cb/glm-5.3** | 31.7 | $0.2156 (26) | Same model as our chain member with **3× the seller depth** — a pure stability upgrade for the glm family. |
| cb/kimi-k3 | 44 | $0.735 (4) | kimi-k3.1-pro-minus-2.5, deeper in-cap book (4 vs 2). |
| cbcn/kimi-k2.7 | ~40 | $0.208 (14) | Honest mid book, code-tuned. |

†Opus 4.6 = 53 on the Feb 2026 index (v4-era); not re-scored on v5.0.1.

## Not recommended (with reasons)

- `cx/gpt-6-luna` (27.9) — below the glm floor we already route; cheap but dumber.
- MiniMax M3.1 Flash (29.1), Gemini 3.8 Flash (30.8), Ling 3.1 (29.6) — all below the floor.
- Claude Fable 5.x, oai/gpt-6-astra, ant/* Opus — over the $1 output cap or 1–2 asks only.
- Feed presence ≠ usable: `ag/gemini-3.8-flash` 404s as an id despite being quoted in comparisons.

## What the numbers say

1. **The account's enabled set is a tiny island.** Every Anthropic, Google, DeepSeek, MiniMax, and cb-family model — 403 `model_disabled` — is off in Dashboard → Models & pricing, while the whole cx/oai/ali/zai/grok set we route is on. The chains were built inside that island. The island is not the market.
2. **Intelligence per dollar collapses outside our island.** Inside it, the best value is Terra (51.3 @ $0.09). Outside: Argon (50.4 @ ~$0.30 honest cluster), Haiku (36.5 @ $0.03), Grok 4.7 (35.5 @ $0.006, already enabled).
3. **Depth, per the owner's rule, flips two picks:** Sol's $0.17 head is 1-seller bait (already excluded by the auto chain); Sonnet 5.5's $0.01 head is the same shape — its honest price is $0.67.

## Recommended actions

1. **Now:** add `grok/grok-4.7` to `glm-budget` (and the `auto` candidate pool). Zero dashboard change, probe-served, dominates the glm tail.
2. **Now or next capacity window:** `cb/deepseek-v4.1-flash` as the trivial-traffic workhorse (37-seller book).
3. **Dashboard toggle (your call, one screen):** enable `ag/gemini-4-argon` + `ag/claude-sonnet-4-6` first — they're the value picks. Then `cc/claude-opus-5-5` if you want a second frontier member. I wire each into chains as they enable.
4. **Stability upgrade:** swap glm-family shallow sources for `cb/glm-5.3` (26 in-cap) once enabled.

All prices are spot asks at 11:46 EAT, 2026-11-11 — they move. Intelligence: Artificial Analysis Intelligence Index v5.0.1 via artificialanalysis.ai / models.fru.dev (Nov 11, 2026 snapshot); scores measure model families at tested effort settings, not these specific InferHub sellers.
