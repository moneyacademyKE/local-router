import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import worker from '../src/worker.js';

const scenario = process.argv[2];
const db = new DatabaseSync(':memory:');
for (const file of ['0001_init.sql', '0002_auto_chain.sql', '0003_usage.sql']) {
  db.exec(readFileSync(new URL(`../migrations/${file}`, import.meta.url), 'utf8'));
}
let clock = 1700000000000;
Date.now = () => clock;
const models = [
  { modelId: 'cx/gpt-6.2-sol', score: 55, prompt: 0.01, completion: 0.1 },
  { modelId: 'ali/qwen4-max', score: 52, prompt: 0.01, completion: 0.1 }
];
db.prepare('INSERT INTO auto_chain_snapshot (id, hour, payload) VALUES (?, ?, ?)')
  .run('auto', Math.floor(clock / 300000), JSON.stringify({ models }));
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
let calls = 0;
globalThis.fetch = async url => {
  if (String(url).endsWith('/models')) {
    return new Response(JSON.stringify({ data: [] }), { status: 200 });
  }
  calls++;
  clock += calls === 1 ? 25000 : 17590;
  if (calls === 1 || scenario === 'failure') return new Response('unavailable', { status: 503 });
  if (scenario === 'stream') {
    return new Response('data: {"choices":[{"delta":{"content":"OK"}}]}\n\ndata: [DONE]\n\n', {
      status: 200, headers: { 'Content-Type': 'text/event-stream' }
    });
  }
  return Response.json({ choices: [{ message: { role: 'assistant', content: 'OK' } }],
    usage: { prompt_tokens: 10, completion_tokens: 2 } });
};
const waits = [];
const request = new Request('https://test.local/v1/chat/completions', {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ model: 'infered/auto', stream: scenario === 'stream',
    messages: [{ role: 'user', content: 'latency contract' }] })
});
const response = await worker.fetch(request,
  { INFERHUB_API_KEY: 'fixture-key', ROUTING_DB: routingDb },
  { waitUntil: promise => waits.push(promise) });
const body = await response.text();
await Promise.all(waits);
const row = db.prepare('SELECT selected_model, ok, attempts, latency_ms FROM routing_decisions').get() ?? null;
console.log(JSON.stringify({ status: response.status, row, calls, hasDone: body.includes('[DONE]') }));
