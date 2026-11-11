/** Hourly market snapshot for the owner-selected intelligence-ranked chain.
 * Availability is a ranking weight (owner rule 2026-11-11): a model's real price
 * is what several sellers can sustain inside the output bid, not what one lone
 * ask advertises. A book with a single in-cap seller is bait — excluded. */

export const AUTO_MODEL = "infered/auto";
export const AUTO_OUTPUT_BID = 0.999999;
export const AUTO_HOUR_MS = 60 * 60 * 1000;

// Scores are the Artificial Analysis snapshot from 2026-11-10, not live scores.
// Equal-score listings are ordered by in-cap seller depth (availability), then
// sustained in-cap price — never by a single seller's cheapest ask.
export const AUTO_MODELS = [
  { id: "cx/gpt-6-astra", score: 57 },
  { id: "oai/gpt-6.1-sol", score: 55 },
  { id: "cx/gpt-6.1-terra", score: 51 },
  { id: "oai/gpt-6.1-terra", score: 51 },
  { id: "ali/kimi-k3.1-pro", score: 46 },
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
    const pricing = entries.get(id)?.pricing;
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
    models.push({ modelId: id, score, prompt, completion: asks[0],
      askCount: asks.length, inCapDepth: inCap.length, depthPrice: inCap[inCap.length - 1] });
  }
  models.sort((a, b) => b.score - a.score || b.inCapDepth - a.inCapDepth ||
    a.depthPrice - b.depthPrice || a.modelId.localeCompare(b.modelId));
  const hour = Math.floor(now / AUTO_HOUR_MS);
  return { hour, refreshedAt: now, nextRefreshAt: (hour + 1) * AUTO_HOUR_MS, models, excluded };
}

async function readSnapshot(db) {
  return db.prepare("SELECT hour, payload FROM auto_chain_snapshot WHERE id = 'auto'").bind().first();
}

function withStaleness(row, currentHour) {
  return { ...JSON.parse(row.payload), stale: Number(row.hour) < currentHour };
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
  if (row && Number(row.hour) === hour) return withStaleness(row, hour);

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
    return withStaleness(row, hour);
  }
  if (!row) throw new Error("Auto snapshot is unavailable for this hour");
  return withStaleness(row, hour);
}
