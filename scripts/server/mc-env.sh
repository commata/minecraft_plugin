# shellcheck shell=bash
# 운영 서버 공통 설정. 다른 스크립트가 source 합니다.
# 값을 바꾸려면 실행할 때 환경 변수로 덮어쓰면 됩니다. 예: KEEP_BACKUPS=14 ./backup.sh

MC_CONTAINER="${MC_CONTAINER:-minecraft-paper}"
MC_DATA="${MC_DATA:-/home/deploy/services/minecraft/data}"   # 컨테이너의 /data (바인드 마운트)
BACKUP_DIR="${BACKUP_DIR:-$HOME/mc-backups}"
KEEP_BACKUPS="${KEEP_BACKUPS:-7}"
STAGING_DIR="${STAGING_DIR:-$HOME/plugin-staging}"           # 업로드된 새 JAR
DEPLOY_LOG_DIR="${DEPLOY_LOG_DIR:-$STAGING_DIR/deploys}"     # 배포 기록 (롤백용)
STOP_TIMEOUT="${STOP_TIMEOUT:-90}"                           # docker stop 대기 시간(초)
START_TIMEOUT="${START_TIMEOUT:-300}"                        # 서버가 뜰 때까지 기다리는 시간(초)

# 이 스크립트들이 절대 교체·삭제하지 않는 플러그인 (plugin.yml 의 name 기준)
PROTECTED_PLUGINS="${PROTECTED_PLUGINS:-Chunky CoreProtect}"

log()  { printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*" >&2; }
warn() { printf '[%s] 경고: %s\n' "$(date '+%H:%M:%S')" "$*" >&2; }
die()  { printf '[%s] 오류: %s\n' "$(date '+%H:%M:%S')" "$*" >&2; exit 1; }

rcon() { docker exec "$MC_CONTAINER" rcon-cli "$@"; }

container_running() {
    [ "$(docker inspect -f '{{.State.Running}}' "$MC_CONTAINER" 2>/dev/null)" = "true" ]
}

# JAR 안의 plugin.yml(또는 paper-plugin.yml)에서 name 을 읽음
plugin_name_of() {
    python3 - "$1" <<'PY'
import re, sys, zipfile
try:
    z = zipfile.ZipFile(sys.argv[1])
except Exception:
    sys.exit(1)
for f in ("paper-plugin.yml", "plugin.yml"):
    try:
        text = z.read(f).decode("utf-8", "replace")
    except KeyError:
        continue
    m = re.search(r"""^name:\s*['"]?([^'"\s#]+)""", text, re.M)
    if m:
        print(m.group(1))
        sys.exit(0)
sys.exit(1)
PY
}

is_protected() {
    local name="$1" p
    for p in $PROTECTED_PLUGINS; do
        [ "${name,,}" = "${p,,}" ] && return 0
    done
    return 1
}

require_cmds() {
    local c
    for c in "$@"; do
        command -v "$c" >/dev/null 2>&1 || die "'$c' 명령이 없습니다. 설치 후 다시 실행하세요."
    done
}
