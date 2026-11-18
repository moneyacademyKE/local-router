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
#   4. read /v1/auto back     (say what the chain looks like, don't assume)
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

# The pin is literal on purpose: `bunx wrangler` with no version resolves the
# newest release (that is how 4.164.0 slipped into a deploy that claimed to be
# pinned). A variable invites drift; this line is the single place to bump.
wrangler() { bunx wrangler@4.163.0 "$@" --config "$CONFIG"; }

echo "== 1/4  remote D1 migrations (${DB_NAME}) =="
wrangler d1 migrations apply "$DB_NAME" --remote

echo "== 2/4  deploy (wrangler 4.163.0) =="
wrangler deploy

echo "== 3/4  bust the auto snapshot cache =="
wrangler d1 execute "$DB_NAME" --remote --command \
  "UPDATE auto_chain_snapshot SET hour = 0 WHERE id IN ('auto', 'auto-health', 'auto-quota');"

echo "== 4/4  read the chain back from ${BASE_URL} =="
curl -s -m 30 "${BASE_URL}/v1/auto" | head -c 700
echo

echo "deploy complete: migrations applied, worker live, snapshot rebuilding on next request"
