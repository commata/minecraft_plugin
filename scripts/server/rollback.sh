#!/usr/bin/env bash
# 마지막(또는 지정한) 배포를 되돌립니다: 새로 넣은 JAR 을 빼고 이전 JAR 을 복원한 뒤 재시작.
# 월드는 건드리지 않습니다. 월드까지 되돌려야 하면 docs/DEPLOY.md 의 "백업에서 복원" 절차를 따르세요.
#
#   ./rollback.sh            가장 최근 배포를 되돌림
#   ./rollback.sh <STAMP>    특정 배포를 되돌림 (예: 20261124-130500)
#   ./rollback.sh --list     배포 기록 목록
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=mc-env.sh
source "$SCRIPT_DIR/mc-env.sh"

case "${1:-}" in
    --list) ls -1t "$DEPLOY_LOG_DIR"/*.env 2>/dev/null | xargs -r -n1 basename | sed 's/\.env$//'; exit 0 ;;
    -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
    "") MANIFEST="$(ls -1t "$DEPLOY_LOG_DIR"/*.env 2>/dev/null | head -n 1 || true)" ;;
    *) MANIFEST="$DEPLOY_LOG_DIR/$1.env" ;;
esac
[ -n "${MANIFEST:-}" ] && [ -f "$MANIFEST" ] || die "배포 기록을 찾을 수 없습니다. ./rollback.sh --list 로 확인하세요."

STAMP="" ARCHIVE="" BACKUP_FILE=""
REMOVED=() ADDED=()
# shellcheck disable=SC1090
source "$MANIFEST"
PLUGINS_DIR="$MC_DATA/plugins"

log "되돌릴 배포: $STAMP"
for j in "${ADDED[@]}";   do log "  제거: $j"; done
for j in "${REMOVED[@]}"; do log "  복원: $j"; done
[ -n "$BACKUP_FILE" ] && log "  (참고) 배포 직전 백업: $BACKUP_FILE"

if container_running; then
    rcon "say 플러그인 되돌리기로 서버가 잠시 재시작됩니다." >/dev/null || true
    rcon "save-all flush" >/dev/null || true
    docker stop -t "$STOP_TIMEOUT" "$MC_CONTAINER" >/dev/null
fi

for j in "${ADDED[@]}"; do
    if [ -f "$PLUGINS_DIR/$j" ]; then
        mkdir -p "$ARCHIVE/rolled-back"
        mv -- "$PLUGINS_DIR/$j" "$ARCHIVE/rolled-back/"
    fi
done
for j in "${REMOVED[@]}"; do
    [ -f "$ARCHIVE/$j" ] || die "보관된 이전 JAR 이 없습니다: $ARCHIVE/$j"
    cp -- "$ARCHIVE/$j" "$PLUGINS_DIR/"
done

docker start "$MC_CONTAINER" >/dev/null
mv -- "$MANIFEST" "$MANIFEST.rolled-back"
log "되돌리기 완료. 서버를 다시 시작했습니다. 확인: docker logs -f $MC_CONTAINER"
