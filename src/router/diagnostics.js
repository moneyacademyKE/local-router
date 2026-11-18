/**
 * Diagnostics — "why is the chain this short / this slow / this dead?"
 *
 * One pure data function instead of the three-SQL interrogation the owner had
 * to run by hand every time. It answers, in one payload:
 *
 *   chain      — served order, gate exclusions, and the `chainDiagnostics`
 *                warnings (thin books, the bid that would unlock an over_cap)
 *   health     — measured per-model health: attempts, ok rate, latency,
 *                last status, cooldown state, failure classes
 *   failures   — 24h failure classes per model+status, plus the last 10 raw
 *                failures with their upstream error strings
 *
 * Deliberately returns data, not a Response: the caller owns auth and
 * serialisation, so this stays testable without a request object.
 */

import { getAutoSnapshot } from "./auto.js";
import { loadHealth } from "./health.js";
import { isCircuitOpen } from "./metrics.js";

const FAILURE_WINDOW = "-24 hours";
const RECENT_LIMIT = 10;
// The auto chain's provider id, as pareto.js stamps it on every candidate.
const AUTO_PROVIDER_ID = "inferhub-auto";

async function rows(db, sql) {
  try {
    const res = await db.prepare(sql).bind().all();
    return res?.results || [];
  } catch {
    return [];
  }
}

/** Aggregate 24h failures into (model, status) classes, newest first. */
export async function failureClasses(db) {
  return rows(
    db,
    "SELECT model, status, COUNT(*) AS n, MAX(ts) AS last_at FROM routing_attempts " +
    `WHERE ok = 0 AND ts >= datetime('now','${FAILURE_WINDOW}') ` +
    "GROUP BY model, status ORDER BY n DESC"
  );
}

/** The last few raw failures — the actual upstream error strings. */
export async function recentFailures(db) {
  return rows(
    db,
    "SELECT ts, requested_model, model, status, latency_ms, error FROM routing_attempts " +
    `WHERE ok = 0 AND ts >= datetime('now','${FAILURE_WINDOW}') ` +
    `ORDER BY id DESC LIMIT ${RECENT_LIMIT}`
  );
}

/**
 * Circuit state per chain member, plus the order a request will actually try.
 *
 * `pareto.js` sorts a tripped circuit to the BACK of the chain, so the served
 * order and the attempted order diverge exactly when a member is failing —
 * which is the moment someone is reading diagnostics. Naming both is the
 * difference between "the head model answered" and "the head model was never
 * tried, and here is why".
 */
export function circuitState(models, metricsStore) {
  const tripped = [];
  for (const model of models) {
    try {
      if (isCircuitOpen(metricsStore, AUTO_PROVIDER_ID, model?.modelId)) tripped.push(model.modelId);
    } catch {
      // No metrics store (or a malformed one): state is unknown, never guessed.
    }
  }
  const trippedSet = new Set(tripped);
  const attemptedOrder = models
    .filter(m => !trippedSet.has(m?.modelId))
    .map(m => m?.modelId)
    .concat(tripped);
  return { providerId: AUTO_PROVIDER_ID, tripped, attemptedOrder };
}

/**
 * Build the whole diagnostics payload.
 * `env` needs ROUTING_DB; `now` is injectable so tests pin the health window;
 * `metricsStore` is the in-memory circuit/latency store the request path uses.
 */
export async function buildDiagnostics(env, db, now = Date.now(), metricsStore = null) {
  let chain = null;
  let chainError = null;
  try {
    // `now` is forwarded so the chain's hour window and the diagnostics
    // timestamp describe the same moment — otherwise a cached snapshot can
    // read as stale in a payload that claims to be current.
    chain = await getAutoSnapshot(env, undefined, now);
  } catch (e) {
    chainError = e?.message || "auto snapshot unavailable";
  }

  const health = await loadHealth(db, now);
  const [classes, recent] = await Promise.all([failureClasses(db), recentFailures(db)]);

  return {
    timestamp: now,
    chain: chain
      ? {
        order: (chain.models || []).map(m => m.modelId),
        models: chain.models || [],
        excluded: chain.excluded || [],
        diagnostics: chain.diagnostics || null,
        stale: chain.stale === true
      }
      : { error: chainError },
    circuits: chain ? circuitState(chain.models || [], metricsStore) : null,
    health,
    failures: { classes, recent }
  };
}
