# 개발 핸드북(PDF) 검토: 실제 코드와 대조한 결과

> 대상: 사용자가 올린 「마인크래프트 플러그인·모드·GUI 개발 — 원칙, 설계 패턴 및 실무 장애·적용 사례 핸드북」(8쪽, 2026-11 작성)
> 대조 기준: `main` 3bbc507 + PR #11 (`claude/project-thread-5w1i09`, 9f62926), docs/PLAN.md·ECONOMY.md v0.9
> 검토일: 2026-11-26

핸드북은 AI로 작성된 문서로 보입니다. 본문에 `[span_xx](start_span)` 같은 편집 흔적이 남아 있고, 스스로 "개별 .java 구현 파일 본문은 직접 대조하지 못했다"고 밝힙니다.
그래서 이 저장소에서는 **핸드북을 규칙의 원본으로 쓰지 않고**, 코드로 확인된 부분만 [AGENTS.md](../AGENTS.md)에 반영했습니다.

## 1. 요약

| 구분 | 내용 |
| --- | --- |
| 그대로 채택 (코드와 일치) | 커스텀 `InventoryHolder`로 메뉴 식별, 메뉴 창 아이템 이동 전면 차단, 회색 유리판 빈칸, 클릭 락, 제자리 갱신, MONITOR 읽기 전용, 비동기 스레드에서 Bukkit API 금지, SQLite WAL + `synchronous=FULL` + `PreparedStatement`, 정지 상태 백업, 정수 화폐, 3상태 결과, 생성자 주입(정적 `getInstance` 없음), 외부 JAR 버전·SHA-256 고정, `/reload` 금지, `player.breakBlock` 경로 통일 |
| 코드와 다름 → **코드 방식 유지** | 2장 표 |
| 아직 구현 안 된 것을 구현된 것처럼 씀 | 3장 표 |
| 이 저장소 범위 밖 | Fabric/NeoForge 모드(핸드북 3장, 4.4, 7장 모드 체크리스트), Mixin, 모드 패킷. 이 프로젝트는 바닐라 클라이언트 + Paper 플러그인만 |
| 검증 안 됨 (규칙 근거로 쓰지 않음) | 4장 |

## 2. 핸드북과 실제 코드가 다른 곳

