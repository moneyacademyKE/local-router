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

const FAILURE_WINDOW = "-24 hours";
const RECENT_LIMIT = 10;

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
 * Build the whole diagnostics payload.
 * `env` needs ROUTING_DB; `now` is injectable so tests pin the health window.
 */
export async function buildDiagnostics(env, db, now = Date.now()) {
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
    health,
    failures: { classes, recent }
  };
}
