import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import worker from '../src/worker.js';

const db = new DatabaseSync(':memory:');
for (const file of ['0001_init.sql', '0002_auto_chain.sql', '0003_usage.sql']) {
  db.exec(readFileSync(new URL(`../migrations/${file}`, import.meta.url), 'utf8'));
}
const hour = Math.floor(Date.now() / 3600000);
db.prepare('INSERT INTO auto_chain_snapshot (id, hour, payload) VALUES (?, ?, ?)')
  .run('auto', hour, JSON.stringify({ models: [] }));
const routingDb = {
  prepare(sql) {
    return { bind(...values) {
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
console.log(JSON.stringify({ status: response.status, row }));
