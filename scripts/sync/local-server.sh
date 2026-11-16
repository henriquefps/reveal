#!/usr/bin/env bash
# A throwaway hoard-server for instrumented sync tests (docs/adr/0018): the pinned release image run in Docker, bound
# to loopback, everything under one work directory, removed by `stop`. Never exposed beyond this machine; an emulator
# reaches it through `adb reverse`. The server lives in the Hoard repository (github.com/henriquefps/hoard, `server/`);
# the protocol of record is Hoard's docs/adr/0013-server-protocol.md.
#
#   scripts/sync/local-server.sh start <work dir> <port> [nas file...]   run the container, set the admin password,
#                                                                        choose <work dir>/mnt/backup as the backup
#                                                                        folder and index the given files (CC0 samples
#                                                                        or synthetic) as a NAS folder
#   scripts/sync/local-server.sh pair  <work dir> <port> [url]           print a fresh pairing link (10 minutes)
#                                                                        whose url is [url] (default the loopback)
#   scripts/sync/local-server.sh reindex <work dir>                      walk the NAS folder again (after files were
#                                                                        added to <work dir>/mnt/nas/Shoots)
#   scripts/sync/local-server.sh revoke <work dir> <device id>           revoke a device as the admin page does
#   scripts/sync/local-server.sh stop  <work dir>                        stop it and delete the work directory
#
# The pinned server version is HOARD_SERVER_VERSION below (the only place; bump it when Reveal moves to a newer server).
# Override for a try-out: HOARD_SERVER_IMAGE=<image:tag>.
set -euo pipefail

HOARD_SERVER_VERSION="0.12.0"
image="${HOARD_SERVER_IMAGE:-ghcr.io/henriquefps/hoard-server:$HOARD_SERVER_VERSION}"

cmd="${1:?start|pair|reindex|revoke|stop}"; dir="${2:?work dir}"
admin() { # method path [json]
  curl -fsS -X "$1" -b "$dir/cookies" -c "$dir/cookies" -H 'Content-Type: application/json' -H 'X-Hoard-Admin: 1' \
    "http://127.0.0.1:$port$2" ${3:+--data "$3"}
}
name() { echo "reveal-test-$(basename "$dir")-$(cat "$dir/port")"; }

case "$cmd" in
  start)
    port="${3:?port}"; shift 3
    mkdir -p "$dir/data" "$dir/mnt/backup" "$dir/mnt/nas/Shoots"
    for f in "$@"; do cp "$f" "$dir/mnt/nas/Shoots/"; done
    echo "$port" > "$dir/port"
    # The container runs as the caller so the bind mounts need no chown; limits keep a shared machine usable.
    docker run -d --name "$(name)" --user "$(id -u):$(id -g)" --cpus 2 --memory 1g \
      -p "127.0.0.1:$port:3071" -e LOG_FORMAT=text \
      -v "$dir/data:/data" -v "$dir/mnt:/mnt" "$image" serve > "$dir/container"
    for _ in $(seq 1 200); do curl -fsS "http://127.0.0.1:$port/v1/health" > /dev/null 2>&1 && break; sleep 0.1; done
    code="$(docker logs "$(name)" 2>&1 | grep -o 'setupCode=[0-9 -]*[0-9]' | head -1 | cut -d= -f2)"
    code="${code//\"/}"
    password="local-test-$(date +%s)-pass"
    admin POST /admin/api/setup "{\"code\":\"$code\",\"password\":\"$password\"}" > /dev/null
    admin PUT /admin/api/storage '{"path":"/mnt/backup"}' > /dev/null
    if [ "$#" -gt 0 ]; then admin POST /admin/api/nas '{"path":"/mnt/nas"}' > /dev/null; fi
    echo "hoard-server $image on http://127.0.0.1:$port (work dir $dir)"
    ;;
  pair)
    port="$(cat "$dir/port")"
    url="${4:-http://127.0.0.1:$port}"
    admin POST /admin/api/pairings "{\"url\":\"$url\"}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["uri"])'
    ;;
  reindex)
    port="$(cat "$dir/port")"
    admin POST /admin/api/nas/reindex > /dev/null
    ;;
  revoke)
    port="$(cat "$dir/port")"
    admin POST "/admin/api/devices/${3:?device id}/revoke" > /dev/null
    ;;
  stop)
    [ -f "$dir/port" ] && docker rm -f "$(name)" > /dev/null 2>&1 || true
    rm -rf "$dir"
    ;;
  *) echo "usage: $0 start|pair|reindex|revoke|stop" >&2; exit 2 ;;
esac
