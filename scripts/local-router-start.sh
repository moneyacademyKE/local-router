#!/bin/sh
set -eu

ROOT="${INFERED_ROOT:-$HOME/Projects/infered-local}"
LOG_DIR="${INFERED_STATE_ROOT:-$HOME/.opencrabs/state/infered-local}"
PERSIST_DIR="$LOG_DIR/wrangler-state"
LOG="$LOG_DIR/dev-server.log"
PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
export PATH

mkdir -p "$LOG_DIR"
HAS_LEDGER=false
for db in "$PERSIST_DIR"/v3/d1/miniflare-D1DatabaseObject/*.sqlite; do
  case "$db" in */metadata.sqlite) continue ;; esac
  if [ -f "$db" ] && [ "$(sqlite3 "$db" "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='routing_decisions'" 2>/dev/null || :)" = 1 ]; then
    HAS_LEDGER=true
    break
  fi
done
if [ "$HAS_LEDGER" = false ]; then
  printf '%s local-router boot: missing D1 state at %s; refusing to create an empty ledger\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$PERSIST_DIR" >> "$LOG"
  exit 1
fi
cd "$ROOT"
printf '%s local-router boot: applying migrations\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" >> "$LOG"
if ! bunx wrangler@4.163.0 d1 migrations apply infered-routing --local --persist-to "$PERSIST_DIR" --config wrangler.jsonc >> "$LOG" 2>&1; then
  printf '%s local-router boot: migration failed; refusing to start\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" >> "$LOG"
  exit 1
fi
printf '%s local-router boot: migrations complete; starting wrangler\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" >> "$LOG"
exec bunx wrangler@4.163.0 dev --port 8790 --test-scheduled --persist-to "$PERSIST_DIR"
