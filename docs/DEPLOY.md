# 운영 서버 백업·배포 가이드

운영 서버: 미니PC `minipc` (SSH 포트 22222, 계정 `deploy`), 컨테이너 `minecraft-paper`,
데이터 `/home/deploy/services/minecraft/data` (컨테이너의 `/data`).

| 스크립트 | 실행 위치 | 하는 일 |
| --- | --- | --- |
| `scripts/deploy.ps1` | Windows | 빌드 → 스크립트·JAR 업로드 → `install-plugin.sh` 실행 |
| `scripts/server/backup.sh` | 미니PC | 월드·설정·플러그인 데이터 백업 (최근 7개 유지) |
| `scripts/server/install-plugin.sh` | 미니PC | 알림 → 저장 → 정지 → 백업 → 우리 JAR만 교체 → 시작 → 로드 확인 |
| `scripts/server/rollback.sh` | 미니PC | 마지막 배포의 JAR 교체를 되돌리고 재시작 |
| `scripts/server/mc-env.sh` | (공통 설정) | 경로·컨테이너 이름·보호 플러그인 목록 |

미니PC에서는 `~/mc-scripts/`에, 업로드한 JAR은 `~/plugin-staging/`에, 백업은 `~/mc-backups/`에 놓입니다.

## 지키는 규칙

- **Chunky, CoreProtect는 절대 건드리지 않습니다.** JAR 안의 `plugin.yml` 이름으로 판단하며, 보호 목록에 있는 이름은 설치·교체 대상이 되면 즉시 중단합니다.
- 교체는 **같은 플러그인 이름의 이전 JAR만** 대상으로 합니다. 이전 JAR은 삭제하지 않고 `~/plugin-staging/old/<시각>/`으로 옮깁니다.
- 항상 백업 후 교체합니다. 배포 중 백업은 서버를 **정지한 상태**에서 떠서 CoreProtect DB까지 일관되게 저장됩니다.
- `/reload`는 쓰지 않고 항상 재시작합니다.
- 설치 도중 실패하면 JAR을 바꾸기 전 상태 그대로 서버를 다시 켭니다.

## 처음 한 번: 준비

1. 미니PC에 `python3`가 있는지 확인합니다 (JAR의 plugin.yml을 읽는 데 사용).
   ```bash
   ssh minipc "python3 --version"
   ```
2. 업로드만 해서 스크립트를 올리고, 백업을 한 번 떠 봅니다.
   ```powershell
   .\scripts\deploy.ps1 -Plugin custom-items -UploadOnly
   ssh minipc "~/mc-scripts/backup.sh"
   ```
   서버는 켜진 그대로이고, 저장만 잠시 멈췄다가 다시 켭니다. `~/mc-backups/mc-*.tar.gz`가 생기면 성공입니다.
   백업 크기와 걸린 시간을 기억해 두면 배포 중 서버가 멈춰 있는 시간을 짐작할 수 있습니다.
3. dry-run으로 무엇이 바뀔지 확인합니다. 실제로는 아무것도 바꾸지 않습니다.
   ```powershell
   .\scripts\deploy.ps1 -Plugin custom-items -DryRun
   ```
   `교체될 이전 버전`, `새 플러그인` 줄에 우리 플러그인만 나오는지 확인합니다.

## 배포

```powershell
.\scripts\deploy.ps1 -Plugin custom-items            # 접속자에게 30초 전 알림 후 진행
.\scripts\deploy.ps1 -Plugin custom-items -Delay 0   # 혼자 있을 때 바로 진행
```

마지막에 `확인: CustomItems 로드됨`과 `배포 완료`가 나오면 끝입니다. 출력에 되돌리기 명령도 함께 나옵니다.
Chunky 사전 생성을 돌리던 중이었다면 서버 콘솔에서 `chunky continue`를 실행합니다.

## 되돌리기 (플러그인만)

```bash
ssh minipc
~/mc-scripts/rollback.sh --list     # 배포 기록
~/mc-scripts/rollback.sh            # 가장 최근 배포 되돌리기
~/mc-scripts/rollback.sh 20261124-130500
```

새로 넣은 JAR을 빼고 이전 JAR을 되돌린 뒤 재시작합니다. 월드는 건드리지 않습니다.

## 백업에서 복원 (월드까지 되돌려야 할 때)

월드가 망가졌을 때만 사용합니다. **백업 시점 이후의 플레이 기록은 사라지므로** 신중하게 진행합니다. 기존 데이터는 지우지 않고 옆으로 옮겨 둡니다.

```bash
ssh minipc
ls -1t ~/mc-backups/                               # 복원할 백업 고르기
B=~/mc-backups/mc-YYYYMMDD-HHMMSS.tar.gz
D=/home/deploy/services/minecraft/data

docker stop -t 90 minecraft-paper
mv "$D" "$D.broken-$(date +%Y%m%d-%H%M%S)"         # 현재 데이터는 보관
mkdir -p "$D"
tar -C "$D" -xzpf "$B"
docker start minecraft-paper
docker logs -f minecraft-paper                     # Done 이 나올 때까지 확인
```

서버 JAR·라이브러리·로그는 백업에서 제외했으므로 처음 시작할 때 이미지가 다시 받습니다.
복원이 확인되면 `data.broken-*` 폴더는 나중에 직접 지웁니다.

## 설정 바꾸기

`scripts/server/mc-env.sh`의 값은 환경 변수로 덮어쓸 수 있습니다.

```bash
KEEP_BACKUPS=14 ~/mc-scripts/backup.sh
```

## 정기 백업 (선택)

매일 새벽 5시에 백업하려면 미니PC에서 `crontab -e`로 아래 줄을 추가합니다.

```
0 5 * * * $HOME/mc-scripts/backup.sh >> $HOME/mc-backups/cron.log 2>&1
```
