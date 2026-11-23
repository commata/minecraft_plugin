# Paper 플러그인 개발 환경 설계 계획

> 상태: 초안 (구현 전 설계 단계)
> 대상: Ubuntu 미니PC의 Docker Paper 야생 서버 (`minecraft-paper`, 데이터 `/data`)
> 개발 PC: Windows, IntelliJ IDEA, 로컬 경로 `C:\minecreaft`

이 문서는 **운영 중인 야생 서버를 절대 건드리지 않으면서** 플러그인을 개발·테스트·배포하는 구조를 정합니다.
실제 코드 작성은 이 계획이 확정된 뒤 단계별로 진행합니다.

---

## 0. 확인이 필요한 정보 (가정 포함)

아래 값은 서버/PC에서 직접 확인해야 합니다. 확인 전까지는 오른쪽 **가정값**으로 설계합니다.

| 항목 | 확인 방법 | 가정값 |
| --- | --- | --- |
| Paper / MC 버전 | `docker exec minecraft-paper rcon-cli version` | 1.21.x 최신 |
| 서버 Java 버전 | `docker exec minecraft-paper java -version` | Java 21 |
| 개발 PC JDK | PowerShell `java -version` | 미설치로 가정 → JDK 21 설치 |
| Docker 이미지 | `docker inspect minecraft-paper --format '{{.Config.Image}}'` | `itzg/minecraft-server` |
| `/data` 마운트 | 아래 명령 | 호스트 디렉터리 바인드 마운트 |
| 기존 플러그인 목록 | `docker exec minecraft-paper rcon-cli plugins` | 없음 또는 소수 |

```bash
# /data 가 어디에 저장되는지 (영구 저장 여부 확인)
docker inspect minecraft-paper \
  --format '{{range .Mounts}}{{println .Type .Source "->" .Destination}}{{end}}'
```

**버전 맞추기 원칙:** 플러그인의 `paper-api` 버전은 **서버 버전과 같거나 낮게** 잡습니다.
높은 API로 빌드한 플러그인은 낮은 서버에서 로드되지 않습니다.
Java 버전은 서버가 요구하는 버전(MC 1.20.5 이상은 Java 21)에 맞춥니다.
Mojang이 2026년부터 연도 기반 버전(26.x)으로 바뀌었으므로, 서버가 26.x라면 Paper 문서에서 해당 버전의 Java 요구사항과 API 좌표를 다시 확인합니다.

---

## 1. 전체 구조

```
[Windows PC: C:\minecreaft]                 [Ubuntu 미니PC]
 IntelliJ + Gradle                          ┌─────────────────────────────┐
   │                                        │ minecraft-paper (운영, 야생) │ ← 최종 배포만
   ├─ ./gradlew runServer ─▶ 로컬 테스트 서버 │   /data/plugins/*.jar        │
   │   (run-paper, PC 안에서만 동작)          └─────────────────────────────┘
   │                                        ┌─────────────────────────────┐
   └─ ./gradlew build ─▶ *.jar ─ scp ─────▶ │ minecraft-paper-test (선택)  │ ← 실제 환경 검증
                                            │   운영 월드 복사본, 다른 포트  │
                                            └─────────────────────────────┘
```

환경은 3단계로 나눕니다.

| 단계 | 위치 | 용도 | 월드 |
| --- | --- | --- | --- |
| ① 로컬 개발 서버 | Windows PC (`run-paper`) | 코드 수정 → 즉시 실행/디버깅 | 새 테스트 월드 |
| ② 스테이징 서버 (선택) | 미니PC Docker, 별도 컨테이너 | 운영과 같은 이미지·플러그인 조합으로 검증 | 운영 월드 **복사본** |
| ③ 운영 서버 | 미니PC `minecraft-paper` | 실제 야생 서버 | 운영 월드 |

처음에는 ①과 ③만으로 시작하고, 기존 플러그인과 충돌 가능성이 생기면 ②를 추가합니다.

---

## 2. 프로젝트 구조: 멀티 모듈 채택

앞으로 여러 플러그인을 만들 계획이므로 **Gradle 멀티 프로젝트 한 저장소**로 갑니다.

| 방식 | 장점 | 단점 |
| --- | --- | --- |
| 단일 모듈 (플러그인 1개) | 가장 단순 | 두 번째 플러그인부터 설정 복붙 |
| 저장소 여러 개 | 플러그인별 독립 | 공통 코드 공유, 버전 관리가 번거로움 |
| **멀티 모듈 (채택)** | 공통 빌드 설정·공통 코드 공유, 한 번에 빌드 | 초기 설정이 조금 많음 |

