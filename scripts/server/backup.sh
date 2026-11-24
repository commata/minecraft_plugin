#!/usr/bin/env bash
# 월드·설정·플러그인 데이터를 tar.gz 로 백업합니다.
#
#   ./backup.sh            서버가 켜진 상태에서 백업 (save-off → save-all flush → tar → save-on)
#   ./backup.sh --offline  서버가 꺼진 상태에서 백업 (install-plugin.sh 가 사용, 가장 안전)
#
# 결과: $BACKUP_DIR/mc-YYYYMMDD-HHMMSS.tar.gz  (최근 $KEEP_BACKUPS 개만 유지)
# 마지막 줄에 백업 파일 경로를 출력합니다.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=mc-env.sh
source "$SCRIPT_DIR/mc-env.sh"

MODE=online
case "${1:-}" in
    --offline) MODE=offline ;;
    "" ) ;;
    -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
    *) die "알 수 없는 옵션: $1" ;;
esac

require_cmds docker tar gzip df du
[ -d "$MC_DATA" ] || die "데이터 폴더가 없습니다: $MC_DATA"
mkdir -p "$BACKUP_DIR"

# 읽을 수 없는 파일이 있으면 백업이 불완전해지므로 미리 확인
unreadable="$(find "$MC_DATA" ! -readable -print -quit 2>/dev/null || true)"
[ -z "$unreadable" ] || die "읽을 수 없는 파일이 있습니다: $unreadable (파일 소유자/권한 확인 필요)"

# 디스크 여유 공간 확인 (압축 전 크기 기준, 보수적으로)
need_kb="$(du -sk "$MC_DATA" | cut -f1)"
free_kb="$(df -Pk "$BACKUP_DIR" | awk 'NR==2 {print $4}')"
if [ "$free_kb" -lt "$need_kb" ]; then
    die "백업 공간 부족: 필요 약 $((need_kb/1024))MB, 남은 공간 $((free_kb/1024))MB ($BACKUP_DIR)"
fi

stamp="$(date '+%Y%m%d-%H%M%S')"
out="$BACKUP_DIR/mc-$stamp.tar.gz"
tmp="$out.partial"

saving_off=0
cleanup() {
    rm -f "$tmp"
    if [ "$saving_off" = 1 ]; then
        rcon "save-on" >/dev/null 2>&1 || warn "save-on 실패. 서버 콘솔에서 직접 'save-on' 을 실행하세요!"
    fi
}
trap cleanup EXIT

if [ "$MODE" = online ]; then
    container_running || die "서버가 꺼져 있습니다. 꺼진 상태라면 --offline 으로 실행하세요."
    log "자동 저장 중지 후 디스크에 저장 (save-off, save-all flush)"
    rcon "save-off" >/dev/null
    saving_off=1
    rcon "save-all flush" >/dev/null
    sleep 3
else
    container_running && die "--offline 은 서버가 꺼진 상태에서만 사용합니다."
fi

log "백업 중: $MC_DATA → $out"
# 다시 받을 수 있는 파일(서버 JAR, 라이브러리, 캐시, 로그)은 제외
tar -C "$MC_DATA" -czpf "$tmp" \
    --anchored --no-wildcards-match-slash \
    --exclude='./logs' \
    --exclude='./cache' \
    --exclude='./libraries' \
    --exclude='./versions' \
    --exclude='./*.jar' \
    .

if [ "$saving_off" = 1 ]; then
    rcon "save-on" >/dev/null
    saving_off=0
fi

log "백업 파일 검사 중"
tar -tzf "$tmp" >/dev/null || die "백업 파일이 손상되었습니다: $tmp"
for must in ./world ./server.properties ./whitelist.json ./ops.json; do
    tar -tzf "$tmp" "$must" >/dev/null 2>&1 || warn "백업에 $must 이(가) 없습니다."
done
mv "$tmp" "$out"

# 오래된 백업 정리 (최근 KEEP_BACKUPS 개 유지)
mapfile -t old < <(ls -1t "$BACKUP_DIR"/mc-*.tar.gz 2>/dev/null | tail -n +"$((KEEP_BACKUPS + 1))")
for f in "${old[@]}"; do
    log "오래된 백업 삭제: $f"
    rm -f -- "$f"
done

log "백업 완료 ($(du -h "$out" | cut -f1))"
echo "$out"
