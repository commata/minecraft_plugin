# AGENTS.md — AI 코딩 에이전트 개발 가이드

이 저장소(`commata/minecraft_plugin`)에서 코드를 쓰는 **모든 AI 에이전트**(Claude, Codex, Cursor, Copilot 등)가 따르는 규칙입니다.
사람 개발자도 같은 규칙을 씁니다. 여기 적힌 규칙은 **지금 저장소의 코드에서 실제로 쓰는 방식**을 정리한 것이고,
외부 자료(예: 사용자가 올린 개발 핸드북 PDF)와 다를 때는 이 문서와 코드가 이깁니다. 다른 점은 [docs/HANDBOOK-REVIEW.md](docs/HANDBOOK-REVIEW.md)에 정리했습니다.

> 기준: 2026-11-26, `main` 3bbc507 + 열린 PR #11(`common.gui`, EconomyHub 메뉴·스킬). PR #11이 머지되기 전에는 `kr.commata.common.gui`가 `main`에 없습니다.

---

## 0. 먼저 읽을 것과 우선순위

| 순서 | 문서 | 내용 |
| --- | --- | --- |
| 1 | 이 문서 | 원칙·구조·컨벤션·금지 사항 |
| 2 | [docs/PLAN.md](docs/PLAN.md) | 개발 환경, 빌드 기준(버전 고정), 배포 구조 |
| 3 | [docs/ECONOMY.md](docs/ECONOMY.md) | 경제·직업·상점·Market·Trade·스킬 기획 (**확정 사항의 원본**). 7.4 = 모든 자체 GUI 레이아웃, 8장 = 악용 방지 체크리스트 |
| 4 | [docs/DEPLOY.md](docs/DEPLOY.md) | 운영 서버 백업·배포·되돌리기 |
| 5 | [server-config/economy/README.md](server-config/economy/README.md), `VERSIONS.md` | 외부 플러그인 설정과 고정 버전 |
| 6 | [docs/HANDBOOK-REVIEW.md](docs/HANDBOOK-REVIEW.md) | 개발 핸드북 PDF를 코드와 대조한 결과 (채택·불채택·미검증) |
| 7 | [docs/STATUS.md](docs/STATUS.md) | **지금 진행 상황**: 현재 단계, PR 상태, 인게임 피드백·버그 기록, 테스트 기록, 미해결 사항 (규칙 아님) |

충돌하면 이 순서로 따릅니다.

1. **사용자(저장소 주인)가 대화·PR에서 내린 결정** — 문서에 아직 없으면 그 결정을 문서에 먼저 반영합니다.
2. `docs/ECONOMY.md`, `docs/PLAN.md`의 "확정" 항목
3. 이 문서
4. 지금 코드의 관례 (위 문서에 없을 때)
5. 외부 자료 (핸드북 PDF, 웹 글). **검증 없이 규칙으로 옮기지 않습니다.**

기획이 정하지 않은 동작(가격, 보상, 쿨타임, 레이아웃, 확률 등)을 에이전트가 임의로 정하지 않습니다. 추천안을 문서에 "제안"으로 적고 사용자 결정을 받습니다.

---

## 1. 프로젝트 한눈에

- 운영: Ubuntu 미니PC, Docker 컨테이너 `minecraft-paper`(`itzg/minecraft-server:java25`), 데이터 `/home/deploy/services/minecraft/data` → `/data`
- 서버: **MC 26.3, Paper `26.3-258`, Java 25**. 화이트리스트 지인 야생 서버(easy, keepInventory). 기존 플러그인 **Chunky, CoreProtect**
- 개발 PC: Windows, IntelliJ + Gradle, `C:\minecraft\minecraft_plugin`
- 빌드: Gradle Kotlin DSL 멀티 모듈, `build-logic` convention plugin, Shadow, run-paper

### 모듈