| # | 핸드북 주장 | 실제 코드·문서 | 이 저장소의 규칙 |
| --- | --- | --- | --- |
| 1 | Paper API는 `26.3.build.+`로 적어야 한다 (1.1, 7장 체크리스트) | `gradle.properties` `paperApiVersion=26.3.build.258-stable`. PLAN.md 0장이 `+`를 **명시적으로 거부** (서버보다 새 빌드로 컴파일되면 로드 실패) | 서버와 같은 빌드로 고정 |
| 2 | `paper-plugin.yml` + `PluginBootstrap`, `join-classpath`를 써야 한다 (1.1, 1.3, 7장) | 모든 플러그인이 `plugin.yml`. PLAN.md 2장 결정 ("`paper-plugin.yml`은 익숙해진 뒤 검토") | `plugin.yml` 유지. 바꾸려면 사용자 승인 |
| 3 | 명령은 `PluginBootstrap`에서 등록 | 모든 플러그인이 `onEnable`의 `LifecycleEvents.COMMANDS` + `BasicCommand` | 지금 방식 유지 (부트스트랩 등록은 2번과 함께 검토) |
| 4 | 스키마 버전은 `PRAGMA user_version` (2.2, 7장) | Bank는 `meta` 표의 `schema` 값 + `migrate()` (지금 v2) | Bank 방식 유지. 새 DB도 같은 방식으로 통일 |
| 5 | Folia 호환 4종 스케줄러(Global/Region/Entity/Async)를 쓰면 좋다 (2.1) | 전부 `BukkitScheduler`(`runTask`, `runTaskTimer`, `runTaskTimerAsynchronously`) | BukkitScheduler 유지. 단일 Paper 서버이고 Folia는 목표 아님. 섞어 쓰지 않음 |
| 6 | `getHolder(false)`로 식별 (4.1, 7장) | `common.gui.MenuListener`, Travel `TpaMenuListener` 모두 `getHolder()` | 지금은 `common.gui`를 그대로 씀. `getHolder(false)`(스냅샷 생성 안 함)로 바꾸는 것은 **개선 후보**: `MenuListener` 한 곳만 바꾸면 되고, 26.3-258 javadoc 확인 후 별도 PR |
| 7 | 클릭 락은 거래·구매·뽑기 버튼에 200–300ms (4.1) | `MenuListener.CLICK_LOCK_MS = 250`, **모든 메뉴 클릭**에 플레이어별 적용. 중복 처리 방지는 기능별 "플레이어당 거래 하나" 규칙이 맡음 (ECONOMY.md 7.4) | 코드 방식 |
| 8 | 핵심 지표는 "15자 이내 창 제목"에 (4.1) | ECONOMY.md 7.4에 글자 수 규칙 없음. 제목은 화면별로 확정 (`직업 관리 (%joined%/2)` 등) | 7.4 레이아웃이 원본 |
| 9 | 테스트 2층에 MockBukkit + Mockito (2.3) | MockBukkit·Mockito 의존성 없음. 테스트 20개 파일(PR #11 포함) 모두 Bukkit 비의존 JUnit 5 | 순수 로직 분리 + JUnit. MockBukkit 도입은 사용자 결정 사항 |
| 10 | VaultUnlocked의 BigDecimal·다중 통화·비동기 API 활용 (1.3) | Bank·EconomyHub는 **구 Vault `Economy` 인터페이스만** (`VaultAPI 1.7.1`, ECONOMY.md 2장 v0.4 결정) | 구 Vault API만 |
| 11 | 대량 쓰기는 전용 단일 SQLite 워커로 메인 스레드 분리 (2.2) | Bank는 호출한 스레드에서 락을 잡고 동기 커밋(Vault 호출이 동기라 결과를 바로 돌려줘야 함). heartbeat만 비동기 | Bank 방식 유지. 커밋 지연은 테스트 서버에서 실측 (핸드북의 "실측" 권고는 유효) |
| 12 | `/bank refund`는 EconomyShopGUI·Market 출금으로 제한 (6.2) | 코드는 **EconomyShopGUI 출금만** (`Failure.NOT_SHOP`, `Sources.SHOP`) | 코드가 맞음. Market 환불 경로는 Market 설계(13.6)의 tx_id 복구가 대신함 |
| 13 | 입력은 AnvilGUI 대신 Paper Dialog API로 (4.2) | 아직 입력 UI 없음. ECONOMY.md 7.4.6은 "클릭 시 금액 입력"까지만 정함 | NMS 라이브러리 금지는 채택. Dialog API는 `/trade` 기획 때 26.3-258 API로 확인 후 제안 |

## 3. 핸드북이 "적용됨"이라고 썼지만 아직 없는 것

| 핸드북 위치 | 주장 | 실제 상태 |
| --- | --- | --- |
| 6.2 | Bank 스키마 v3, `withdraw/deposit(…, extRef)`, `findByExtRef`, `ext_ref UNIQUE` | **설계만** (ECONOMY.md 13.6). 코드는 스키마 v2, 해당 API 없음 |
| 6.2 | Market의 PENDING 선기록, `.dat` 직접 검증, `market:seq`, `AsyncPlayerPreLoginEvent` 거절, `recovery/` 대피 | **설계만**. `plugins/market` 없음 (1.7단계) |
| 6.3 | EconomyHub가 설치한 광석·원목·호박·수박 좌표를 청크 PDC에 기록하고 Market과 공유 | 지금 `UntrustedBlocks`는 **피스톤 도착 좌표와 유체 생성 블록만** 표시. 설치 블록 기록은 Market 1.7단계에서 만들 예정 (ECONOMY.md 15.5) |
| 6.3 | 광맥 채굴·일괄 벌목·3×3 수확, 자석, 나뭇잎 급속 소멸 | PR #11은 **1차(Lv.10/20)만**. 연쇄 파괴·3×3은 2.5b, 설치 기록 이후 |
| 6.4 | `/희귀상점`·`/뽑기`·`/판매`·`/trade` 전 화면에 커스텀 홀더 등 "통일 적용" | 코드가 있는 것은 EconomyHub `/economy`·`/직업`·스킬 설정(PR #11, **미머지**)과 Travel `/tpa`뿐. 나머지는 ECONOMY.md 7.4 레이아웃만 확정 |
| 6.1 | `plugins/trade`가 `TpaMenu` 구조를 재사용 | 계획 (ECONOMY.md 7.3). 모듈 없음 |
| 6.4 | GUI 개편이 "PR #10 및 v0.9"에서 | `/shop` 디자인은 PR #10(EconomyShopGUI 설정), 자체 GUI 레이아웃은 ECONOMY.md 7.4(PR #11) |

## 4. 검증하지 않은 외부 주장

다음은 핸드북에 사례·근거로 나오지만 이 검토에서 원문을 확인하지 않았습니다. 규칙의 근거로 인용하지 않습니다.

- `CVE-2026-55624`(Lost-Auction), UI-Utils-26x·ACAudit 공격 벡터, Paper #1440·#11221, FragGuard·MPDB 장애, Create #8508/#9864/#7426, Moonrise × Puzzles Lib #138, LuckPerms #4211/#4221
- Mineflayer 4.39.0이 26.1까지만 지원, MockBukkit v26.3 존재, InvUI 2.4–2.5·Triumph-GUI 3.1.13·IF 0.12.2의 26.3 지원 여부
- Paper 26.2+ 침대 PDC 제거, Data Component API의 `@Experimental` 해제 범위, Timings no-op
- 하이퍼팜 서버 관련 서술 (ECONOMY.md 13.3에 2026-11-25 직접 확인한 내용이 따로 있음)

필요해지면 해당 Paper/라이브러리 공식 문서나 26.3-258 javadoc으로 확인한 뒤 ECONOMY.md·AGENTS.md에 반영합니다.

## 5. 핸드북에서 가져와 AGENTS.md에 반영한 것

코드에 이미 있는 원칙을 이름 붙여 정리하는 데 쓴 항목입니다.

- "경계면에서 사고가 난다"(5장 결론): 스레드·저장소·플러그인 경계마다 명시적 계약과 fail-closed (AGENTS.md 2장 7–8번, 5.3)
- MONITOR 읽기 전용, `ignoreCancelled` 규율 (AGENTS.md 6장)
- 하단 인벤토리 Shift 클릭·더블클릭·숫자키·`F` 경로까지 차단 (AGENTS.md 7장, 코드는 이미 그렇게 함)
- NMS 기반 입력 라이브러리 금지 (AGENTS.md 7장)
- 활성 SQLite 파일 복사 백업 금지 (AGENTS.md 5.2)
