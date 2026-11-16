import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import worker from '../src/worker.js';
import { collectAnalytics } from '../src/ui/analytics.js';

const db = new DatabaseSync(':memory:');
for (const file of ['0001_init.sql', '0002_auto_chain.sql', '0003_usage.sql']) {
  db.exec(readFileSync(new URL(`../migrations/${file}`, import.meta.url), 'utf8'));
}
const hour = Math.floor(Date.now() / 3600000);
db.prepare('INSERT INTO auto_chain_snapshot (id, hour, payload) VALUES (?, ?, ?)')
  .run('auto', hour, JSON.stringify({ models: [] }));
const routingDb = {
  async batch(statements) {
    return statements.map(statement => ({ results: db.prepare(statement.sql).all() }));
  },
  prepare(sql) {
    return { sql, bind(...values) {
      return {
        first: async () => db.prepare(sql).get(...values) ?? null,
        run: async () => db.prepare(sql).run(...values)
      };
    }};
  }
};
// Prevent the background market refresh from making a network request.
globalThis.fetch = async () => new Response(JSON.stringify({ data: [] }), {
  status: 200, headers: { 'Content-Type': 'application/json' }
});
const waits = [];
const req = new Request('https://test.local/v1/chat/completions', {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ model: 'infered/auto', messages: [{ role: 'user', content: 'no candidates' }] })
});
const response = await worker.fetch(req, { INFERHUB_API_KEY: 'test-key', ROUTING_DB: routingDb }, {
  waitUntil: p => waits.push(p)
});
await Promise.all(waits);
const row = db.prepare('SELECT requested_model, selected_model, ok, attempts FROM routing_decisions').get() ?? null;
db.exec(`UPDATE routing_decisions SET session_id='session-a';
  INSERT INTO routing_decisions (session_id, selected_model, ok, attempts) VALUES
    ('session-a', 'test/model', 1, 0),
    ('session-a', 'test/model', 0, 2),
    ('session-a', 'test/model', 0, 1),
    ('session-a', 'test/model', 1, 1),
    ('session-a', 'test/model', 1, 2);`);
const analytics = await collectAnalytics(routingDb);
console.log(JSON.stringify({ status: response.status, row, analytics }));
