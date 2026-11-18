#!/usr/bin/env bash
# Read the chain diagnostics from either door without hand-curling a Bearer token.
#
#   ./scripts/diag.sh            # local door (127.0.0.1:8790)
#   ./scripts/diag.sh cloud      # cloud door (infered-virtual-router)
#   ./scripts/diag.sh cloud | jq '.chain.order'
#
# The admin plane is token-gated by ROUTER_AUTH_TOKEN, which lives in .dev.vars
# locally and as a Worker secret in cloud. This script reads the same local copy
# of the secret (never the value in your shell history) and falls back to
# $ROUTER_AUTH_TOKEN when that file is absent, so CI can supply it by env.
#
# Exit codes: 0 with the JSON body on stdout, 1 when the token is missing,
# 2 when the door answers non-200 (the body still prints so the error is legible).
set -euo pipefail

TOKEN_FILE="${ROUTER_TOKEN_FILE:-$HOME/.opencrabs/state/infered-local/router-admin-token}"
BASE_LOCAL="${ROUTER_LOCAL_URL:-http://127.0.0.1:8790}"
BASE_CLOUD="${ROUTER_CLOUD_URL:-https://infered-virtual-router.moneyacad.workers.dev}"

case "${1:-local}" in
  local) base="$BASE_LOCAL" ;;
  cloud) base="$BASE_CLOUD" ;;
  -h|--help|help) sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  *) echo "unknown door: $1 (use 'local' or 'cloud')" >&2; exit 1 ;;
esac

token="${ROUTER_AUTH_TOKEN:-}"
if [ -z "$token" ] && [ -r "$TOKEN_FILE" ]; then
  token="$(tr -d '\n' < "$TOKEN_FILE")"
fi
if [ -z "$token" ]; then
  echo "no admin token: set ROUTER_AUTH_TOKEN or write $TOKEN_FILE" >&2
  exit 1
fi

body="$(curl -sS -m "${DIAG_TIMEOUT:-60}" -w '\n%{http_code}' \
  -H "Authorization: Bearer ${token}" "${base}/v1/diagnostics")"
status="${body##*$'\n'}"
payload="${body%$'\n'*}"
printf '%s\n' "$payload"
if [ "$status" != "200" ]; then
  echo "--- /v1/diagnostics on ${base} answered ${status}" >&2
  exit 2
fi