| 경로 | 플러그인 이름 (`plugin.yml`) | 도메인 | 상태 |
| --- | --- | --- | --- |
| `common/` | (라이브러리) | 메시지(MiniMessage), 쿨다운, 시간 표기, GUI 바탕(`gui`, PR #11) | 각 플러그인 JAR에 relocate 포함 |
| `plugins/custom-items/` | `CustomItems` | 커스텀 아이템·스킬(로드맵 1단계) | 운영 배포됨 (0.1.0) |
| `plugins/travel/` | `Travel` | `/home`, `/sethome`, `/tpa` (예전 `Home`) | 머지됨 |
| `plugins/pets/` | `Pets` | `/callpet` | 머지됨 |
| `plugins/bank/` | `Bank` | 화폐(원) 원장, Vault 경제 제공자, `/money` `/pay` `/baltop` `/bank` | 머지됨, 운영 미배포 |
| `plugins/economy-hub/` | `EconomyHub` | VJobs 보완(탈퇴 후 쿨타임, 피스톤·유체 블록 보상 제외, XP 유지), `/economy`·`/직업` 메뉴, 직업 편의 스킬 | 1b 머지, 1.5 = PR #11 |
| `plugins/market/` (예정) | `Market` | `/희귀상점`, `/뽑기`, `/판매` (ECONOMY.md 13·14장) | 1.7단계, 미착수 |
| `plugins/trade/` (예정) | `Trade` | `/trade` 1:1 교환 (ECONOMY.md 7.3, 7.4.6) | 2단계, 미착수 |
| `server-config/economy/` | — | VJobs·EconomyShopGUI 운영 설정, 외부 JAR 버전·SHA-256 | |
| `scripts/` | — | `deploy.ps1`(Windows), `server/*.sh`(미니PC) | |

외부 플러그인: VaultUnlocked 2.20.3(plugin.yml 이름 **`Vault`**), VJobs 2.2.0-RELEASE, EconomyShopGUI 7.3.4(무료판), LuckPerms 5.5.x.

---

## 2. 절대 규칙 (어기면 운영 서버나 경제가 깨짐)

1. **운영 서버를 직접 건드리지 않습니다.** 에이전트는 SSH·배포·`docker` 명령을 실행하지 않습니다. 배포는 사용자가 `scripts/deploy.ps1`로 합니다.
2. **Chunky, CoreProtect는 절대 설치·교체·설정 변경 대상이 아닙니다** (`mc-env.sh`의 `PROTECTED_PLUGINS`).
3. **`/reload` 금지.** 플러그인 변경은 항상 재시작으로 반영합니다. 코드도 `/reload`를 전제로 하지 않습니다.
4. **이미 운영에 쓰인 저장 키·파일 이름을 바꾸지 않습니다.** 예: Travel의 `home:*` PDC 키, `economyhub:u<구역>`, `economyhub:last_job_leave`, `plugins/EconomyHub/job-xp.tsv`, `bank.db`. 바꿔야 하면 마이그레이션과 사용자 승인이 먼저입니다.
5. **버전은 `gradle.properties`에서만, 서버와 같은 빌드로 고정**합니다(`paperApiVersion=26.3.build.258-stable`). `26.3.build.+` 같은 범위 버전 금지 (PLAN.md 0장: 서버보다 새 API로 컴파일되면 로드 실패).
6. **돈은 정수 원(`long`)만.** 소수 금액은 반올림하지 않고 거부합니다(ECONOMY.md 2.2, `Amounts.check`). 금액 상한 `Amounts.MAX = 2^53-1`.
7. **성공은 디스크에 반영된 뒤에만** 반환합니다. DB는 커밋 후, 파일은 fsync 후. 결과를 모르면 "성공"도 "실패"도 아닌 **불확실** 상태로 다루고 자동으로 되돌리지 않습니다 (5장).
8. **불확실하면 막습니다 (fail-closed).** 기록 파일을 못 읽음 → 해당 기능(탈퇴·판매·뽑기) 잠금. 데이터 폴더가 있는데 DB가 없음 → 새로 만들지 않고 잠금 모드 (`Bank` `Bootstrap`).
9. **기존 월드·플레이어 데이터와 기존 플러그인 동작을 바꾸지 않습니다** (ECONOMY.md 1장 6번). 경제 기능은 선택이고, 안 해도 손해가 없어야 합니다.
10. 비밀 정보(SSH 주소·키, 토큰)는 커밋하지 않습니다 (`*.env`, `deploy.local.*`는 `.gitignore`).

---

## 3. 아키텍처 원칙

### 3.1 플러그인 하나 = 도메인 하나 = JAR 하나

- 기능을 도메인별 플러그인으로 나눕니다. **같은 상태(전투 태그, 시전 등)를 공유해야 하면 한 플러그인으로 합칩니다** — 예: `/home`과 `/tpa`를 `Travel`로 합친 이유는 "맞은 직후 `/home`은 막히는데 `/tpa`로 도망치는" 구멍을 없애기 위해서입니다.
- 자체 플러그인끼리 **컴파일 의존을 만들지 않는 것이 기본**입니다. 연동은 다음 중 하나로 합니다.
  - 공통 계약 서비스: 돈은 모두 Vault `Economy` 인터페이스(`ServicesManager`)로. `Bank`를 직접 부르지 않습니다(ECONOMY.md 3장).
  - 예외(설계 확정): `Market`은 거래번호 멱등성 때문에 `softdepend: [Bank]`로 Bank Java API(`withdraw/deposit/findByExtRef`, 스키마 v3)를 부릅니다 (ECONOMY.md 13.6). **이 API는 아직 구현되지 않았습니다.**
  - 다른 플러그인 기능을 여는 버튼은 그 명령을 실행합니다(`player.performCommand`, 다음 틱). EconomyHub `/economy`가 이 방식입니다.
- `plugin.yml`의 `depend`/`softdepend`/`loadbefore`로 켜지는 순서를 명시합니다. 예: Bank `depend: [Vault]`, `loadbefore: [VJobs, EconomyShopGUI, EconomyHub]`; EconomyHub `softdepend: [VJobs]`.

### 3.2 `common`은 작고, 플러그인마다 relocate

- `common`은 Bukkit에 가볍게 기대는 유틸만 둡니다(`text.Messages`, `cooldown.Cooldowns`, `time.Durations`, `gui.*`).
- Shadow가 `kr.commata.common` → `kr.commata.<모듈명에서 - 뺀 것>.libs.common`으로 옮깁니다(`paper-plugin-conventions`). 그래서 **플러그인마다 `MenuListener`를 하나씩 등록**하고, 다른 플러그인의 `Menu`와 섞이지 않습니다.
- 두 개 이상 플러그인이 쓰는 코드가 생기면 `common`으로 올립니다. 한 플러그인만 쓰면 그 플러그인 안에 둡니다. 도메인 로직(돈, 직업 규칙)은 `common`에 넣지 않습니다.
- 별도 "라이브러리 플러그인"을 서버에 깔지 않습니다.

### 3.3 외부 플러그인 연동

- 외부 JAR은 **문서만 믿지 않고 JAR 안의 클래스·`plugin.yml`·기본 설정을 직접 확인**한 뒤 씁니다(ECONOMY.md 2장, 메모: VaultUnlocked 이름이 `Vault`, ESGUI 슬롯이 1-based/0-based 섞임 등). 확인한 사실은 문서에 남깁니다.
- 공개 Maven 저장소가 없는 플러그인(VJobs)은 **컴파일 의존 없이 리플렉션**으로 씁니다(`economyhub/jobs/VJobsHook`). 클래스 이름은 상수로 모으고, 연결 실패 시 예외로 죽지 않고 **그 기능만 끄고 경고 로그 + 상태 명령(`/economyhub status`)에 표시**합니다.
- 외부 플러그인의 동작을 덮어쓸 때는 그 플러그인의 이벤트 순서를 확인하고 주석으로 남깁니다(예: VJobs `onJoin` LOWEST, `onQuit` NORMAL).
- 외부 JAR 버전·SHA-256은 `server-config/economy/VERSIONS.md`에 고정합니다. 크랙·비공식 빌드 금지.

### 3.4 구성 루트와 의존성 주입

- 메인 클래스(`*Plugin extends JavaPlugin`, `final`)의 `onEnable()`이 **구성 루트**입니다. 저장소 → 서비스 → 리스너·명령 순으로 만들고 **생성자로 넘깁니다.**
- `static getInstance()`·전역 싱글턴 금지. 지금 코드에 하나도 없습니다.
- `onDisable()`은 만든 순서의 반대로 정리합니다: 열린 메뉴 닫기(`MenuListener.closeAll`) → 타이머·작업 취소 → 저장소 닫기 → 서비스 등록 해제.
- 기능 일부가 준비되지 않아도 플러그인 전체를 끄지 않습니다. 해당 기능만 끄고 이유를 로그와 상태 명령에 남깁니다. (예: Bank 잠금 모드에서도 Vault 제공자는 등록해서 모든 입출금이 실패하도록 함 — 다른 플러그인이 "경제 없음"으로 착각해 무료로 동작하지 않게.)

### 3.5 순수 로직과 Bukkit 코드 분리 (테스트 가능하게)

판단 로직은 Bukkit 없이 돌아가는 클래스로 떼어 JUnit으로 테스트합니다. 이 저장소의 테스트는 전부 이 계층입니다.

| 플러그인 | 순수 로직 패키지 | 예 |
| --- | --- | --- |
| Bank | `bank.core` | `Amounts`, `BankStore`(SQLite), `Bootstrap`, `PlayerFiles`, `Sources` |
| Travel | `travel.logic`, `travel.model` | `ActionClassifier`, `PlayerSafety`, `RequestBook`, `ConfigMigration` |
| Pets | `pets.logic` | `PetIndex`, `SpotSearch` |
| EconomyHub | `guard.Marks`, `jobs.CooldownPolicy`, `JobXpStore`, `LeaveTimeStore`, `SavedJobXp`, `menu.JobSlot`, `skills.CaveVisionRule` | |
| common | `Cooldowns`, `Durations`, `gui.ClickLock` | 시간은 `Clock`/`LongSupplier`/`nowMs` 인자로 주입 |

- 시간·난수·파일 경로는 인자로 받습니다(`Clock`, `LongSupplier`, `Path`). `System.currentTimeMillis()`를 로직 안에서 직접 부르지 않습니다.
- Bukkit 객체(`Player`, `Block`)를 순수 로직에 넘기지 않고 UUID·좌표·`Material` 같은 값으로 바꿔 넘깁니다.

---

## 4. 패키지·파일 구조

### 4.1 새 플러그인 추가 순서

1. `plugins/<kebab-name>/` 폴더, `settings.gradle.kts`에 `include(":plugins:<kebab-name>")`
2. `build.gradle.kts`: `plugins { id("paper-plugin-conventions") }` + 필요하면 `implementation(project(":common"))`. 서버가 제공하는 것(Paper, Vault API, sqlite-jdbc)은 `compileOnly`
3. `src/main/resources/plugin.yml` (아래 템플릿), `config.yml`(있으면)
4. 패키지 `kr.commata.<모듈명에서 - 뺀 것>` (예: `economy-hub` → `kr.commata.economyhub`), 메인 클래스 `<Name>Plugin`
5. `README.md` 모듈 표·명령어 표, 필요하면 `docs/` 기획 문서 갱신
6. 배포 대상이면 `scripts/deploy.ps1 -Plugin <kebab-name>`로 동작하는지 확인(이름이 바뀌는 경우 `mc-env.sh` `RENAMED_PLUGINS`)

```yaml
name: PluginName            # PascalCase. 운영 후 바꾸지 않음 (데이터 폴더·PDC 네임스페이스가 됨)
version: '${version}'       # gradle.properties 에서 치환
main: kr.commata.pkg.PluginNamePlugin
api-version: '${apiVersion}'
description: 한국어 한 줄 설명
author: commata
depend: []                  # 필요할 때만
softdepend: []
permissions:
  pluginname.admin:
    description: 관리자 명령
    default: op
```

- 메타데이터는 **`plugin.yml`** 입니다. `paper-plugin.yml`·`PluginBootstrap`은 쓰지 않습니다(PLAN.md 2장 결정, 바꾸려면 사용자 승인).
- 명령은 `plugin.yml commands:`가 아니라 `getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, ...)` 안에서 `registrar().register(이름, 설명, [별칭], BasicCommand)`로 등록합니다(모든 플러그인 공통).

### 4.2 플러그인 안 패키지 (필요한 것만 만듦)

| 패키지 | 들어가는 것 |
| --- | --- |
| (루트) | `*Plugin`(구성 루트), 짧은 설정 record, 관리자 명령 |
| `command/` | `BasicCommand` 구현. 인자 파싱 → 서비스 호출 → 메시지. 로직은 두지 않음 |
| `listener/` | Bukkit 이벤트 → 서비스 호출 |
| `service/` | 유스케이스(여러 저장소·규칙을 묶음). Travel `HomeService`, `TeleportRequestService` |
| `logic/` 또는 `core/` | Bukkit 비의존 순수 규칙·상태. **단위 테스트 대상** |
| `model/` | 값 객체(record) |
| `storage/` | PDC·파일·DB 접근. 키 이름은 여기 한 곳에 상수로 |
| `gui/` 또는 `menu/` | `Menu` 하위 클래스, 메뉴 설정 |
| 도메인 패키지 | EconomyHub처럼 기능이 여럿이면 `guard/`, `jobs/`, `menu/`, `skills/`로 기능별 분리 |
| `vault/` | Vault 연동 어댑터 (Bank) |

테스트는 같은 패키지 경로의 `src/test/java`에 `<Class>Test`로 둡니다.

---

## 5. 데이터와 영속성

### 5.1 어디에 저장하나

| 데이터 | 저장 위치 | 예 |
| --- | --- | --- |
| 플레이어 한 명의 작은 상태(집, 쿨타임 시각, 토글) | 플레이어 PDC (`NamespacedKey(plugin, "snake_case")`) | Travel `home:home`, EconomyHub `skill_toggle.<id>` |
| 블록 좌표 표시 | 청크 PDC 비트맵 (구역별 `long[64]`) | EconomyHub `economyhub:u<구역>` |
| 크래시에도 잃으면 안 되는 작은 기록 | 플러그인 폴더 파일, **임시 파일 → fsync → 원자적 이동** (`AtomicFiles`) | `job-xp.tsv`, `job-leaves.tsv` |
| 돈·거래 원장, 여러 표를 한 번에 바꿔야 하는 것 | SQLite 파일 DB | `plugins/Bank/bank.db`, (예정) `market.db` |
| 운영 설정 | `config.yml` (JAR 기본값 + 서버 파일) | |

- PDC와 외부 DB는 저장 시점이 다릅니다. **둘 사이 정합성이 필요한 값은 PDC에 두지 않습니다** (EconomyHub XP 기록을 PDC에서 fsync 파일로 옮긴 이유).
- 시각은 epoch 밀리초(`long`)로 저장합니다(재시작 중에도 시간이 흐름).
- 아이템 자체에 추적용 PDC/NBT를 붙이지 않습니다(스택이 안 겹침). "직접 얻은 양" 같은 추적은 플레이어별 카운터로 합니다(ECONOMY.md 14.2).

### 5.2 SQLite 규칙 (Bank 방식)

- 파일 DB, `PRAGMA journal_mode=WAL` + `synchronous=FULL` + `foreign_keys=ON` + `busy_timeout`. 설정이 실제로 적용됐는지 다시 읽어서 확인하고, 아니면 열기 실패.
- "검사 → 변경 → 기록"은 **한 트랜잭션**. 커밋이 끝난 뒤에만 성공 반환. 메모리 캐시는 커밋 후 갱신하는 조회용 사본.
- 연결 하나 + `ReentrantLock`. 모든 SQL은 `PreparedStatement` 파라미터 바인딩.
- 스키마 버전은 `meta` 표의 `schema` 값으로 관리하고 `migrate()`로 올립니다(지금 v2). 새 설치 스키마와 마이그레이션 결과가 같은지 테스트합니다.
- 커밋·롤백 결과를 확신할 수 없으면 연결을 닫고 이후 변경을 모두 `STORAGE_ERROR`로 거부합니다(`brokenReason`).
- sqlite-jdbc는 Paper 서버에 들어 있으므로 `compileOnly`(JAR에 넣지 않음).
- **활성 DB 파일을 복사해서 백업하지 않습니다.** 백업은 서버 정지 상태 전체 스냅샷(`backup.sh --offline`)이고, 경제 데이터는 단독 복원하지 않습니다(ECONOMY.md 2.3, 9.2).

### 5.3 결과 타입

- 실패 이유를 `boolean`이나 단일 에러로 뭉치지 않습니다. `enum Failure` + `record Outcome`(Bank), `sealed interface Result permits Ready, Locked`(Bootstrap)처럼 **이유가 드러나는 타입**을 씁니다.
- 외부 저장소가 끼는 경계(돈 ↔ 아이템 ↔ 플레이어 파일)는 **3상태**로 나눕니다: 확정 성공 / 확정 미반영 / 불확실(`UNKNOWN`, `UNREADABLE`). 불확실은 자동 취소·재지급하지 않고 잠근 뒤 재확인합니다(ECONOMY.md 13.6, 14.3).
- 사용자에게 보여 줄 문구는 enum에 한국어로 둡니다(`Failure.message()`).

### 5.4 Paper 26.x 저장 구조

- 플레이어 파일: 월드 루트의 `players/data/<uuid>.dat` (예전 `playerdata`도 확인). `World#getWorldFolder()`는 차원 폴더라 `PlayerFiles.worldRoot()`로 월드 루트를 구합니다.
- `player.saveData()`는 실패해도 예외 없이 돌아올 수 있으므로, 돈과 아이템이 엮이면 디스크 파일을 직접 읽어 확인하는 설계를 따릅니다(ECONOMY.md 13.6, Market 구현 때).

---

## 6. 이벤트·스레드 규칙

- **월드·엔티티·인벤토리·명령 실행은 메인 스레드에서만.** 비동기는 DB heartbeat처럼 Bukkit API를 안 부르는 일만 (`runTaskTimerAsynchronously`).
- 스케줄러는 `getServer().getScheduler()`(BukkitScheduler)를 씁니다. 단일 Paper 서버이고 Folia는 목표가 아닙니다. 한 플러그인 안에서 Folia 스케줄러와 섞지 않습니다.
- 우선순위 사용법(지금 코드 기준):

| 우선순위 | 용도 | 예 |
| --- | --- | --- |
| `LOWEST` | 다른 플러그인보다 먼저 상태를 읽어 둠 | PayoutGuard 블록 표시 확인, JobXpKeeper 접속 |
| `NORMAL`(기본) | 일반 처리 | |
| `HIGH`/`HIGHEST` | **취소·수정이 필요한 최종 결정** | VJobs 보상 취소(HIGH), 탈퇴 전 XP 기록·실패 시 취소(HIGHEST), 메뉴 클릭 차단(HIGHEST) |
| `MONITOR` | **결과만 읽음.** 기록·정리·후속 작업 | 피스톤 표시, 시전 취소 감지, 펫 위치 기록 |

- **`MONITOR`에서 이벤트를 취소하거나 내용을 바꾸지 않습니다.**
- 이미 취소된 이벤트를 처리할 이유가 없으면 `ignoreCancelled = true`. 메뉴 차단처럼 "다른 플러그인이 취소를 풀어도 다시 막아야" 하는 경우만 일부러 뺍니다(주석 필수).
- 이벤트 안에서 창 닫기·다른 명령 실행·다른 플러그인이 아직 처리 중인 상태 읽기는 **다음 틱**(`runTask`)으로 미룹니다.
- 다중 블록 파괴는 블록을 직접 공기로 바꾸지 않고 `player.breakBlock(block)`으로 해서 내구도·행운·CoreProtect·VJobs·판매 집계가 한 경로를 타게 합니다. 재귀 가드 필수(ECONOMY.md 15.5).
- 외부 플러그인 이벤트 순서에 기대는 코드는 클래스 javadoc에 순서를 적습니다(PayoutGuard 참고).

---

## 7. GUI 규칙

**모든 자체 GUI는 `common`의 `kr.commata.common.gui`(PR #11)를 씁니다. 직접 `InventoryClickEvent` 리스너를 새로 만들지 않습니다.**
레이아웃(슬롯 번호·아이콘·문구)은 [ECONOMY.md 7.4](docs/ECONOMY.md)가 원본이고, 사용자 시안은 프로젝트 파일 `designs/`에 있습니다.

- `Menu` 하위 클래스 = 화면 하나. `Menu`가 전용 `InventoryHolder`라 **창 제목 문자열로 메뉴를 구분하지 않습니다**.
- `draw(viewer)`에서 `set(slot, item, click)`만 호출. 놓지 않은 칸은 자동으로 `GRAY_STAINED_GLASS_PANE`(이름 `" "`)으로 채워집니다.
- `MenuListener`(플러그인당 하나 등록)가 위·아래 인벤토리의 클릭·Shift 클릭·숫자키·`F`·더블클릭 모으기·드래그·크리에이티브 복제를 **전부 취소**하고, 위 창 버튼 클릭만 `ClickLock`(250ms, 플레이어별)을 거쳐 넘깁니다.
- 닫기 버튼은 우측 하단 `BARRIER`(`Items.close()`). 아이콘은 바닐라 아이템만, 한 칸에 하나. 최대 6줄.
- 이름·로어는 MiniMessage(`Items.text`, 기울임 자동 끔). 색: 제목·이름 `#F5C542`, 설명 `#AAAAAA`, 실행 안내 `#55FF55`, 경고·불가 `#FF5555`, 강조 수치 `#FFDF6B`, 현재 탭 `#55FFFF`. 실행 안내 줄은 `▶ 클릭하여 …`.
- 거래 성공 뒤 창을 닫지 않고 `render(viewer)`로 **제자리 갱신**. 제목에 숫자가 있으면 `retitle`.
- 버튼 핸들러 안에서 창 닫기·명령 실행은 다음 틱.
- 클릭 락은 체감용입니다. **중복 처리 방지는 각 기능의 "플레이어당 진행 중 거래 하나" 규칙과 서버 측 재검증**이 맡습니다.
- 기능을 제공하는 플러그인이 없으면 `GRAY_DYE` + `[준비 중]`.
- `onDisable`에서 `MenuListener.closeAll(getServer())`.
- 아이템을 넣고 빼는 창(`/trade` 본인 칸)만 예외이고, 그때도 허용 동작을 화이트리스트로 직접 처리합니다(ECONOMY.md 7.4.6).
- 텍스트·금액 입력에 NMS·패킷 기반 라이브러리(AnvilGUI, SignGUI)를 넣지 않습니다. 입력 방식은 기획에서 정합니다(Paper Dialog API는 후보, 26.3-258 API로 확인 후 제안).
- Travel의 `TpaMenu`는 `common.gui` 이전 코드입니다. 고칠 일이 생기면 `common.gui`로 옮깁니다.

---

## 8. 코딩 컨벤션

- **Java 25.** `record`(값·결과), `sealed interface` + 패턴 매칭 `switch`(결과 분기), `var`는 타입이 명확할 때만. 클래스는 기본 `final`.
- 들여쓰기 4칸, UTF-8. import 와일드카드 없음.
- **주석·javadoc·로그·사용자 메시지는 한국어.** 클래스 javadoc 첫 줄에 "무엇을, 왜"를 쓰고, 기획 근거가 있으면 `(ECONOMY.md 7.2)`처럼 절 번호를 붙입니다. 외부 플러그인·Paper의 확인한 동작(버전 포함)은 주석으로 남깁니다.
- 사용자 메시지: MiniMessage(`Messages.parse`) 또는 Adventure `Component`. `ChatColor`·`§` 코드 금지. 금액 표기는 `Amounts.format` → `12,340원`. 남은 시간은 `Durations.korean` → `4분 30초`.
- 설정: `saveDefaultConfig()` 후 읽기. 키가 없으면 JAR 기본값으로 대체(EconomyHub `MenuSettings`). 잘못된 값·더 이상 안 쓰는 키는 **경고 로그 후 기본값**. 숫자는 정수. `/<plugin> reload` 명령으로 다시 읽기(관리자 권한).
- 권한: `<pluginlower>.<기능>`(예 `bank.admin`, `home.reload`), 관리자 기능 `default: op`.
- 명령 이름: 영어 + 한국어 별칭(`/economy` = `/경제`, `/money` = `/돈`). 한국어 전용 명령(`/직업`, `/판매`)은 ECONOMY.md에서 정한 것만.
- 콘솔에서 쓸 수 없는 명령은 "게임 안에서만 쓸 수 있습니다." 안내.
- 로그: 기능별 활성 상태를 `onEnable` 마지막에 한 줄로(`EconomyHub 0.1.0 활성화 (탈퇴 후 쿨타임 60분, …)`). 치명적 상태(잠금 모드)는 `severe` 블록으로 해결 방법까지.

---

## 9. 빌드·테스트·CI

- 전체 빌드: `./gradlew build` (Windows `.\gradlew build`). CI(`.github/workflows/build.yml`)가 PR마다 같은 명령을 JDK 25로 돌리고 JAR을 올립니다. **CI 초록이 머지 조건**입니다.
- 로컬 테스트 서버: `.\gradlew :plugins:<모듈>:runServer` (`<모듈>/run/`, Git 제외). 외부 플러그인 JAR은 `run/plugins/`에 직접 넣습니다. 처음에 `whitelist add <닉>`.
- 테스트: **JUnit 5 순수 단위 테스트**만 있습니다(MockBukkit·Mockito 없음). 새 규칙·계산·저장 형식·상태 전이는 테스트를 같이 씁니다. SQLite 테스트는 임시 폴더의 실제 파일 DB로.
- 인게임 확인이 필요한 항목은 PR 본문에 "테스트 서버 확인 항목"으로 적고, 경제 기능은 ECONOMY.md 8장 체크리스트에 추가합니다.
- **클라우드 에이전트 제약:** `repo.papermc.io`, `api.modrinth.com`이 막혀 있어 Gradle 전체 빌드가 안 될 수 있습니다. 그때는 Bukkit 비의존 클래스만 `javac` + junit standalone으로 컴파일·테스트하고, 전체 빌드는 CI 결과로 확인합니다. "빌드 통과"는 CI나 실제 실행 결과가 있을 때만 씁니다.
- 테스트를 끄거나 건너뛰어서 초록을 만들지 않습니다.

---

## 10. 작업 흐름 (브랜치·커밋·PR·문서)

1. 기획이 필요한 기능은 **문서 먼저**: ECONOMY.md(또는 새 docs)에 규칙·숫자·레이아웃을 쓰고 사용자 확인 → 구현.
2. 브랜치에서 작업하고 `main`에 직접 푸시하지 않습니다.
3. 커밋 메시지: `<모듈>: <무엇을 왜>` (예 `EconomyHub: keep job XP records until VJobs has saved them`, `docs: ...`). 영어·한국어 모두 가능, 한 커밋 한 주제.
4. PR 본문: 무엇을 바꿨는지, 관련 문서 절, 테스트(단위 + 테스트 서버 확인 항목), 운영 배포 시 주의(설정·권한·마이그레이션).
5. 같이 갱신: `README.md`(모듈·명령어 표), 해당 docs, 설정 기본값(`config.yml`), 외부 JAR이 바뀌면 `VERSIONS.md`.
6. 리뷰에서 결정이 바뀌면 문서의 "결정 사항" 절과 버전 표기를 갱신합니다(ECONOMY.md는 문서 버전을 올림).

---

## 11. 배포 (참고 — 에이전트는 실행하지 않음)

`deploy.ps1 -Plugin <모듈> [-DryRun|-UploadOnly] [-SshHost minipc|minipc-lan]` → 업로드 → 미니PC `install-plugin.sh`: 알림 → 저장 → **정지 상태 백업** → 같은 이름의 이전 JAR만 `old/`로 이동 → 시작 → 로드 확인. 되돌리기 `rollback.sh`. 경제 첫 배포는 ECONOMY.md 9.2 절차(검증된 설정 선배치, 판매 권한 잠금, 오프라인 기준 백업)를 그대로 따릅니다.
코드가 이 흐름을 깨지 않게: 플러그인 이름 고정, 첫 기동에서 데이터 자동 생성 규칙 명확히(Bank는 폴더가 없을 때만), 재시작만으로 반영.

---

## 12. PR 전 체크리스트

- [ ] 기획 문서와 숫자·슬롯·문구가 일치하고, 새로 정한 것은 문서에 적었다
- [ ] 운영 저장 키·파일·플러그인 이름을 바꾸지 않았다 (바꿨다면 마이그레이션 + 사용자 승인)
- [ ] 버전은 `gradle.properties`만, 범위 버전 없음. 새 의존성은 서버 제공이면 `compileOnly`
- [ ] 돈은 정수, 성공은 커밋·fsync 뒤, 불확실 상태는 잠금·재확인 (자동 되돌리기 없음)
- [ ] Bukkit API는 메인 스레드에서만, `MONITOR`에서 취소·수정 없음
- [ ] GUI는 `common.gui` 사용, 제목 비교 없음, 아이템 이동 전부 차단, 다음 틱 처리
- [ ] 외부 플러그인 연동이 실패하면 그 기능만 꺼지고 로그·상태 명령에 보인다
- [ ] `onDisable`에서 메뉴 닫기·작업 취소·저장소 닫기
- [ ] 순수 로직 단위 테스트 추가, 인게임 확인 항목을 PR에 적음
- [ ] README·docs·config 기본값 갱신

---

## 13. 다중 에이전트 운영 (Orca Orchestration, 2026-11-26 사용자 결정)

영구 Bot(CTO-Claude, DEV-Neon, QA-Astra)은 쓰지 않고, Orca Orchestration으로 실제 Worker를 띄워 일을 나눕니다.

| 역할 | 에이전트 (Orca `--agent`) | 확인된 모델 (2026-11-26) | 맡는 일 |
| --- | --- | --- | --- |
| Coordinator | Claude Code | `claude-opus-5-5` | 피드백 분석, 원인 파악, 설계·범위 결정, 위임, 통합, 최종 리뷰, 문서·커밋·PR, 사용자 보고 |
| DEV Worker | `antigravity` | `gemini-4-neon` | 구현·버그 수정·리팩터링·GUI·단위 테스트, QA 지적 수정 |
| QA Worker | `codex` | 기본 `gpt-6.2-sol` medium (아래 13.1) | 독립 코드 리뷰, 회귀·경계·동시성·경제 악용 검증. DEV 보고를 믿지 않고 실제 diff·테스트로 판단 |

- **규모별 투입**: 소규모(오타·메시지·설정값·원인이 뻔한 버그)는 Coordinator가 직접. 중규모(이벤트·GUI·여러 클래스)는 DEV 또는 QA 중 하나. 대규모·고위험(Bank·거래·DB 구조·동시성·복사 방지·여러 플러그인)은 셋 다.
- **충돌 방지**: 같은 파일을 두 Worker가 동시에 고치지 않습니다. 코드를 고치는 Worker는 별도 worktree(`--worktree new-child`)에서, 읽기 전용 검증은 같은 worktree에서 해도 됩니다. Spec에 수정 가능 파일과 완료 조건(증거)을 적습니다.
- **검증**: QA는 DEV의 커밋·diff 기준으로 검증합니다. 검증되지 않은 작업은 완료가 아닙니다. 테스트 결과는 PASS / FAIL / NOT RUN으로 나누고, 빌드 통과와 인게임 확인을 구분합니다.
- **모델 대체**: 13.1에 적힌 승인 목록 안에서는 사용자 승인 없이 대체할 수 있고, 목록 밖 모델로는 바꾸지 않습니다. 대체했으면 보고에 이유를 적습니다. **Astra 계열은 자동 대체 대상이 아닙니다**(13.2).
- **피드백 처리**: 분석 → 코드·이벤트 흐름·기획 확인 → 근본 원인 → 영향 판단 → 수정(직접/위임) → 테스트 → (필요시) QA → 통합 → 사용자에게 인게임 확인 방법 안내. 관련 없는 문제는 [docs/STATUS.md](docs/STATUS.md) 6장에 기록만, 기획·밸런스 변경은 사용자 확인 후.
- **진행 기록**: 접수한 피드백, 테스트 결과, PR·배포 상태는 `docs/STATUS.md`에 갱신합니다.
- **단계 전환**: PR #14·#15 안정화(피드백 처리 완료, 회귀 테스트 통과, 악용 문제 없음, 문서 갱신) 전에는 다음 로드맵 기능에 착수하지 않고, 조건을 채우면 사용자에게 제안합니다.

### 13.1 QA 모델 선택 (2026-11-26 사용자 결정, 이전 13.1을 대체)

원칙: 검증 품질을 지키면서 토큰·구독 사용량을 최소화합니다. 모델 성능보다 **테스트 코드·정적 분석·실제 실행 결과**로 품질을 확보합니다. 구독 범위 안에서만 쓰고 추가 결제·유료 API는 쓰지 않습니다. 속도는 Standard(Fast/priority 안 씀).

Orca 지정: `worker-start --agent <codex|antigravity> --model <id> --effort <level>`. 모델 ID·강도는 Codex `~/.codex/models_cache.json`, Antigravity Orca `agent-model-catalog.db`에서 확인(2026-11-26). Antigravity의 "Thinking" 변형은 별도 ID가 없어 `--effort`로 정합니다(아래 표의 강도).

| Level | 대상 | 우선순위 (`agent` `model` / `effort`) |
| --- | --- | --- |
| 0 | 오타·메시지·설정값·단순 조건문, 영향이 명확한 소규모 | QA Worker 없음. Coordinator가 직접 검토 + 관련 테스트 |
| 1 경량 | 빌드·테스트 실행, 단순 변경·명령·권한·설정 검증, 정적 분석, 기존 버그 수정 여부, 간단한 테스트 작성 | ① codex `gpt-6-luna` / high ② antigravity `gemini-4-hydrogen` / medium |
| **2 일반 (기본)** | 일반 PR 리뷰, 인게임 버그·GUI·직업·스킬 검증, 리팩터링, 회귀, 일반 예외, 클래스 간 의존 | ① codex `gpt-6.2-sol` / medium ② antigravity `claude-sonnet-5-5` / high ③ codex `gpt-6.1-terra` / medium ④ antigravity `gemini-4-hydrogen` / high |
| 3 심층 | 복잡한 이벤트 처리, 플러그인 간 충돌, 대규모 리팩터링, 복합 원인 분석, 성능 병목, Level 2로 못 푼 문제 | ① codex `gpt-6.2-sol` / high ② antigravity `claude-sonnet-5-5` / high ③ antigravity `gemini-4-neon` / high ④ antigravity `claude-opus-5-5` / high |
| 4 고위험 | 아이템·재화 복사, 데이터 손상·유실, 거래 정합성, Bank 트랜잭션, 동시성, DB 마이그레이션, 비정상 종료 복구, 정식 배포 전 중요 기능 | ① codex `gpt-6.2-sol` / high ② antigravity `claude-opus-5-5` / high ③ antigravity `gemini-4-carbon` / high ④ 다른 모델로 필요한 부분만 제한적 교차 검증 |

- PR #14·#15 안정화는 Level 2가 기본입니다. 경제·거래 경계 변경은 Level 4.
- 고위험이라도 상위 모델을 바로 부르지 않습니다. 먼저 문제를 작게 나누고 재현 테스트를 추가하고, 승인 목록 안의 다른 모델로 교차 검증합니다.
- DEV(`antigravity` `gemini-4-neon`)와 QA는 가능하면 다른 모델 계열을 씁니다. 같은 모델이라도 독립 세션·독립 검증으로 돌립니다.

### 13.2 Astra 계열 사용 제한

`gpt-6.2-astra` 등 **Astra 계열은 기본 사용 금지**이고 자동 대체 목록에도 없습니다. 다른 모델 사용량을 모두 소진해도 Astra로 넘어가지 않습니다.
검토 조건(전부 충족): ① 심각한 문제로 진행이 막힘 ② Sol High와 Antigravity 상위 모델로 충분히 시도함 ③ 테스트·코드 분석만으로 해결이 어려움 ④ Astra의 추가 이점이 명확함 ⑤ **사용자가 명시적으로 승인**.
승인 요청 때 보고: 해결 안 된 문제, 지금까지 쓴 모델, 수행한 분석·테스트, 기존 모델로 못 푼 이유, Astra가 검증할 범위, 예상 추가 사용량. 승인 전에는 Astra Worker를 만들거나 부르지 않습니다.
승인 후: medium·Standard 우선, 해결 못 한 문제만 전달, 이미 검증한 영역 재분석 안 함, 작업 단위 하나, 추가 호출은 다시 승인.

### 13.3 사용량 초과와 대체 순서

- Codex 사용량을 모두 쓰면 Antigravity로 옮깁니다. 일반 순서: ① `claude-sonnet-5-5` / high ② `gemini-4-hydrogen` / high ③ `gemini-4-neon` / medium ④ Coordinator 직접 검증. 고위험은 Sonnet·Neon·Opus·Carbon + 강화된 테스트.
- 실제 환경의 모델 ID·강도와 계정 권한·남은 사용량을 확인합니다. 잠긴 모델·한도 초과 서비스는 반복 호출·재시도하지 않습니다.
- 모든 대체 모델을 못 쓰면 검증 가능한 범위까지만 하고 미검증 항목을 보고합니다.

### 13.4 QA 컨텍스트·반복 검증·사용량

- **QA에 넘기는 것**: 사용자 요청·재현 조건, 기준 SHA·검증 SHA, 변경 파일 목록·diff 범위, 필요한 기획 절, 기대 동작, 핵심 검증 항목, 테스트 명령. QA는 변경과 직접 영향 범위부터 보고, 필요할 때만 더 탐색합니다.
- **하지 않는 것**: 매번 전체 저장소·전체 문서 재분석, 이미 검증한 코드 재검토, 같은 로그·파일 반복 읽기, 불필요한 Worker 중복, 같은 분석을 여러 모델에 무조건 요청, 긴 보고서, 전체 로그 출력, Fast 모드.
- **반복 검증**: 마지막 QA 통과 SHA를 [docs/STATUS.md](docs/STATUS.md) 5장 "QA 기록"에 남기고 그 뒤 diff만 봅니다. 이전 발견의 수정 여부를 먼저 확인하고, 관련 회귀 테스트를 돌립니다. 공통 모듈·DB·경제처럼 영향이 넓으면 범위를 넓히고, 기존 결과를 무효로 만드는 구조 변경이면 전체 검증. 정식 배포 전에는 모델의 전체 재분석보다 **자동화된 전체 회귀 테스트**를 우선합니다.
- **품질은 그대로**: 실제 코드·테스트 근거, 정상·실패 경로, 경계·예외, 아이템·재화 복사, 데이터 정합성, 회귀, 플러그인 호환, 인게임과 모의 테스트 구분, 실행 안 한 테스트는 NOT RUN.
- **QA 작업 중 금지**: `gradlew --stop`(같은 PC의 모든 Gradle 데몬과 로컬 테스트 서버까지 꺼짐, 2026-11-26 실제 발생), 사용자 작업 폴더·DEV worktree에서 Gradle 실행.
- **보고 (간결하게)**: 에이전트·모델·강도와 선택 이유, 검증 커밋·범위, 발견, 수정·재검증 결과, 실행한/못 한 테스트, 대체 여부와 이유, 사용량(확인 가능하면), 남은 위험.
