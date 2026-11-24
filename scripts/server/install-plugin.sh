#!/usr/bin/env bash
# 새로 만든 플러그인 JAR 을 운영 서버에 설치합니다.
#
#   ./install-plugin.sh [옵션] <새 JAR> [<새 JAR> ...]
#
# 옵션
#   --dry-run      실제로 바꾸지 않고 무엇을 할지만 출력
#   --delay <초>   재시작 전 접속자에게 알리고 기다리는 시간 (기본 30)
#   --no-backup    백업 생략 (권장하지 않음)
#
# 순서: 검사 → 접속자 알림 → 저장 → 서버 정지 → 백업(--offline) → 같은 이름의 이전 JAR 을 보관 폴더로 이동
#       → 새 JAR 복사 → 서버 시작 → 플러그인 로드 확인. 문제가 있으면 rollback.sh 로 되돌립니다.
# Chunky, CoreProtect 등 PROTECTED_PLUGINS 는 절대 건드리지 않습니다.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=mc-env.sh
source "$SCRIPT_DIR/mc-env.sh"

DRY_RUN=0
DELAY=30
DO_BACKUP=1
JARS=()
while [ $# -gt 0 ]; do
    case "$1" in
        --dry-run) DRY_RUN=1 ;;
        --delay) shift; DELAY="${1:?--delay 뒤에 초를 적으세요}" ;;
        --no-backup) DO_BACKUP=0 ;;
        -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
        -*) die "알 수 없는 옵션: $1" ;;
        *) JARS+=("$1") ;;
    esac
    shift
