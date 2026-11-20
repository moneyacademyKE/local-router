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
// Recency beats the average: a member that answered all day but failed its
// last attempt two minutes ago is not the head of the chain. Inside this
// cooldown it is "cooling" — ranked after everything else that might answer,
// ahead of the members already proven dead. Derived from measurement (the
// attempt ledger's tail), never from a hand-set flag.
export const HEALTH_COOLDOWN_MS = 15 * 60 * 1000;
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
 * (a timeout's latency measures the timeout, not the model).
 *
 * Beyond the ratio it keeps the three facts a ratio erases:
 *   statusCounts — 503 no_capacity, 403 model_disabled, 429 quota and network
 *                  timeouts are four different diseases that all read "fail";
 *   lastStatus/lastError — the newest attempt's verdict, so the diagnosis is
 *                  current rather than averaged into invisibility;
 *   streak — consecutive failures at the tail, which is what makes the
 *                  cooldown tier possible at all. */
export async function aggregateHealth(db, now = Date.now()) {
  const window = `-${HEALTH_WINDOW_HOURS} hours`;
  const res = await db.prepare(
    "SELECT model, SUM(ok) AS ok, SUM(1 - ok) AS fail, " +
    "CAST(AVG(CASE WHEN ok = 1 THEN latency_ms END) AS INTEGER) AS avg_ms, " +
    "CAST(strftime('%s', MAX(CASE WHEN ok = 1 THEN ts END)) AS INTEGER) AS last_ok_s, " +
    "CAST(strftime('%s', MAX(CASE WHEN ok = 0 THEN ts END)) AS INTEGER) AS last_fail_s " +
    "FROM routing_attempts WHERE ts >= datetime('now', ?1) GROUP BY model"
  ).bind(window).all();
  const rows = res?.results || [];
  const models = {};
  let ok = 0, fail = 0;
  for (const r of rows) {
    const mOk = Number(r.ok) || 0;
    const mFail = Number(r.fail) || 0;
    ok += mOk; fail += mFail;
    const lastOkS = Number(r.last_ok_s);
    const lastFailS = Number(r.last_fail_s);
    models[r.model] = {
      ok: mOk, fail: mFail,
      rate: round(mOk / Math.max(1, mOk + mFail)),
      avgMs: Number.isFinite(r.avg_ms) && r.avg_ms > 0 ? r.avg_ms : null,
      lastOkAt: lastOkS > 0 ? lastOkS * 1000 : null,
      lastFailAt: lastFailS > 0 ? lastFailS * 1000 : null,
      statusCounts: {}, lastStatus: null, lastError: null, streak: 0
    };
  }
  // Failure classes per model: the same "fail" split by what actually happened.
  const classes = await db.prepare(
    "SELECT model, status, COUNT(*) AS n FROM routing_attempts " +
    "WHERE ts >= datetime('now', ?1) GROUP BY model, status"
  ).bind(window).all().catch(() => ({ results: [] }));
  const fleetClasses = {};
  for (const c of classes?.results || []) {
    const label = c.status === null || c.status === undefined ? "network" : String(c.status);
    fleetClasses[label] = (fleetClasses[label] || 0) + (Number(c.n) || 0);
    const m = models[c.model];
    if (m) m.statusCounts[label] = Number(c.n) || 0;
  }
  // Newest attempts per model (id order — ts is second-granular, id is not).
  const tail = await db.prepare(
    "SELECT model, ok, status, error, rn FROM (SELECT model, ok, status, error, " +
    "ROW_NUMBER() OVER (PARTITION BY model ORDER BY id DESC) AS rn " +
    "FROM routing_attempts WHERE ts >= datetime('now', ?1)) WHERE rn <= 6"
  ).bind(window).all().catch(() => ({ results: [] }));
  const tails = new Map();
  for (const t of tail?.results || []) {
    const rn = Number(t.rn);
    if (!tails.has(t.model)) tails.set(t.model, []);
    const list = tails.get(t.model);
    list.push({ rn, ok: Number(t.ok), status: t.status, error: t.error });
  }
  for (const [model, list] of tails) {
    const m = models[model];
    if (!m) continue;
    list.sort((a, b) => a.rn - b.rn);
    const newest = list[0];
    m.lastStatus = newest.status === null || newest.status === undefined
      ? null : Number(newest.status);
    m.lastError = clip(newest.error, 120);
    let streak = 0;
    while (streak < list.length && list[streak].ok === 0) streak += 1;
    m.streak = streak;
  }
  const fastest = Object.values(models)
    .map(m => m.avgMs).filter(v => Number.isFinite(v) && v > 0);
  return {
    windowHours: HEALTH_WINDOW_HOURS,
    computedAt: now,
    fleet: {
      ok, fail,
      rate: ok + fail > 0 ? round(ok / (ok + fail)) : null,
      fastestMs: fastest.length ? Math.min(...fastest) : null,
      failureClasses: fleetClasses
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
 * value in five tiers, by expected value of the NEXT attempt:
 *   0 proven     — tried, answered, and its newest attempt answered
 *   1 thin       — same, but only ONE seller sits inside the bid. It answered,
 *                  so it is real; its supply is one seller deep, so it is not
 *                  the head. (It used to be excluded outright: a ban on a
 *                  belief the ledger can measure. A thin book that cannot
 *                  answer now demotes itself after three attempts.)
 *   2 unproven   — never tried, or tried too few times to judge: neutral
 *                  weights, ranked after everything that has proven it works
 *   3 cooling    — its newest attempt failed inside HEALTH_COOLDOWN_MS. The
 *                  24h average still looks fine; the last minute does not.
 *   4 disproven  — tried HEALTH_MIN_ATTEMPTS+ times and never once answered
 *                  (speed 0, value 0)
 * The tiers exist so a new model still gets a chance once the proven members
 * are exhausted, without letting an untested model jump a measured workhorse.
 * A single failure never lands a model in "disproven" — evidence floor first.
 *
 * `reorder` (owner directive 2026-11-21): the auto chain's membership AND
 * sequence are declared, so health annotates its members without re-sorting
 * them. The tiers still describe each member honestly (a cooling model still
 * says so); they just no longer decide who is first. Callers that own a
 * measured ordering — the cascade products — keep the default `reorder: true`.
 */
export function applyValueRanking(snapshot, health, { reorder = true } = {}) {
  const models = Array.isArray(snapshot?.models) ? snapshot.models : [];
  if (!models.length) return snapshot;
  const fleetRate = Number.isFinite(health?.fleet?.rate) ? health.fleet.rate : null;
  // Cooldown is judged against the aggregation instant, not wall-clock now:
  // the payload is cached for up to HEALTH_FRESH_MS, and a cached verdict must
  // stay reproducible for whoever reads it.
  const ref = Number.isFinite(health?.computedAt) ? health.computedAt : Date.now();
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
    // A declared member carries no intelligence score: valueScore is then
    // UNKNOWN (null), not zero. Zero is a measurement ("worth nothing per
    // dollar"); null is the absence of one, and the two must not read alike.
    const score = Number.isFinite(m.score) ? m.score : null;
    const thin = Number(m.inCapDepth) === 1;
    const cooling = Boolean(h) && Number.isFinite(h.lastFailAt) &&
      !(Number.isFinite(h.lastOkAt) && h.lastOkAt > h.lastFailAt) &&
      ref - h.lastFailAt < HEALTH_COOLDOWN_MS;
    return { ...m, thinBook: thin, reliability: round(reliability), speed: round(speed),
      valueScore: score === null ? null : round((score * reliability * speed) / cost),
      measuredAttempts: attempts, cooling,
      valueTier: attempts === 0 ? "unproven"
        : cooling ? "cooling"
          : ok > 0 ? (thin ? "thin" : "proven")
            : (attempts >= HEALTH_MIN_ATTEMPTS ? "disproven" : "unproven") };
  });
  const tierRank = { proven: 0, thin: 1, unproven: 2, cooling: 3, disproven: 4 };
  if (reorder) {
    ranked.sort((a, b) => tierRank[a.valueTier] - tierRank[b.valueTier] ||
      (b.valueScore ?? -1) - (a.valueScore ?? -1) ||
      (b.score ?? 0) - (a.score ?? 0) ||
      (a.effectiveBlended ?? 0) - (b.effectiveBlended ?? 0) ||
      String(a.modelId).localeCompare(String(b.modelId)));
  }
  return { ...snapshot, health: { windowHours: health?.windowHours ?? null,
    fleetRate, fastestMs: health?.fleet?.fastestMs ?? null,
    computedAt: health?.computedAt ?? null, cooldownMs: HEALTH_COOLDOWN_MS,
    failureClasses: health?.fleet?.failureClasses ?? null }, models: ranked };
}
