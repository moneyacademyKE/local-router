/** Market snapshots for the market chains (./chains.js says which models, at
 * what bid, in whose order), rebuilt hourly and on a 30-minute heartbeat
 * (owner rule 2026-11-11). One mechanism serves every chain: a declared chain
 * is annotated by measured health and never reordered; a value chain is
 * re-sorted by it.
 * Availability is a price weight (owner rule 2026-11-11): a model's real price
 * is what several sellers can sustain inside the output bid, not what one lone
 * ask advertises. A book with a single in-cap seller is admitted but flagged
 * thinBook (2026-11-18) — depth orders the chain, it does not ban supply. */

import { loadHealth, applyValueRanking } from "./health.js";
import { AUTO_MODEL, MARKET_CHAINS } from "./chains.js";

export const AUTO_HOUR_MS = 60 * 60 * 1000;
// Heartbeat cadence (owner directive 2026-11-11): measured availability and
// the scheduled refresh run every 30 minutes, without changing hourly D1 keys.
export const AUTO_HEARTBEAT_MS = 30 * 60 * 1000;
export const AUTO_MAX_HEARTBEAT_MS = 35 * 60 * 1000;

// Cache economics (owner rule 2026-11-11; measured live on the marketplace:
// a repeated long prefix cached nearly all input at 0.10x the fresh ask).
// CACHE_TOKEN_COST is that measured ratio. CACHE_HIT_RATE is the assumed
// share of a conversation's input tokens served from cache: node-local
// cache plus hourly book refreshes keep real traffic well below the 99.8%
// immediate-repeat ceiling, so the assumption is deliberately conservative.
// Both surface in /v1/auto; measured D1 rates replace them when usage rows
// start recording cached_tokens.
export const CACHE_HIT_RATE = 0.5;
export const CACHE_TOKEN_COST = 0.1;

/** Drops everything a persisted row names that the chain no longer does: a
 * snapshot written before a chain change serves only current members. */
export function filterAutoSnapshotModels(snapshot, chain = MARKET_CHAINS[AUTO_MODEL]) {
  if (!snapshot || typeof snapshot !== "object") return { models: [] };
  const memberIds = new Set(chain.members.map(m => m.id));
  const models = Array.isArray(snapshot.models)
    ? snapshot.models.filter(model => memberIds.has(model?.modelId))
    : [];
  return { ...snapshot, models };
}

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

export function buildAutoSnapshot(feed, now = Date.now(), chain = MARKET_CHAINS[AUTO_MODEL]) {
  if (!Array.isArray(feed?.data)) throw new Error("Invalid live market response");
  const entries = new Map(feed.data.map(m => [m.id, m]));
  const models = [];
  const excluded = [];
  for (const { id, score } of chain.members) {
    const entry = entries.get(id);
    const pricing = entry?.pricing;
    if (!pricing) { excluded.push({ modelId: id, reason: "not_in_feed" }); continue; }
    const prompt = minAsk(pricing.asks_in, pricing.min_ask_in);
    const asks = askLadder(pricing);
    if (prompt === null || !asks.length) { excluded.push({ modelId: id, reason: "unpriced" }); continue; }
    // In-cap sellers = providers offering output inside the bid: the low price
    // tier this chain can actually buy from. Depth there is the stability weight.
    const inCap = asks.filter(v => v <= chain.bid);
    if (!inCap.length) { excluded.push({ modelId: id, reason: "over_cap", ask: asks[0] }); continue; }
    // A one-seller book is NOT excluded (2026-11-18). The router buys from that
    // seller at a price inside the bid, so the model is genuinely purchasable;
    // excluding it deletes real supply on the strength of a belief ("the lone
    // ask will vanish") that the attempt ledger can measure instead. It is
    // admitted flagged thinBook and ranked in the `thin` tier: behind members
    // whose book is deep, ahead of nothing that answers. A thin book that stops
    // answering demotes itself after HEALTH_MIN_ATTEMPTS, which is measurement.
    const thinBook = inCap.length === 1;
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
    models.push({ modelId: id, ...(Number.isFinite(score) ? { score } : {}), prompt, completion: asks[0],
      askCount: asks.length, inCapDepth: inCap.length, depthPrice, thinBook,
      supportsCache, effectiveInput, effectiveBlended });
  }
  // A declared chain's order IS its served order — no build-time sort, no
  // score-led reshuffle (applyValueRanking is told not to reorder, see
  // withValueOrder, so a cold D1 and a warm one agree on the sequence the
  // owner asked for). A value chain keeps a deterministic capability-led build
  // order — score, then depth, then blended price — as the sequence health
  // re-sorts, so a cold D1 still serves a sensible chain.
  if (chain.order === "value") {
    models.sort((a, b) => (b.score ?? 0) - (a.score ?? 0) ||
      b.inCapDepth - a.inCapDepth || a.effectiveBlended - b.effectiveBlended ||
      a.modelId.localeCompare(b.modelId));
  }
  const hour = Math.floor(now / AUTO_HOUR_MS);
  return { hour, refreshedAt: now, nextRefreshAt: (hour + 1) * AUTO_HOUR_MS, model: chain.model, bid: chain.bid,
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
 * fired every 30 minutes by the scheduled handler. 200 = ok, 429 = sellers out of
 * quota (evidence parsed from the refusal), 403 = refused, anything else =
 * unknown — a network flake never demotes. Probes carry the output bid
 * header so measurement can never overspend. Persists best-effort to the
 * chain's quota row; the returned payload is the truth either way. */
export async function probeQuotaHealth(env, members, fetchFn = fetch, now = Date.now(), chain = MARKET_CHAINS[AUTO_MODEL]) {
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
          "x-max-output-price": String(chain.bid) },
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
      `INSERT INTO auto_chain_snapshot (id, hour, payload) VALUES ('${chain.quotaRowId}', ?1, ?2) ` +
      "ON CONFLICT(id) DO UPDATE SET hour=excluded.hour, payload=excluded.payload"
    ).bind(hour, JSON.stringify(payload)).run();
    // No freshness guard on the quota row: the heartbeat writer is single and
    // sequential (scheduled handler only), so the newest probe battery is by
    // construction the freshest evidence. A same-hour guard would discard
    // every minute-32 fire (30-minute heartbeat, hourly row keys).
  } catch { /* probe persistence is best-effort; the payload is the truth */ }
  return payload;
}