```
C:\minecreaft\minecraft_plugin\          ← 이 저장소를 clone
├── settings.gradle.kts                   ← 모듈 목록
├── build.gradle.kts                      ← 루트(거의 비어 있음)
├── gradle.properties                     ← 버전 값 모음
├── gradle/
│   ├── libs.versions.toml                ← 의존성 버전 카탈로그
│   └── wrapper/                          ← Gradle Wrapper (커밋함)
├── build-logic/                          ← 공통 빌드 규칙 (convention plugin)
│   └── src/main/kotlin/
│       └── paper-plugin-conventions.gradle.kts
├── common/                               ← 공통 유틸 라이브러리 (메시지, 쿨다운, 설정 로더 등)
├── plugins/
│   ├── custom-items/                     ← 첫 번째 플러그인
│   │   ├── build.gradle.kts
│   │   └── src/main/
│   │       ├── java/kr/commata/customitems/CustomItemsPlugin.java
│   │       └── resources/plugin.yml
│   └── (다음 플러그인)/
├── scripts/
│   ├── deploy.ps1                        ← Windows에서 실행하는 배포 스크립트
│   └── server/                           ← 미니PC에서 실행할 스크립트
│       ├── backup.sh
│       └── install-plugin.sh
└── docs/
    └── PLAN.md
```

규칙:

- 패키지 이름: `kr.commata.<플러그인명>` (가정, 원하는 이름으로 변경 가능)
- `common`은 각 플러그인 JAR 안에 **Shadow로 포함 + relocate** 합니다. 별도 "라이브러리 플러그인"을 서버에 깔 필요가 없게 해서 배포를 단순하게 유지합니다.
- 플러그인 하나 = JAR 하나. 서로 다른 플러그인끼리는 직접 의존하지 않는 것을 기본으로 합니다.
- 메타데이터는 `plugin.yml`(Bukkit 방식)을 사용합니다. `paper-plugin.yml`은 아직 실험적이라 익숙해진 뒤 검토합니다.

---

## 3. Gradle 설정 (Kotlin DSL)

| 구성 요소 | 선택 | 이유 |
| --- | --- | --- |
| 빌드 스크립트 | Kotlin DSL (`*.gradle.kts`) | IntelliJ 자동완성, Paper 문서 예제와 동일 |
| Gradle | Wrapper 사용 (최신 8.x 이상) | PC에 Gradle 설치 불필요, CI와 동일 버전 |
| Java toolchain | `java.toolchain.languageVersion = 21` | 서버 Java와 일치, 자동 JDK 선택 |
| API | `io.papermc.paper:paper-api:<서버버전>-R0.1-SNAPSHOT` (`compileOnly`) | 서버가 제공하므로 JAR에 넣지 않음 |
| 로컬 서버 | `xyz.jpenilla.run-paper` | `./gradlew runServer`로 테스트 서버 자동 다운로드·실행 |
| JAR 묶기 | `com.gradleup.shadow` | `common` 포함 및 relocate |
| plugin.yml 버전 | `processResources`로 `${version}` 치환 | 버전 한 곳에서 관리 |

convention plugin(`build-logic`)이 하는 일:

1. Java 21 toolchain, UTF-8 인코딩 설정 (Windows 한글 깨짐 방지)
2. PaperMC 저장소 추가, `paper-api` compileOnly 추가
3. `plugin.yml`의 `version`, `api-version` 자동 치환
4. Shadow JAR을 기본 산출물로 지정 → `plugins/<이름>/build/libs/<이름>-<버전>.jar`
5. `runServer`의 `minecraftVersion`을 서버 버전과 동일하게 설정

각 플러그인 `build.gradle.kts`는 이 정도만 남깁니다.

```kotlin
plugins {
    id("paper-plugin-conventions")
}

dependencies {
    implementation(project(":common"))
}
```

버전 값은 `gradle.properties`에 모읍니다.

```properties
mcVersion=1.21.x        # 서버 버전 확인 후 확정
javaVersion=21
group=kr.commata
```

---

## 4. 로컬 개발 서버 (run-paper)

- `./gradlew :plugins:custom-items:runServer` 한 줄로 PC에서 Paper 서버가 뜨고, 빌드된 플러그인이 자동으로 들어갑니다.
- 서버 파일은 `plugins/<이름>/run/`에 생기며 `.gitignore`로 제외합니다.
- 첫 실행 후 `run/eula.txt`를 `true`로, `run/server.properties`에서 `online-mode`는 그대로 두고 `localhost:25565`로 접속합니다.
- 운영과 비슷한 조건을 맞추기 위해 테스트 서버에도 `difficulty=easy`, `gamerule keepInventory true`를 적용해 둡니다.
- IntelliJ에서 `runServer`를 **Debug**로 실행하면 브레이크포인트가 동작합니다.
- 선택: JetBrains Runtime + Hotswap 설정으로 재시작 없이 코드 반영 (익숙해진 뒤).

