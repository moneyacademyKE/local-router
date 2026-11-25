#!/usr/bin/env bash
# Cloud deploy — one command, pinned, with the steps that must not be forgotten.
#
# Why this exists: on 2026-11-18 `bunx wrangler deploy` reported success and
# shipped a worker whose new code writes to `routing_attempts`, while remote D1
# was still at migration 0003. Every attempt insert threw, silently. The
# snapshot cache was stale on top of that, still advertising the old gates. A
# green deploy is not a correct deploy: the ordering below is the deploy.
#
#   1. remote D1 migrations   (code that writes new tables needs them first)
#   2. deploy                 (pinned wrangler; bunx does not honour a pin)
#   3. bust the snapshot rows (the hourly cache short-circuits otherwise)
#   4. read every chain back  (say what each chain looks like, don't assume)
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

# The market chains are read from the code and never listed here. A chain added
# to src/router/chains.js brings its own D1 rows (infered/auto-budget arrived
# 2026-11-25 with two), and must not need a second edit in this script to have
# them busted and its order read back.
chains_js="import { MARKET_CHAINS } from './src/router/chains.js';"
CHAINS="$(node --input-type=module -e "${chains_js} console.log(Object.keys(MARKET_CHAINS).map(k => k.replace('infered/', '')).join(' '))")"
ROWS="$(node --input-type=module -e "${chains_js} const ids = ['auto-health', ...Object.values(MARKET_CHAINS).flatMap(c => [c.rowId, c.quotaRowId])]; console.log(ids.map(id => \"'\" + id + \"'\").join(', '))")"

echo "== 1/4  remote D1 migrations (${DB_NAME}) =="
wrangler d1 migrations apply "$DB_NAME" --remote

echo "== 2/4  deploy (wrangler 4.163.0) =="
wrangler deploy

echo "== 3/4  bust the auto snapshot cache (rows: ${ROWS}) =="
wrangler d1 execute "$DB_NAME" --remote --command \
  "UPDATE auto_chain_snapshot SET hour = 0 WHERE id IN (${ROWS});"

echo "== 4/4  read every market chain back from ${BASE_URL} =="
for chain in $CHAINS; do
  echo "-- /v1/${chain}"
  curl -s -m 30 "${BASE_URL}/v1/${chain}" | jq -c \
    '{declared, served: [.models[].modelId], excluded: [.excluded[] | (.modelId + ":" + .reason)], bid: .diagnostics.bid, orderSource: .diagnostics.orderSource}'
done
echo

echo "deploy complete: migrations applied, worker live, snapshot rebuilding on next request"
