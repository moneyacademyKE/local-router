// Regression: an ok=1 decision with no model used to bind NULL into
// routing_decisions.selected_model, which D1 rejected outright — the row was
// lost and the ledger undercounted served traffic (77 such losses in
// dev-server.log, Nov 2026). The row must land, visibly, as 'unspecified'.
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { recordRoutingAnalytics } from '../src/router/telemetry.js';

const db = new DatabaseSync(':memory:');
for (const file of ['0001_init.sql', '0003_usage.sql']) {
  db.exec(readFileSync(new URL(`../migrations/${file}`, import.meta.url), 'utf8'));
}

const routingDb = {
  prepare(sql) {
    return {
      bind(...values) {
        return { run: async () => db.prepare(sql).run(...values) };
      }
    };
  }
};

await recordRoutingAnalytics({ ROUTING_DB: routingDb }, {
  ok: true,
  requestedModel: 'infered/auto',
  attempts: 1,
  latencyMs: 42
});

const row = db.prepare('SELECT requested_model, selected_model, ok FROM routing_decisions').get() ?? null;
console.log(JSON.stringify({ row }));