운영 서버의 설정 파일(`server.properties`, `whitelist.json`, `ops.json`)은 **저장소에 넣지 않습니다.** 운영 설정은 서버에만 존재합니다.

---

## 5. 스테이징 서버 (선택, 2단계 이후)

미니PC에 운영과 **같은 이미지**로 컨테이너를 하나 더 띄웁니다.

| 항목 | 운영 | 스테이징 |
| --- | --- | --- |
| 컨테이너 | `minecraft-paper` | `minecraft-paper-test` |
| 포트 | 25565 | 25566 |
| 데이터 | 운영 `/data` 볼륨 | 별도 디렉터리 (예: `~/mc-test/data`) |
| 월드 | 원본 | 운영 백업에서 복원한 복사본 |
| 화이트리스트 | 운영 그대로 | 본인만 |

미니PC 메모리가 부족하면 스테이징은 테스트할 때만 켜고 바로 끕니다.

---

## 6. 빌드 → 배포 흐름

### 6.1 수동 배포 (처음 단계)

```
1. 로컬:   ./gradlew build                 → plugins/custom-items/build/libs/custom-items-0.1.0.jar
2. 로컬:   ./gradlew runServer 로 테스트
3. 로컬:   scp 로 미니PC 임시 폴더에 업로드   → ~/plugin-staging/
4. 미니PC: backup.sh                        → 월드·설정 백업
5. 미니PC: install-plugin.sh <jar>         → 기존 버전 제거, 새 JAR 설치
6. 미니PC: 안전 재시작 후 로드 확인
```

미니PC 쪽 설치 절차 (`install-plugin.sh`가 할 일):

```bash
# 1) 저장 강제 및 알림
docker exec minecraft-paper rcon-cli "say 1분 후 플러그인 업데이트로 재시작합니다"
docker exec minecraft-paper rcon-cli "save-all flush"

# 2) 백업 (아래 6.2)
./backup.sh

# 3) 같은 플러그인의 이전 버전 JAR 제거 후 새 JAR 설치
#    /data 가 호스트 디렉터리에 바인드 마운트되어 있으면 docker cp 대신 직접 복사가 더 단순
cp ~/plugin-staging/custom-items-0.1.0.jar <호스트의 /data 경로>/plugins/
#    (named volume 이면)
docker cp ~/plugin-staging/custom-items-0.1.0.jar minecraft-paper:/data/plugins/

# 4) 재시작 (stop 이 저장을 마치도록 대기 시간을 넉넉히)
docker restart -t 60 minecraft-paper

# 5) 확인
docker exec minecraft-paper rcon-cli plugins
docker logs --tail 100 minecraft-paper | grep -iE "custom-items|error|exception"
```

지켜야 할 점:

- **`/reload` 는 쓰지 않습니다.** 메모리 누수와 플러그인 오작동 원인이 됩니다. 항상 재시작합니다.
- 같은 플러그인 JAR이 두 개(구버전+신버전) 있으면 충돌하므로 설치 전 이전 버전을 지웁니다. 지운 JAR은 `~/plugin-staging/old/`에 보관해 롤백에 씁니다.
- 컨테이너를 **재생성**(`docker rm`)하기 전에는 반드시 `/data` 마운트가 영구 저장인지 확인합니다.
- 플러그인은 `server.properties`, `whitelist.json`, `ops.json`, 게임룰을 **코드로 변경하지 않는다**를 개발 규칙으로 둡니다.

### 6.2 백업

| 대상 | 방법 |
| --- | --- |
| 월드 (`world`, `world_nether`, `world_the_end`) | `save-off` → `save-all flush` → `tar` → `save-on` |
| 설정 (`server.properties`, `whitelist.json`, `ops.json`, `bukkit.yml`, `spigot.yml`, `config/`) | 함께 `tar` |
| `plugins/` | 함께 `tar` (플러그인 데이터 포함) |

- 저장 위치: `~/mc-backups/YYYYMMDD-HHMM.tar.gz`, 최근 N개(예: 7개)만 유지
- 정기 백업은 이후 `itzg/mc-backup` 사이드카 컨테이너 또는 cron으로 자동화
- **복원 연습을 최소 한 번** 스테이징 서버에서 해 봅니다. 복원해 보지 않은 백업은 믿지 않습니다.

### 6.3 Windows 배포 스크립트 (`scripts/deploy.ps1`)

수동 절차가 익숙해지면 한 줄로 묶습니다.

```powershell
.\scripts\deploy.ps1 -Plugin custom-items
#  → gradlew build → scp → ssh 로 install-plugin.sh 실행 → 로그 출력
```

