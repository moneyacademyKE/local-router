/** Hourly market snapshot for the owner-selected intelligence-ranked chain.
 * Availability is a ranking weight (owner rule 2026-11-11): a model's real price
 * is what several sellers can sustain inside the output bid, not what one lone
 * ask advertises. A book with a single in-cap seller is bait — excluded. */

export const AUTO_MODEL = "infered/auto";
export const AUTO_OUTPUT_BID = 0.999999;
export const AUTO_HOUR_MS = 60 * 60 * 1000;

// Cache economics (owner rule 2026-11-11; measured live the same day: an
// identical 5,276-token prefix sent twice to ag/claude-sonnet-4-6 cached
// 5,265 tokens on the repeat and billed them at 0.10x the fresh input ask —
// the node's own asks were in $0.057 / cached $0.0057 / out $0.285, so
// InferHub passes the upstream cache-read discount through).
// CACHE_TOKEN_COST is that measured ratio. CACHE_HIT_RATE is the assumed
// share of a conversation's input tokens served from cache: node-local
// cache plus hourly book refreshes keep real traffic well below the 99.8%
// immediate-repeat ceiling, so the assumption is deliberately conservative.
// Both surface in /v1/auto; measured D1 rates replace them when usage rows
// start recording cached_tokens.
export const CACHE_HIT_RATE = 0.5;
export const CACHE_TOKEN_COST = 0.1;

// Scores are the Artificial Analysis snapshot from 2026-11-10, not live scores.
// Equal-score listings are ordered by in-cap seller depth (availability), then
// cache-adjusted blended price (25% effective input + 75% sustained in-cap
// output) — never by a single seller's cheapest ask.
// 2026-11-11 (owner directive, market-gap wiring): grok-4.7 (35.5, 8 in-cap
// sellers) and claude-sonnet-4-6 (51, 7 in-cap sellers, account toggle
// flipped) join the pool. ag/gemini-4-argon stays OUT — explicit owner
// exclusion despite market eligibility. Same day: ag/gemini-4-carbon (60.0,
// the market's top score) joins under the seller-quota rule — its thin
// two-ask book saturates hard, and measured quota health demotes it below
// available members until supply recovers.
export const AUTO_MODELS = [
  { id: "ag/gemini-4-carbon", score: 60 },
  { id: "cx/gpt-6-astra", score: 57 },
  { id: "oai/gpt-6.1-sol", score: 55 },
  { id: "cx/gpt-6.1-terra", score: 51 },
  { id: "oai/gpt-6.1-terra", score: 51 },
  { id: "ag/claude-sonnet-4-6", score: 51 },
  { id: "ali/kimi-k3.1-pro", score: 46 },
  { id: "grok/grok-4.7", score: 35.5 },
  { id: "ali/qwen3.8-max-0902", score: 34 },
  { id: "ali/glm-5.3", score: 32 },
  { id: "zai/glm-5.3", score: 32 }
];

function minAsk(values, fallback) {
  const source = Array.isArray(values) && values.length ? values : [fallback];
  const valid = source.filter(v => v !== null && v !== undefined && v !== "")
    .map(Number).filter(v => Number.isFinite(v) && v >= 0);
  return valid.length ? Math.min(...valid) : null;
}

/** Sorted completion asks for one model: its seller ladder. A bare min_ask_out
 * (no ladder) is one seller at one price — a ladder of length one. */
function askLadder(pricing) {
  const source = Array.isArray(pricing?.asks_out) && pricing.asks_out.length
    ? pricing.asks_out
    : [pricing?.min_ask_out];
  return source.filter(v => v !== null && v !== undefined && v !== "")
    .map(Number).filter(v => Number.isFinite(v) && v >= 0).sort((a, b) => a - b);
}

