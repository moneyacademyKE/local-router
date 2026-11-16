#!/bin/sh
set -eu

ROOT="/Users/moe/Desktop/infered"
LOG_DIR="/Users/moe/.opencrabs/state/infered-local"
LOG="$LOG_DIR/dev-server.log"
PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
export PATH

mkdir -p "$LOG_DIR"
cd "$ROOT"
printf '%s local-router boot: applying migrations\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" >> "$LOG"
if ! bunx wrangler@4.163.0 d1 migrations apply infered-routing --local --config wrangler.jsonc >> "$LOG" 2>&1; then
  printf '%s local-router boot: migration failed; refusing to start\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" >> "$LOG"
  exit 1
fi
printf '%s local-router boot: migrations complete; starting wrangler\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" >> "$LOG"
exec bunx wrangler@4.163.0 dev --port 8790 --test-scheduled
