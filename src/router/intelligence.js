/** Intelligence scores for the value chain, keyed by MODEL FAMILY.
 *
 * A score belongs to a model, not to a seller's path to it: `cx/gpt-6.1-terra`
 * and `oai/gpt-6.1-terra` are one model at two vendors. A feed id and a board
 * entry are therefore both reduced to a family key — the model's own name,
 * normalised — and joined by EXACT match on that key. Never a fuzzy match: a
 * wrong score is a lie about intelligence, while a missing one only leaves a
 * model out, and the chain's diagnostics count it.
 *
 * Two sources, in this order:
 *   live     — the Artificial Analysis Intelligence Index as published daily
 *              on models.fru.dev (no key). Read at most twice a day into its
 *              own D1 row and served stale when the site is down.
 *   recorded — the scores this repo recorded on 2026-11-10/11, for a cold
 *              start with no row and no board.
 *
 * Nothing here is estimated. A model on neither list has no score, and a
 * chain that ranks intelligence per dollar cannot rank it.
 */

import { TABLE } from "./chains.js";

export const INTELLIGENCE_BOARD_URL = "https://models.fru.dev/api/boards/aa-intelligence";
export const INTELLIGENCE_ROW_ID = "auto-intelligence";
// The board is captured once a day (04:30 UTC); reading it twice a day finds
// each capture without hammering a free endpoint.
export const INTELLIGENCE_PERIOD_MS = 12 * 60 * 60 * 1000;

// Artificial Analysis Intelligence Index, the snapshot of 2026-11-10/11 the
// auto chain ranked by until the owner declared its sequence (2026-11-21) —
// and again since both chains became discovered (2026-11-27). Kept as
// the vendor ids they were recorded under; the family keys are derived below
// by the same normaliser every other score goes through.
const RECORDED = [
  ["ag/gemini-4-carbon", 60],
  ["cx/gpt-6-astra", 57],
  ["oai/gpt-6.1-sol", 55],
  ["cx/gpt-6.1-terra", 51],
  ["oai/gpt-6.1-terra", 51],
  ["ali/kimi-k3.1-pro", 46],
  ["grok/grok-4.7", 35.5],
  ["ali/glm-5.3", 32]
];

// A maker name some sellers write INSIDE the model segment (`mv/zai-glm-5-3`),
// where the usual form is a path segment of its own (`zai/glm-5.3`). Exact
// tokens only — a general "drop the first word" rule would let one model
// borrow another's score.
const MAKER_PREFIXES = ["zai", "z-ai"];

function normalise(name) {
  return String(name).toLowerCase().trim()
    .replace(/[ _-]\d{4}$/, "") // dated build suffix: -1017, " 0813"
    .replace(/(\d)p(\d)/g, "$1$2") // glm-5p3 is glm-5.3
    .replace(/[^a-z0-9]/g, "");
}

/** Every family key a feed id may be known by, most specific first. */
export function familyKeys(feedId) {
  const parts = String(feedId).toLowerCase().trim().split("/").filter(Boolean);
  const name = parts[parts.length - 1] || "";
  const keys = [normalise(name)];
  for (const maker of MAKER_PREFIXES) {
    if (name.startsWith(`${maker}-`)) keys.push(normalise(name.slice(maker.length + 1)));
  }
  return keys.filter(Boolean);
}

function indexScores(entries) {
  const index = TABLE({});
  for (const [name, score] of entries) {
    // A score with no name behind it has no family: it is dropped rather
    // than filed under the key "undefined".
    if (typeof name !== "string" || !name.trim() || !Number.isFinite(score)) continue;
    const key = familyKeys(name)[0];
    if (key && index[key] === undefined) index[key] = score;
  }
  return index;
}

export const RECORDED_INTELLIGENCE = indexScores(RECORDED);

/** Validates a board payload into a lookup. A number is only ever read from
 * `score`; a malformed or empty board is `null`, so a caller falls back to
 * what it has rather than ranking on a guess. */
export function intelligenceFromBoard(payload) {
  const ranking = payload?.ranking;
  if (!Array.isArray(ranking) || !ranking.length) return null;
  const scored = ranking.filter(r => Number.isFinite(r?.score));
  const index = indexScores(scored.flatMap(r => [[r.model, r.score], [r.name, r.score]]));
  if (!Object.keys(index).length) return null;
  return {
    source: INTELLIGENCE_BOARD_URL,
    publishedOn: payload.board?.publishedOn ?? null,
    capturedAt: payload.board?.capturedAt ?? null,
    models: scored.length,
    verified: scored.filter(r => r?.verified === true).length,
    index
  };
}

/** The score of one feed entry: the live board's where it has one, else the
 * recorded one. `null` when neither source knows the family. */
export function intelligenceFor(feedId, { board = null, recorded = RECORDED_INTELLIGENCE } = {}) {
  const keys = familyKeys(feedId);
  for (const key of keys) {
    if (board?.index && board.index[key] !== undefined) return { score: board.index[key], source: "board" };
  }
  for (const key of keys) {
    if (recorded[key] !== undefined) return { score: recorded[key], source: "recorded" };
  }
  return null;
}

/** What a report may say about where a chain's intelligence came from. The
 * lookup itself never leaves the worker. */
export function intelligenceProvenance(board) {
  if (!board) return { source: "recorded", recordedOn: "2026-11-10/11", stale: false };
  return { source: board.source, publishedOn: board.publishedOn, capturedAt: board.capturedAt,
    models: board.models, verified: board.verified, stale: Boolean(board.stale) };
}

/** The current intelligence board: this period's D1 row, else a fresh read
 * that replaces it. A failed read serves the last board read (`stale`), and
 * with no board at all returns `null` — the caller then ranks by the recorded
 * scores, so a third-party outage can empty nobody's chain. Persistence is
 * best-effort; the payload is the truth either way. */
export async function loadIntelligence(env, fetchFn = fetch, now = Date.now()) {
  const db = env?.ROUTING_DB;
  const period = Math.floor(now / INTELLIGENCE_PERIOD_MS);
  let cached = null;
  if (db) {
    try {
      const row = await db.prepare(
        `SELECT hour, payload FROM auto_chain_snapshot WHERE id = '${INTELLIGENCE_ROW_ID}'`
      ).bind().first();
      cached = intelligenceFromBoard(JSON.parse(row?.payload));
      if (cached && Number(row.hour) === period) return cached;
    } catch { /* an unreadable row is a cold start, never an error */ }
  }
  try {
    const response = await fetchFn(INTELLIGENCE_BOARD_URL,
      { headers: { Accept: "application/json" }, signal: AbortSignal.timeout(8000) });
    if (!response.ok) throw new Error(`Intelligence board refresh failed (${response.status})`);
    const text = await response.text();
    const fresh = intelligenceFromBoard(JSON.parse(text));
    if (!fresh) throw new Error("Intelligence board is empty or malformed");
    if (db) {
      try {
        await db.prepare(
          `INSERT INTO auto_chain_snapshot (id, hour, payload) VALUES ('${INTELLIGENCE_ROW_ID}', ?1, ?2) ` +
          "ON CONFLICT(id) DO UPDATE SET hour=excluded.hour, payload=excluded.payload " +
          "WHERE excluded.hour > auto_chain_snapshot.hour"
        ).bind(period, text).run();
      } catch { /* best-effort: the next rebuild simply reads again */ }
    }
    return fresh;
  } catch {
    return cached ? { ...cached, stale: true } : null;
  }
}