export function buildAutoSnapshot(feed, now = Date.now()) {
  if (!Array.isArray(feed?.data)) throw new Error("Invalid live market response");
  const entries = new Map(feed.data.map(m => [m.id, m]));
  const models = [];
  const excluded = [];
  for (const { id, score } of AUTO_MODELS) {
    const entry = entries.get(id);
    const pricing = entry?.pricing;
    if (!pricing) { excluded.push({ modelId: id, reason: "not_in_feed" }); continue; }
    const prompt = minAsk(pricing.asks_in, pricing.min_ask_in);
    const asks = askLadder(pricing);
    if (prompt === null || !asks.length) { excluded.push({ modelId: id, reason: "unpriced" }); continue; }
    // In-cap sellers = providers offering output inside the bid: the low price
    // tier this chain can actually buy from. Depth there is the stability weight.
    const inCap = asks.filter(v => v <= AUTO_OUTPUT_BID);
    if (!inCap.length) { excluded.push({ modelId: id, reason: "over_cap", ask: asks[0] }); continue; }
    if (inCap.length === 1) {
      const nextAsk = asks.find(v => v > AUTO_OUTPUT_BID) ?? null;
      excluded.push({ modelId: id, reason: "bait_priced", ask: inCap[0], nextAsk });
      continue;
    }
    // Cache-adjusted pricing: a cache-capable model's returning conversation
    // buys the cached share of its input at the cache-read rate instead of
    // the fresh ask. The price tie-break uses the router's own 25/75
    // input/output blend (calculateBlendedPrice's mix), so the ranking's
    // price opinion finally includes what input costs — cache-aware.
    const supportsCache = Boolean(entry?.supports_cache);
    const effectiveInput = supportsCache
      ? Number((prompt * (1 - CACHE_HIT_RATE * (1 - CACHE_TOKEN_COST))).toFixed(6))
      : prompt;
    const depthPrice = inCap[inCap.length - 1];
    const effectiveBlended = Number(((0.25 * effectiveInput) + (0.75 * depthPrice)).toFixed(6));
    models.push({ modelId: id, score, prompt, completion: asks[0],
      askCount: asks.length, inCapDepth: inCap.length, depthPrice,
      supportsCache, effectiveInput, effectiveBlended });
  }
  models.sort((a, b) => b.score - a.score || b.inCapDepth - a.inCapDepth ||
    a.effectiveBlended - b.effectiveBlended || a.modelId.localeCompare(b.modelId));
  const hour = Math.floor(now / AUTO_HOUR_MS);
  return { hour, refreshedAt: now, nextRefreshAt: (hour + 1) * AUTO_HOUR_MS,
    cache: { hitRate: CACHE_HIT_RATE, tokenCost: CACHE_TOKEN_COST,
      measured: "2026-11-11 live double-probe: 5265/5276 prefix tokens cached, cache-read billed at 0.10x fresh input ask" },
    models, excluded };
}

/** Seller-quota health (owner rule 2026-11-11): the feed publishes no quota
 * data — pricing carries only asks — so availability is MEASURED: a tiny
 * probe per member classifies the live answer. A member whose sellers are out
 * of quota (429) or refused (403) demotes below every available member:
 * availability gates the head slot; it is not a score tie-break. Absent,
 * malformed, or stale quota data is a no-op, never a demotion. */
export function applyQuotaHealth(snapshot, quota) {
  const results = quota?.results;
  if (!results || typeof results !== "object" || Array.isArray(results)) return snapshot;
  const unhealthy = (m) => {
    const status = results[m.modelId]?.status;
    return status === "quota" || status === "refused";
  };
  const decorate = (m) => {
    const r = results[m.modelId];
    return r ? { ...m, quota: r } : m;
  };
  const models = snapshot.models || [];
  const demoted = models.filter(unhealthy).map(m => ({ ...decorate(m), saturated: true }));
  const healthy = models.filter(m => !unhealthy(m)).map(decorate);
  return { ...snapshot, models: [...healthy, ...demoted] };
}

const QUOTA_SELLERS = /([\d,]+)\s+of\s+([\d,]+)\s+sellers/i;

function classifyQuotaProbe(status, message) {
  if (status === 200) return { status: "ok" };
  if (status === 429) {
    const match = QUOTA_SELLERS.exec(message || "");
    const num = (s) => Number(s.replace(/,/g, ""));
    return { status: "quota",
      ...(match ? { sellersOut: num(match[1]), sellersTotal: num(match[2]) } : {}) };
  }
  if (status === 403) return { status: "refused" };
  return { status: "unknown" };
}

/** Measures seller quota: one tiny completion (max_tokens 8) per member,
 * fired hourly by the scheduled handler. 200 = ok, 429 = sellers out of
 * quota (evidence parsed from the refusal), 403 = refused, anything else =
 * unknown — a network flake never demotes. Probes carry the output bid
 * header so measurement can never overspend. Persists best-effort to the
 * auto-quota row; the returned payload is the truth either way. */
