-- Shared hourly intelligence-ranked chain; old snapshots cannot overwrite newer ones.
CREATE TABLE IF NOT EXISTS auto_chain_snapshot (
  id TEXT PRIMARY KEY,
  hour INTEGER NOT NULL,
  payload TEXT NOT NULL
);
