/** Measured model health and the value ranking it feeds (owner directive
 * 2026-11-18: "rank the most intelligent but reliable and cost-effective
 * models higher — value for money without degraded performance").
 *
 * Three inputs, all auditable, none hand-set per model:
 *   intelligence  score            — the Artificial Analysis index (capability)
 *   reliability   success rate     — measured attempts, Laplace-smoothed
 *   speed         relative latency — measured, against the fastest member
 *   cost          effectiveBlended — cache-aware blended $/M from the book
 *
 * valueScore = score × reliability × speed ÷ effectiveBlended
 *
 * The alternative we rejected: another hand-maintained ordering. A priority
 * list encodes yesterday's capacity and has to be re-edited every time
 * upstream moves — that is a policy that cannot be measured, only believed.
 * Attempts are facts; the ranking is derived from them.
 */

export const HEALTH_WINDOW_HOURS = 24;
// Laplace pseudo-observations: an unproven model is pulled toward the fleet's
// own success rate instead of being judged on one lucky or one unlucky attempt.
export const HEALTH_PRIOR_WEIGHT = 10;
export const HEALTH_FRESH_MS = 30 * 60 * 1000;
// One attempt is an anecdote, not a verdict: a member is only branded
// "disproven" after this many tries, so a single transient 503 cannot exile
// a model that has never actually been given a fair run.
export const HEALTH_MIN_ATTEMPTS = 3;
export const ATTEMPT_RETENTION_DAYS = 7;
export const HEALTH_ROW_ID = "auto-health";

const round = (v, d = 3) => (Number.isFinite(v) ? Number(v.toFixed(d)) : null);
const clip = (s, n = 200) => (s === null || s === undefined ? null : String(s).slice(0, n));

/** One row per upstream attempt — the failed ones from failoverErrors, plus
 * the attempt that actually served. Best-effort like every ledger write:
 * observability never breaks routing, but it is never silent either. */
export async function recordAttempts(env, { requestedModel, failoverErrors, served, servedLatencyMs }) {
  try {
    const db = env?.ROUTING_DB;
    if (!db) return 0;
    const rows = [];
    for (const e of failoverErrors || []) {
      if (!e?.modelId) continue;
      rows.push([clip(requestedModel), e.modelId, e.providerId ?? null, 0,
        Number.isFinite(e.status) ? e.status : null,
        Number.isFinite(e.latencyMs) ? Math.round(e.latencyMs) : null, clip(e.error)]);
    }
    if (served?.modelId) {
      rows.push([clip(requestedModel), served.modelId, served.providerId ?? null, 1, 200,
        Number.isFinite(servedLatencyMs) ? Math.round(servedLatencyMs) : null, null]);
    }
    if (!rows.length) return 0;
    await db.batch(rows.map(r => db.prepare(
      "INSERT INTO routing_attempts (requested_model, model, provider, ok, status, latency_ms, error) " +
      "VALUES (?1,?2,?3,?4,?5,?6,?7)"
    ).bind(...r)));
    return rows.length;
  } catch (e) {
    console.error("routing-attempts: D1 write failed:", e?.message || e);
    return 0;
  }
}

/** Aggregates the attempt ledger into per-model health plus fleet totals.
 * ok/fail are counts; avgMs is the mean latency of the attempts that WORKED
 * (a timeout's latency measures the timeout, not the model). */
export async function aggregateHealth(db, now = Date.now()) {
  const window = `-${HEALTH_WINDOW_HOURS} hours`;
  const res = await db.prepare(
    "SELECT model, SUM(ok) AS ok, SUM(1 - ok) AS fail, " +
    "CAST(AVG(CASE WHEN ok = 1 THEN latency_ms END) AS INTEGER) AS avg_ms " +
    "FROM routing_attempts WHERE ts >= datetime('now', ?1) GROUP BY model"
  ).bind(window).all();
  const rows = res?.results || [];
  const models = {};
  let ok = 0, fail = 0;
  for (const r of rows) {
    const mOk = Number(r.ok) || 0;
    const mFail = Number(r.fail) || 0;
    ok += mOk; fail += mFail;
    models[r.model] = {
      ok: mOk, fail: mFail,
      rate: round(mOk / Math.max(1, mOk + mFail)),
      avgMs: Number.isFinite(r.avg_ms) && r.avg_ms > 0 ? r.avg_ms : null
    };
  }
  const fastest = Object.values(models)
    .map(m => m.avgMs).filter(v => Number.isFinite(v) && v > 0);
  return {
    windowHours: HEALTH_WINDOW_HOURS,
    computedAt: now,
    fleet: {
      ok, fail,
      rate: ok + fail > 0 ? round(ok / (ok + fail)) : null,
      fastestMs: fastest.length ? Math.min(...fastest) : null
    },
    models
  };
}