/** The 30-minute heartbeat, for every chain: make sure the hour's snapshot
 * exists, then measure seller quota. Chains run one after another on purpose
 * — each battery is a burst of upstream probes, and two at once would double
 * it. A chain that fails is logged and skipped: one dead book must not stop
 * the other from being measured. */
export async function runHeartbeat(env, now = Date.now()) {
  for (const chain of Object.values(MARKET_CHAINS)) {
    try {
      const snapshot = await getAutoSnapshot(env, fetch, now, chain);
      await probeQuotaHealth(env, snapshot?.models || [], fetch, now, chain);
    } catch (e) {
      console.error(`heartbeat: ${chain.model} skipped:`, e?.message || e);
    }
  }
}

async function readSnapshot(db, rowId) {
  return db.prepare(`SELECT hour, payload FROM auto_chain_snapshot WHERE id = '${rowId}'`).bind().first();
}

function withStaleness(row, currentHour, chain) {
  const snapshot = filterAutoSnapshotModels(JSON.parse(row.payload), chain);
  return { ...snapshot, stale: Number(row.hour) < currentHour };
}

/** Applies measured quota health to a served snapshot. Quota data older than
 * one hour is ignored; any read failure is a no-op — health is an
 * enhancement, never a dependency of the chain. */
async function withQuotaHealth(snapshot, db, hour, quotaRowId) {
  try {
    const row = await db.prepare(
      `SELECT hour, payload FROM auto_chain_snapshot WHERE id = '${quotaRowId}'`
    ).bind().first();
    if (row && Number(row.hour) >= hour - 1) {
      return applyQuotaHealth(snapshot, JSON.parse(row.payload));
    }
  } catch { /* degrade to market order, never block the chain */ }
  return snapshot;
}

/** Annotates the chain with measured health: reliability, speed, value tier,
 * attempt counts, cooldown. A declared chain is NOT reordered — the owner
 * declared the sequence (2026-11-21), so a model that has been slower or less
 * reliable still sits where the owner put it and says so in its own fields. A
 * value chain IS reordered: intelligence per dollar is a measurement, so the
 * measured order is the served order. A D1 read failure degrades to the
 * build order, never to a dead chain. Live failure still outranks the order
 * downstream: pareto.js pushes a circuit-tripped member to the back. */
async function withValueOrder(snapshot, db, now, chain) {
  try {
    const health = await loadHealth(db, now);
    return health ? applyValueRanking(snapshot, health, { reorder: chain.order === "value" }) : snapshot;
  } catch { return snapshot; }
}

/** One-glance explanation of the served chain: why it is this deep, who is
 * cooling, and what single price change would widen it. The router already
 * knew all of this — it just never said it, so every question about a short
 * chain needed a SQL session. Pure function of the snapshot: no I/O, no
 * secrets, nothing that can fail. */
