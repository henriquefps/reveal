#!/usr/bin/env bash
# A throwaway reveal-sync for instrumented sync tests (docs/adr/0018): built from sync-server/, bound to loopback,
# everything under one work directory, removed by `stop`. Never exposed beyond this machine; an emulator reaches it
# through `adb reverse`.
#
#   scripts/sync/local-server.sh start <work dir> <port> [nas file...]   build, run, set the admin password, choose
#                                                                        <work dir>/mnt/backup as the backup folder
#                                                                        and index the given files (CC0 samples or
#                                                                        synthetic) as a NAS folder
#   scripts/sync/local-server.sh pair  <work dir> <port> [url]           print a fresh pairing link (10 minutes)
#                                                                        whose url is [url] (default the loopback)
#   scripts/sync/local-server.sh stop  <work dir>                        stop it and delete the work directory
set -euo pipefail

here="$(cd "$(dirname "$0")/../.." && pwd)"
cmd="${1:?start|pair|stop}"; dir="${2:?work dir}"
admin() { # method path [json]
  curl -fsS -X "$1" -b "$dir/cookies" -c "$dir/cookies" -H 'Content-Type: application/json' -H 'X-Reveal-Admin: 1' \
    "http://127.0.0.1:$port$2" ${3:+--data "$3"}
}

case "$cmd" in
  start)
    port="${3:?port}"; shift 3
    mkdir -p "$dir/data" "$dir/mnt/backup" "$dir/mnt/nas/Shoots"
    for f in "$@"; do cp "$f" "$dir/mnt/nas/Shoots/"; done
    (cd "$here/sync-server" && CGO_ENABLED=0 go build -o "$dir/reveal-sync" .)
    "$dir/reveal-sync" --listen 127.0.0.1 --port "$port" --data-dir "$dir/data" --mounts "$dir/mnt" \
      --log-format text > "$dir/server.log" 2>&1 &
    echo $! > "$dir/pid"
    for _ in $(seq 1 100); do curl -fsS "http://127.0.0.1:$port/v1/health" > /dev/null 2>&1 && break; sleep 0.1; done
    code="$(grep -o 'setupCode=[0-9 -]*[0-9]' "$dir/server.log" | head -1 | cut -d= -f2)"
    code="${code//\"/}"
    password="local-test-$(date +%s)-pass"
    admin POST /admin/api/setup "{\"code\":\"$code\",\"password\":\"$password\"}" > /dev/null
    admin PUT /admin/api/storage "{\"path\":\"$dir/mnt/backup\"}" > /dev/null
    if [ "$#" -gt 0 ]; then admin POST /admin/api/nas "{\"path\":\"$dir/mnt/nas\"}" > /dev/null; fi
    echo "$port" > "$dir/port"
    echo "reveal-sync on http://127.0.0.1:$port (work dir $dir)"
    ;;
  pair)
    port="$(cat "$dir/port")"
    url="${4:-http://127.0.0.1:$port}"
    admin POST /admin/api/pairings "{\"url\":\"$url\"}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["uri"])'
    ;;
  stop)
    [ -f "$dir/pid" ] && kill "$(cat "$dir/pid")" 2> /dev/null || true
    rm -rf "$dir"
    ;;
  *) echo "usage: $0 start|pair|stop" >&2; exit 2 ;;
esac
