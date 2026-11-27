#!/usr/bin/env bash
# Cloud deploy — one command, pinned, with the steps that must not be forgotten.
#
# Why this exists: on 2026-11-18 `bunx wrangler deploy` reported success and
# shipped a worker whose new code writes to `routing_attempts`, while remote D1
# was still at migration 0003. Every attempt insert threw, silently. The
# snapshot cache was stale on top of that, still advertising the old gates. A
# green deploy is not a correct deploy: the ordering below is the deploy.
#
#   1. remote D1 migrations    (code that writes new tables needs them first)
#   2. deploy                  (pinned wrangler; bunx does not honour a pin)
#   3. wait for that version   (the previous one must not answer the next step)
#   4. bust the snapshot rows  (the hourly cache short-circuits otherwise)
#   5. read every chain back   (require what this build fixes for each chain)
#
# Credentials come from the environment and are never printed:
#   CLOUDFLARE_ACCOUNT_ID + CLOUDFLARE_API_KEY (or CLOUDFLARE_API_TOKEN)
#
# Usage: ./scripts/deploy.sh [worker-base-url]
set -euo pipefail

DB_NAME="infered-routing"
CONFIG="wrangler.jsonc"
BASE_URL="${1:-https://infered-virtual-router.moneyacad.workers.dev}"

cd "$(dirname "$0")/.."

if [ -z "${CLOUDFLARE_API_KEY:-}" ] && [ -z "${CLOUDFLARE_API_TOKEN:-}" ]; then
  echo "refusing: set CLOUDFLARE_API_TOKEN (preferred) or CLOUDFLARE_API_KEY + CLOUDFLARE_ACCOUNT_ID" >&2
  exit 2
fi
# Checked before anything is deployed: a missing tool must not fail the
# read-back of a deploy that already went out.
for tool in bunx node jq; do
  command -v "$tool" >/dev/null || { echo "refusing: ${tool} is required" >&2; exit 2; }
done

# The pin is literal on purpose: `bunx wrangler` with no version resolves the
# newest release (that is how 4.164.0 slipped into a deploy that claimed to be
# pinned). A variable invites drift; this line is the single place to bump.
wrangler() { bunx wrangler@4.163.0 "$@" --config "$CONFIG"; }

# The market chains and the product list are read from the code and never
# listed here. A chain added to src/router/chains.js brings its own D1 rows
# (infered/auto-budget arrived 2026-11-25 with two), and must not need a second
# edit in this script to have them busted and its order read back.
src_js="import { MARKET_CHAINS, chainParameters } from './src/router/chains.js'; import { VIRTUAL_ALIASES } from './src/router/catalog.js';"
CHAINS="$(node --input-type=module -e "${src_js} console.log(Object.keys(MARKET_CHAINS).map(k => k.replace('infered/', '')).join(' '))")"
ROWS="$(node --input-type=module -e "${src_js} const ids = ['auto-health', ...Object.values(MARKET_CHAINS).flatMap(c => [c.rowId, c.quotaRowId])]; console.log(ids.map(id => \"'\" + id + \"'\").join(', '))")"
PRODUCTS="$(node --input-type=module -e "${src_js} console.log(Object.keys(VIRTUAL_ALIASES).sort().join(' '))")"
# What a chain fixes, read from the code: a declared chain its member list, a
# discovered one its parameters (its candidates come from the live market).
fixed_by() { node --input-type=module -e "${src_js} const c = MARKET_CHAINS['infered/$1']; console.log(JSON.stringify({declared: c.members ? c.members.map(m => m.id) : null, parameters: chainParameters(c)}))"; }

echo "== 1/5  remote D1 migrations (${DB_NAME}) =="
wrangler d1 migrations apply "$DB_NAME" --remote

echo "== 2/5  deploy (wrangler 4.163.0) =="
wrangler deploy

echo "== 3/5  wait for this version to answer ${BASE_URL} =="
# A fresh version takes a few seconds to reach every edge, and busting the
# cache before then is not a no-op. On 2026-11-25 the request after the bust
# was answered by the previous version: it rebuilt the infered/auto row with
# ITS chain and left glm-5.4 unevaluated for the rest of the hour.
sleep 10
for attempt in $(seq 1 20); do
  LIVE="$(curl -s -m 15 "${BASE_URL}/v1/models" | jq -r '[.data[].id] | sort | join(" ")' 2>/dev/null || true)"
  [ "${LIVE}" = "${PRODUCTS}" ] && break
  sleep 3
done
[ "${LIVE}" = "${PRODUCTS}" ] || {
  echo "deploy check failed: ${BASE_URL}/v1/models lists [${LIVE}], this build declares [${PRODUCTS}]" >&2
  exit 1
}

echo "== 4/5  bust the auto snapshot cache (rows: ${ROWS}) =="
wrangler d1 execute "$DB_NAME" --remote --command \
  "UPDATE auto_chain_snapshot SET hour = 0 WHERE id IN (${ROWS});"

echo "== 5/5  read every market chain back from ${BASE_URL} =="
for chain in ${CHAINS}; do
  want="$(fixed_by "${chain}")"
  # The first request after the bust rebuilds the row; a 503 or an older
  # isolate answers without what the chain fixes, so give it a few tries.
  for attempt in $(seq 1 10); do
    body="$(curl -s -m 30 "${BASE_URL}/v1/${chain}")"
    got="$(printf '%s' "${body}" | jq -c '{declared, parameters}' 2>/dev/null || true)"
    [ "${got}" = "${want}" ] && break
    sleep 3
  done
  [ "${got}" = "${want}" ] || {
    echo "deploy check failed: /v1/${chain} does not serve the chain this build fixes (${want})" >&2
    exit 1
  }
  echo "-- /v1/${chain}"
  printf '%s' "${body}" | jq -c \
    '{declared, parameters, served: [.models[].modelId], unscored: .diagnostics.unscored.count, excluded: [.excluded[] | (.modelId + ":" + .reason)], bid: .diagnostics.bid, orderSource: .diagnostics.orderSource}'
done
echo

echo "deploy complete: migrations applied, worker live, snapshot rebuilding on next request"