export async function probeQuotaHealth(env, members, fetchFn = fetch, now = Date.now()) {
  const baseUrl = env?.INFERHUB_BASE_URL || "https://api.inferhub.dev/v1";
  const results = {};
  for (const member of members) {
    const started = Date.now();
    let outcome = { status: "unknown" };
    try {
      const response = await fetchFn(`${baseUrl}/chat/completions`, {
        method: "POST",
        headers: { "Content-Type": "application/json",
          Authorization: `Bearer ${env?.INFERHUB_API_KEY}`,
          "x-max-output-price": String(AUTO_OUTPUT_BID) },
        body: JSON.stringify({ model: member.modelId, max_tokens: 8, stream: false,
          messages: [{ role: "user", content: "Reply with OK" }] }),
        signal: AbortSignal.timeout(20000)
      });
      let message = "";
      try { message = (await response.json())?.error?.message || ""; } catch { /* body is evidence, not a requirement */ }
      outcome = classifyQuotaProbe(response.status, message);
    } catch { outcome = { status: "unknown" }; }
    results[member.modelId] = { ...outcome, ms: Date.now() - started };
  }
  const hour = Math.floor(now / AUTO_HOUR_MS);
  const payload = { hour, probedAt: now, results };
  try {
    await env?.ROUTING_DB?.prepare(
      "INSERT INTO auto_chain_snapshot (id, hour, payload) VALUES ('auto-quota', ?1, ?2) " +
      "ON CONFLICT(id) DO UPDATE SET hour=excluded.hour, payload=excluded.payload " +
      "WHERE excluded.hour > auto_chain_snapshot.hour"
    ).bind(hour, JSON.stringify(payload)).run();
  } catch { /* probe persistence is best-effort; the payload is the truth */ }
  return payload;
}

async function readSnapshot(db) {
  return db.prepare("SELECT hour, payload FROM auto_chain_snapshot WHERE id = 'auto'").bind().first();
}

function withStaleness(row, currentHour) {
  return { ...JSON.parse(row.payload), stale: Number(row.hour) < currentHour };
}

/** Applies measured quota health to a served snapshot. Quota data older than
 * one hour is ignored; any read failure is a no-op — health is an
 * enhancement, never a dependency of the chain. */
async function withQuotaHealth(snapshot, db, hour) {
  try {
    const row = await db.prepare(
      "SELECT hour, payload FROM auto_chain_snapshot WHERE id = 'auto-quota'"
    ).bind().first();
    if (row && Number(row.hour) >= hour - 1) {
      return applyQuotaHealth(snapshot, JSON.parse(row.payload));
    }
  } catch { /* degrade to market order, never block the chain */ }
  return snapshot;
}

/**
 * Serves the current hour's D1 snapshot; the first call after each boundary
 * refreshes it from the live feed. A failed refresh serves the last live
 * snapshot labeled `stale` instead of failing the chain — the per-attempt
 * output bid still enforces the ceiling, so staleness only ages the
 * ordering, never the limit. No snapshot at all fails closed. Never falls
 * back to seeded or official prices.
 */
export async function getAutoSnapshot(env, fetchFn = fetch, now = Date.now()) {
  const db = env?.ROUTING_DB;
  if (!db || !env?.INFERHUB_API_KEY) throw new Error("Auto chain needs D1 and a live market key");
  const hour = Math.floor(now / AUTO_HOUR_MS);
  let row = await readSnapshot(db);
  if (row && Number(row.hour) === hour) return withQuotaHealth(withStaleness(row, hour), db, hour);

  const baseUrl = env.INFERHUB_BASE_URL || "https://api.inferhub.dev/v1";
  try {
    const response = await fetchFn(`${baseUrl}/models`, {
      headers: { Authorization: `Bearer ${env.INFERHUB_API_KEY}` }
    });
    if (!response.ok) throw new Error(`Auto market refresh failed (${response.status})`);
    const snapshot = buildAutoSnapshot(await response.json(), now);
    await db.prepare(
      "INSERT INTO auto_chain_snapshot (id, hour, payload) VALUES ('auto', ?1, ?2) " +
      "ON CONFLICT(id) DO UPDATE SET hour=excluded.hour, payload=excluded.payload " +
      "WHERE excluded.hour > auto_chain_snapshot.hour"
    ).bind(hour, JSON.stringify(snapshot)).run();
    row = await readSnapshot(db);
  } catch (refreshError) {
    if (!row) throw refreshError;
    return withQuotaHealth(withStaleness(row, hour), db, hour);
  }
  if (!row) throw new Error("Auto snapshot is unavailable for this hour");
  return withQuotaHealth(withStaleness(row, hour), db, hour);
}