export function chainDiagnostics(snapshot, chain = MARKET_CHAINS[AUTO_MODEL]) {
  const bid = chain.bid;
  const models = Array.isArray(snapshot?.models) ? snapshot.models : [];
  const excluded = Array.isArray(snapshot?.excluded) ? snapshot.excluded : [];
  const gateCounts = {};
  for (const e of excluded) {
    const reason = e?.reason || "unknown";
    gateCounts[reason] = (gateCounts[reason] || 0) + 1;
  }
  const lockedOut = excluded
    .filter(e => e.reason === "over_cap" && Number.isFinite(e.ask))
    .sort((a, b) => a.ask - b.ask);
  const unlock = lockedOut.length
    ? { modelId: lockedOut[0].modelId, ask: lockedOut[0].ask,
      gap: Number((lockedOut[0].ask - bid).toFixed(6)) }
    : null;
  const cooling = models.filter(m => m.valueTier === "cooling").map(m => m.modelId);
  const thin = models.filter(m => m.thinBook === true).map(m => m.modelId);
  const gates = Object.entries(gateCounts)
    .map(([reason, n]) => `${n} ${reason}`).join(", ");
  const warnings = [];
  if (!models.length) {
    warnings.push("chain is empty: nothing is buyable inside the output bid");
  } else if (models.length < 3) {
    warnings.push(`chain depth ${models.length} of ${models.length + excluded.length} ${chain.noun}s` +
      (gates ? ` (${gates})` : ""));
  }
  if (models.length && !models.some(m => m.valueTier === "proven")) {
    warnings.push("no proven member: every candidate is unproven, thin or cooling");
  }
  if (cooling.length) warnings.push(`cooling after a recent failure: ${cooling.join(", ")}`);
  if (thin.length) warnings.push(`thin book, one in-cap seller: ${thin.join(", ")}`);
  // A member that the gates dropped is the owner's business, not a silent
  // omission: name it, and name why, in the same sentence.
  const servedIds = new Set(models.map(m => m.modelId));
  const missing = chain.members.map(m => m.id).filter(id => !servedIds.has(id));
  if (missing.length) {
    warnings.push(`${chain.noun} absent from the served chain: ${missing.join(", ")}` +
      (gates ? ` (${gates})` : ""));
  }
  if (unlock) warnings.push(`raise the output bid to ${unlock.ask} to admit ${unlock.modelId}`);
  const head = models[0] || null;
  return { bid, chainDepth: models.length, gateCounts, thinBook: thin, cooling,
    // Who decided this sequence. A declared chain says so; a value chain
    // says the order was measured. Said explicitly so a report never has to
    // infer whether an order was bought, declared, or found.
    orderSource: chain.orderSource,
    head: head ? { modelId: head.modelId, valueTier: head.valueTier,
      valueScore: head.valueScore, measuredAttempts: head.measuredAttempts } : null,
    unlock, warnings };
}

/**
 * Serves the current hour's D1 snapshot; the first call after each boundary
 * refreshes it from the live feed. A failed refresh serves the last live
 * snapshot labeled `stale` instead of failing the chain — the per-attempt
 * output bid still enforces the ceiling, so staleness only ages the
 * ordering, never the limit. No snapshot at all fails closed. Never falls
 * back to seeded or official prices.
 *
 * Ordering layers, innermost first: the build order, the measured value
 * order (reliability/speed/cost — it re-sorts a value chain and only
 * annotates a declared one), and quota health last — a member whose sellers
 * are out of quota is demoted regardless of how good its value is.
 * Diagnostics ride on the result: they describe the chain, they never shape it.
 */
export async function getAutoSnapshot(env, fetchFn = fetch, now = Date.now(), chain = MARKET_CHAINS[AUTO_MODEL]) {
  const db = env?.ROUTING_DB;
  if (!db || !env?.INFERHUB_API_KEY) throw new Error("Auto chain needs D1 and a live market key");
  const hour = Math.floor(now / AUTO_HOUR_MS);
  const serve = async (row) => {
    const snapshot = await withQuotaHealth(
      await withValueOrder(withStaleness(row, hour, chain), db, now, chain), db, hour, chain.quotaRowId);
    // `declared` is the chain as written in ./chains.js, said by the live table
    // rather than the stored row, so a reader can set the two orders side by
    // side: what was declared, and what is being served.
    return { ...snapshot, declared: chain.members.map(m => m.id), diagnostics: chainDiagnostics(snapshot, chain) };
  };
  let row = await readSnapshot(db, chain.rowId);
  if (row && Number(row.hour) === hour) return serve(row);

  const baseUrl = env.INFERHUB_BASE_URL || "https://api.inferhub.dev/v1";
  try {
    const response = await fetchFn(`${baseUrl}/models`, {
      headers: { Authorization: `Bearer ${env.INFERHUB_API_KEY}` }
    });
    if (!response.ok) throw new Error(`Auto market refresh failed (${response.status})`);
    const snapshot = buildAutoSnapshot(await response.json(), now, chain);
    await db.prepare(
      `INSERT INTO auto_chain_snapshot (id, hour, payload) VALUES ('${chain.rowId}', ?1, ?2) ` +
      "ON CONFLICT(id) DO UPDATE SET hour=excluded.hour, payload=excluded.payload " +
      "WHERE excluded.hour > auto_chain_snapshot.hour"
    ).bind(hour, JSON.stringify(snapshot)).run();
    row = await readSnapshot(db, chain.rowId);
  } catch (refreshError) {
    if (!row) throw refreshError;
    return serve(row);
  }
  if (!row) throw new Error("Auto snapshot is unavailable for this hour");
  return serve(row);
}