/** Reads the cached health payload, aggregating and re-caching when it is
 * older than the heartbeat. A read failure degrades to "no measurement",
 * which the ranking treats as neutral — never as a demotion. */
export async function loadHealth(db, now = Date.now()) {
  try {
    const row = await db.prepare(
      "SELECT payload FROM auto_chain_snapshot WHERE id = ?1"
    ).bind(HEALTH_ROW_ID).first();
    if (row) {
      const cached = JSON.parse(row.payload);
      if (Number(cached?.computedAt) > now - HEALTH_FRESH_MS) return cached;
    }
  } catch { /* fall through to a fresh aggregation */ }
  try {
    const health = await aggregateHealth(db, now);
    const hour = Math.floor(now / (60 * 60 * 1000));
    await db.prepare(
      "INSERT INTO auto_chain_snapshot (id, hour, payload) VALUES (?1, ?2, ?3) " +
      "ON CONFLICT(id) DO UPDATE SET hour=excluded.hour, payload=excluded.payload"
    ).bind(HEALTH_ROW_ID, hour, JSON.stringify(health)).run();
    // Retention: the ledger is evidence, not an archive.
    await db.prepare(
      "DELETE FROM routing_attempts WHERE ts < datetime('now', ?1)"
    ).bind(`-${ATTEMPT_RETENTION_DAYS} days`).run().catch(() => {});
    return health;
  } catch { return null; }
}

/** Attaches reliability/speed/value to each snapshot member and orders by
 * value in three tiers:
 *   0 proven     — tried, answered, measured value
 *   1 unproven   — never tried, or tried too few times to judge: neutral
 *                  weights, ranked after everything that has proven it works
 *   2 disproven  — tried HEALTH_MIN_ATTEMPTS+ times and never once answered
 *                  (speed 0, value 0)
 * The tiers exist so a new model still gets a chance once the proven members
 * are exhausted, without letting an untested model jump a measured workhorse.
 * A single failure never lands a model in "disproven" — evidence floor first.
 */
export function applyValueRanking(snapshot, health) {
  const models = Array.isArray(snapshot?.models) ? snapshot.models : [];
  if (!models.length) return snapshot;
  const fleetRate = Number.isFinite(health?.fleet?.rate) ? health.fleet.rate : null;
  const ranked = models.map(m => {
    const h = health?.models?.[m.modelId] || null;
    const ok = h?.ok || 0;
    const attempts = ok + (h?.fail || 0);
    const cost = Number.isFinite(m.effectiveBlended) && m.effectiveBlended > 0
      ? m.effectiveBlended : 1e-6;
    let reliability, speed;
    if (attempts === 0) {
      reliability = fleetRate === null ? 1 : fleetRate;
      speed = 1;
    } else {
      const p0 = fleetRate === null ? 0.5 : fleetRate;
      reliability = (ok + HEALTH_PRIOR_WEIGHT * p0) / (attempts + HEALTH_PRIOR_WEIGHT);
      const fastest = health?.fleet?.fastestMs;
      speed = ok > 0 && Number.isFinite(fastest) && Number.isFinite(h.avgMs) && h.avgMs > 0
        ? Math.min(1, fastest / h.avgMs) : 0;
    }
    const score = Number.isFinite(m.score) ? m.score : 0;
    return { ...m, reliability: round(reliability), speed: round(speed),
      valueScore: round((score * reliability * speed) / cost),
      measuredAttempts: attempts,
      valueTier: attempts === 0 ? "unproven"
        : (ok > 0 ? "proven"
          : (attempts >= HEALTH_MIN_ATTEMPTS ? "disproven" : "unproven")) };
  });
  const tierRank = { proven: 0, unproven: 1, disproven: 2 };
  ranked.sort((a, b) => tierRank[a.valueTier] - tierRank[b.valueTier] ||
    (b.valueScore ?? -1) - (a.valueScore ?? -1) ||
    (b.score ?? 0) - (a.score ?? 0) ||
    (a.effectiveBlended ?? 0) - (b.effectiveBlended ?? 0) ||
    String(a.modelId).localeCompare(String(b.modelId)));
  return { ...snapshot, health: { windowHours: health?.windowHours ?? null,
    fleetRate, fastestMs: health?.fleet?.fastestMs ?? null,
    computedAt: health?.computedAt ?? null }, models: ranked };
}
