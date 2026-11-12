-- 0003_usage.sql — token/cost accounting on every routing decision (2026-11-12).
-- The ledger recorded success/latency but no tokens, so actual spend was
-- unmeasurable while cache economics drove ranking. Usage now flows from the
-- upstream usage frame (trailing SSE frame or embedded JSON) through the
-- relay capture into these columns; cost prefers the seller-reported number,
-- falling back to the spot-price computation from the serving candidate.
-- Applied locally (dev door) and remotely (fallback door). Idempotent-safe
-- to re-run only on a fresh DB; ALTER TABLE ADD COLUMN on an existing column
-- errors, so D1 migrations tracking (d1_migrations table) prevents re-runs.
ALTER TABLE routing_decisions ADD COLUMN prompt_tokens INTEGER;
ALTER TABLE routing_decisions ADD COLUMN completion_tokens INTEGER;
ALTER TABLE routing_decisions ADD COLUMN cached_tokens INTEGER;
ALTER TABLE routing_decisions ADD COLUMN cost_usd REAL;