- SSH 접속 정보는 `~/.ssh/config`의 호스트 별칭(예: `minipc`)과 SSH 키를 사용하고, 비밀번호·IP는 저장소에 커밋하지 않습니다.
- Windows 10/11 기본 OpenSSH(`ssh`, `scp`)를 사용합니다.

---

## 7. GitHub Actions (나중 단계)

| 단계 | 내용 | 시점 |
| --- | --- | --- |
| CI | push/PR마다 `./gradlew build` 실행, JAR을 Artifact로 업로드 | 첫 플러그인이 빌드되면 바로 |
| Release | `v*` 태그를 달면 GitHub Release에 JAR 첨부 | 운영 배포를 시작할 때 |
| CD (선택) | Release → 미니PC에 자동 배포 | 수동 배포가 충분히 안정된 뒤 |

CD 방식 후보:

1. **미니PC에 self-hosted runner 설치** (추천): 미니PC 포트를 외부에 열 필요가 없습니다. runner가 Release JAR을 받아 `install-plugin.sh`를 실행합니다.
2. GitHub 호스트 runner에서 SSH 접속: 미니PC SSH를 인터넷에 노출해야 하므로 비추천.

운영 배포는 자동화하더라도 **수동 승인(GitHub Environments의 required reviewer)** 을 걸어 둡니다.

---

## 8. 첫 기능 로드맵: 커스텀 아이템 & 스킬

### 단계 0. 환경 검증 (Hello 플러그인)
- 멀티 모듈 뼈대, `build-logic`, `common` 생성
- 접속 시 환영 메시지 + `/ci version` 명령어
- 완료 기준: 로컬 `runServer`에서 동작 → 운영 서버에 배포해서 `plugins` 목록에 표시

### 단계 1. 커스텀 아이템 기본
- `/ci give <아이템ID> [플레이어]` (OP 전용, 권한 `customitems.give`)
- 아이템 식별: `PersistentDataContainer`에 `customitems:id` 태그 저장 (이름·로어가 바뀌어도 인식 유지)
- 아이템 정의는 `config.yml` 또는 `items/*.yml`에서 읽기 (이름, 로어, 재료, 속성)
- 외형: 처음에는 바닐라 텍스처 + 이름/로어/발광 효과. 리소스팩(`item_model`/커스텀 모델)은 나중 단계

### 단계 2. 스킬 시스템
- 우클릭(`PlayerInteractEvent`)으로 아이템에 연결된 스킬 발동
- 구조: `Skill` 인터페이스 → `DashSkill`, `FireballSkill`, `HealSkill` 등 구현
- 공통 기능: 쿨다운 관리(`common`), 액션바로 쿨다운 표시, 파티클·사운드
- 야생 서버 보호 규칙: 블록 파괴 스킬 없음, PvP 피해는 설정으로 on/off, 스폰 보호 구역 존중

### 단계 3. 데이터 저장
- 플레이어별 해금 스킬·숙련도 저장: 처음에는 플레이어 PDC 또는 YAML, 규모가 커지면 SQLite
- 서버 재시작 후에도 유지되는지 테스트

### 단계 4. 확장 후보 (별도 플러그인으로)
- `/home`, `/spawn` (쉬움, 복습용)
- 몬스터 처치 보상·경제
- 레벨·직업 시스템 (스킬 시스템과 연동)

각 단계는 "로컬 테스트 → (스테이징) → 백업 → 운영 배포" 순서를 지킵니다.

---

## 9. Windows 개발 PC 준비 체크리스트

- [ ] JDK 21 설치 (Eclipse Temurin 21 권장), `java -version` 확인
- [ ] IntelliJ IDEA + **Minecraft Development** 플러그인 설치 (선택이지만 편리)
- [ ] Git 설치, `git config --global core.autocrlf input`
- [ ] `C:\minecreaft`에서 `git clone https://github.com/commata/minecraft_plugin`
- [ ] IntelliJ에서 `C:\minecreaft\minecraft_plugin` 열기 → Gradle 프로젝트로 인식
- [ ] Settings → Build Tools → Gradle → Gradle JVM을 JDK 21로 지정
- [ ] `ssh minipc` 로 비밀번호 없이 접속되도록 SSH 키 등록

> 경로 참고: `C:\minecreaft`는 공백·한글이 없어 Gradle에 문제가 없습니다. 오타라면(`minecraft`) 지금 바꾸는 것이 나중보다 쉽습니다.

---

## 10. 다음 작업 순서

1. 0장의 값(서버 버전, Java, 마운트 경로) 확인 → 이 문서의 가정값 갱신
2. 9장 PC 준비
3. 단계 0 구현 PR (멀티 모듈 뼈대 + Hello 플러그인 + CI)
4. 미니PC 스크립트(`backup.sh`, `install-plugin.sh`) 작성 및 백업·복원 1회 테스트
5. 단계 1부터 기능 개발