done
[ ${#JARS[@]} -gt 0 ] || die "설치할 JAR 을 지정하세요. 예: $0 ~/plugin-staging/custom-items-0.1.0.jar"

require_cmds docker python3 tar
PLUGINS_DIR="$MC_DATA/plugins"
[ -d "$PLUGINS_DIR" ] || die "플러그인 폴더가 없습니다: $PLUGINS_DIR"
[ -w "$PLUGINS_DIR" ] || die "플러그인 폴더에 쓸 권한이 없습니다: $PLUGINS_DIR"

# ---------- 1. 검사: 새 JAR 이름 확인, 교체될 이전 JAR 찾기 ----------
declare -a NEW_NAMES=() OLD_JARS=()
for jar in "${JARS[@]}"; do
    [ -f "$jar" ] || die "파일이 없습니다: $jar"
    case "$jar" in *-plain.jar) die "-plain.jar 은 배포용이 아닙니다: $jar" ;; esac
    name="$(plugin_name_of "$jar")" || die "plugin.yml 을 읽을 수 없습니다 (플러그인 JAR 이 아님?): $jar"
    is_protected "$name" && die "$name 은(는) 보호 대상 플러그인이라 이 스크립트로 바꿀 수 없습니다."
    NEW_NAMES+=("$name")
    log "새 플러그인: $name ← $(basename "$jar")"
done

for existing in "$PLUGINS_DIR"/*.jar; do
    [ -e "$existing" ] || continue
    ename="$(plugin_name_of "$existing" 2>/dev/null)" || continue
    is_protected "$ename" && continue
    for n in "${NEW_NAMES[@]}"; do
        if [ "$ename" = "$n" ]; then
            OLD_JARS+=("$existing")
            log "교체될 이전 버전: $(basename "$existing") ($ename)"
        fi
    done
done
[ ${#OLD_JARS[@]} -gt 0 ] || log "이전 버전 없음 (새로 설치)"

stamp="$(date '+%Y%m%d-%H%M%S')"
ARCHIVE="$STAGING_DIR/old/$stamp"
MANIFEST="$DEPLOY_LOG_DIR/$stamp.env"

if [ "$DRY_RUN" = 1 ]; then
    log "[dry-run] 서버 정지 → 백업 → 이전 JAR ${#OLD_JARS[@]}개를 $ARCHIVE 로 이동 → 새 JAR ${#JARS[@]}개 복사 → 서버 시작"
    log "[dry-run] 실제로는 아무것도 바꾸지 않았습니다."
    exit 0
fi

# ---------- 2. 알림, 저장, 정지 ----------
if container_running; then
    if [ "$DELAY" -gt 0 ]; then
        rcon "say ${DELAY}초 후 플러그인 업데이트로 서버가 잠시 재시작됩니다." >/dev/null || true
        log "${DELAY}초 대기 (접속자 알림)"
        sleep "$DELAY"
    fi
    rcon "chunky pause" >/dev/null 2>&1 || true     # Chunky 작업이 돌고 있으면 멈춤 (진행 상황은 저장됨)
    rcon "save-all flush" >/dev/null || warn "save-all 실패"
    log "서버 정지 중 (최대 ${STOP_TIMEOUT}초)"
    docker stop -t "$STOP_TIMEOUT" "$MC_CONTAINER" >/dev/null
fi
container_running && die "서버가 정지되지 않았습니다."

# 여기서부터 실패하면 서버를 다시 켜고 안내
started=0
on_exit() {
    local rc=$?
    if [ "$rc" -ne 0 ] && [ "$started" = 0 ]; then
        warn "설치 도중 실패했습니다. 서버를 기존 상태 그대로 다시 시작합니다."
        docker start "$MC_CONTAINER" >/dev/null || warn "docker start 실패! 직접 확인하세요."
    fi
}
trap on_exit EXIT

# ---------- 3. 백업 ----------
BACKUP_FILE=""
if [ "$DO_BACKUP" = 1 ]; then
    BACKUP_FILE="$("$SCRIPT_DIR/backup.sh" --offline | tail -n 1)"
else
    warn "백업을 생략했습니다."
fi

# ---------- 4. JAR 교체 (이전 버전은 삭제하지 않고 보관) ----------
mkdir -p "$ARCHIVE" "$DEPLOY_LOG_DIR"
owner="$(stat -c '%u:%g' "$PLUGINS_DIR")"
{
    echo "STAMP=$stamp"
    echo "BACKUP_FILE='$BACKUP_FILE'"
    echo "ARCHIVE='$ARCHIVE'"
    echo "REMOVED=("
    for j in "${OLD_JARS[@]}"; do echo "  '$(basename "$j")'"; done
    echo ")"
    echo "ADDED=("
    for j in "${JARS[@]}"; do echo "  '$(basename "$j")'"; done
    echo ")"
} > "$MANIFEST"

for j in "${OLD_JARS[@]}"; do
    mv -- "$j" "$ARCHIVE/"
done
for j in "${JARS[@]}"; do
    cp -- "$j" "$PLUGINS_DIR/"
    chmod 644 "$PLUGINS_DIR/$(basename "$j")"
    chown "$owner" "$PLUGINS_DIR/$(basename "$j")" 2>/dev/null || true
done
log "JAR 교체 완료 (기록: $MANIFEST)"

# ---------- 5. 시작 및 확인 ----------
since="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
docker start "$MC_CONTAINER" >/dev/null
started=1
log "서버 시작 대기 중 (최대 ${START_TIMEOUT}초)"
waited=0
until docker logs --since "$since" "$MC_CONTAINER" 2>&1 | grep -q 'Done ('; do
    sleep 5
    waited=$((waited + 5))
    container_running || die "서버가 시작 중에 종료되었습니다. docker logs $MC_CONTAINER 확인 후 ./rollback.sh 로 되돌리세요."
    [ "$waited" -lt "$START_TIMEOUT" ] || die "서버가 ${START_TIMEOUT}초 안에 뜨지 않았습니다. 로그 확인 후 필요하면 ./rollback.sh"
done

failed=0
plugins_out="$(rcon plugins 2>/dev/null || true)"
for n in "${NEW_NAMES[@]}"; do
    if docker logs --since "$since" "$MC_CONTAINER" 2>&1 | grep -qiE "(Error occurred while enabling|Could not load).*${n}"; then
        warn "$n 로드 중 오류가 있습니다."
        failed=1
    elif ! grep -q "$n" <<<"$plugins_out"; then
        warn "plugins 목록에 $n 이(가) 보이지 않습니다."
        failed=1
    else
        log "확인: $n 로드됨"
    fi
done
docker logs --since "$since" "$MC_CONTAINER" 2>&1 | grep -iE "$(IFS='|'; echo "${NEW_NAMES[*]}")" | tail -n 20 || true

if [ "$failed" = 1 ]; then
    die "설치는 끝났지만 플러그인 확인에 실패했습니다. 되돌리려면: $SCRIPT_DIR/rollback.sh $stamp"
fi
log "배포 완료. Chunky 작업을 쓰고 있었다면 콘솔에서 'chunky continue' 를 실행하세요."
log "되돌리려면: $SCRIPT_DIR/rollback.sh $stamp"
