-- 0004_attempts.sql — per-attempt routing outcomes (2026-11-18).
-- routing_decisions records one row per REQUEST and names only the candidate
-- that failed LAST. That made per-model reliability unmeasurable: whichever
-- model sat at the tail absorbed the failure count, and every earlier failed
-- attempt was invisible. Ranking by measured reliability needs the facts,
-- not the summary — one row per upstream attempt, written in a single batch
-- with the decision row.
CREATE TABLE IF NOT EXISTS routing_attempts (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts TEXT NOT NULL DEFAULT (datetime('now')),
  requested_model TEXT,
  model TEXT NOT NULL,
  provider TEXT,
  ok INTEGER NOT NULL,
  status INTEGER,
  latency_ms INTEGER,
  error TEXT
);
CREATE INDEX IF NOT EXISTS idx_routing_attempts_ts ON routing_attempts (ts);
CREATE INDEX IF NOT EXISTS idx_routing_attempts_model_ts ON routing_attempts (model, ts);

-- Backfill: only the SERVED attempt is attributable in the pre-0004 ledger
-- (routing_decisions keeps the last error only, so per-model failures from
-- history cannot be reconstructed — inventing them would repeat the very
-- mis-attribution this table exists to end). Served rows are facts: model,
-- latency and timestamp are all recorded. Reliability therefore starts as a
-- lower bound and sharpens as live attempts land; speed and cost are correct
-- immediately, which is enough to rank a chain today.
INSERT INTO routing_attempts (ts, requested_model, model, provider, ok, status, latency_ms, error)
SELECT ts, requested_model, selected_model, selected_provider, 1, 200, latency_ms, NULL
FROM routing_decisions
WHERE ok = 1 AND selected_model IS NOT NULL AND selected_model <> 'unserved';
