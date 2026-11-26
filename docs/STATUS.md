# 개발 진행 상황 (STATUS)

새 세션·Worker가 이 문서와 [AGENTS.md](../AGENTS.md)만 읽고 이어서 작업할 수 있도록 **지금 상태**를 적습니다.
기획 원본은 [ECONOMY.md](ECONOMY.md), 규칙은 AGENTS.md입니다. 이 문서는 진행 기록만 둡니다(규칙·숫자를 새로 정하지 않음).

> 마지막 갱신: 2026-11-26 (Coordinator: Claude Code)

지금 쓰는 로컬 테스트 서버: `plugins/market/run` (`localhost:25565`, RCON 25575 로컬 테스트 전용). Gradle 데몬이 꺼지면 같이 꺼지므로 `runServer` 대신 `java -jar ~/.gradle/caches/run-task-jars/paper/jars/26.3/263.jar nogui`를 그 폴더에서 직접 실행하고, 플러그인 JAR(bank·economy-hub·market)은 `run/plugins/`에 복사합니다. 종료는 RCON `stop`. PR #14·#15 머지 후에는 `main` 빌드가 기준. Claude 백그라운드 셸로 띄우면 세션 재시작 때 꺼지므로 PowerShell `Start-Process -WindowStyle Minimized`로 띄움.

## 1. 현재 단계: 1.7까지 머지 완료 → 테스트 서버 검증·운영 첫 배포 준비

- 1a Bank, 1b 외부 플러그인 설정 + EconomyHub 직업 보호, 1.5 `/economy`·`/직업`, 1.7 Market, 2.5a 편의 스킬 코드가 모두 `main`에 있습니다. **운영에는 아직 경제 플러그인이 하나도 배포되지 않았습니다.**
- 새 기능(2단계 `/trade`, 2.5b)은 아직 시작하지 않습니다. 아래 7장 "다음 작업" 1–2를 마치고 사용자 승인을 받은 뒤 시작합니다(AGENTS.md 13장 단계 전환).
- 사용자의 인게임 피드백이 계속 최우선입니다(4장).

## 2. PR 상태

| PR | 내용 | 상태 (2026-11-26) |
| --- | --- | --- |
| #14 | EconomyHub `/직업` 상태 바·제목 (ECONOMY.md 7.4.2) | MERGED `73e506f` |
| #15 | 경제 1.7 `Market` + Bank v3 API + 안정화(High 4, 인게임 피드백 3) | MERGED `9f88540` |
| #16·#17·#18 | 다중 에이전트 운영·QA 모델 정책 문서 | MERGED |

## 3. 회귀 확인 항목 (이전 수정)

| 항목 | 코드 | 상태 |
| --- | --- | --- |
| 농작물 재식재 처리 순서 | EconomyHub `SkillListener.onBreak`(MONITOR) → 1틱 뒤 `replantCrop`, Market `QuotaTracker.onBlockDrop`(NORMAL)이 `SkillListener.onBlockDrop`(HIGHEST)보다 먼저 집계 | 코드 확인, 인게임 미확인 |
| 농부 첫 수확 (씨앗 없이도 재파종) | `SkillListener.onBlockDrop` 이 드롭에서 씨앗 1개를 먼저 뗌(70c4e41), 실패 시 되돌려 줌 | 코드 확인, 인게임 미확인 |
| 씨앗 겸용 작물 판매 가능량 | Market `SeedCrops` 수확마다 1개 빼기(a771418, `SeedCropsTest`) | 단위 테스트 PASS |
| 직업 스킬 해금 안내 | 가입·레벨업 후 1회 안내, PDC `economyhub:skill_notified.<id>` (70c4e41) | 코드 확인, 인게임 미확인 |

## 4. 인게임 피드백·버그 기록

| 접수일 | 내용 | 원인 | 처리 | 상태 |
| --- | --- | --- | --- | --- |
| 2026-11-26 | 농부 매입에 사탕수수·선인장·달콤한 열매·버섯 추가 요청 | 기획 14.4가 "성장이 빨라 매입 안 함"으로 확정돼 있었음 | 사용자 결정으로 ECONOMY.md v0.9.3(63097cd), 구현 39c42fd·1ab34e4·7899a30(설치 표시·버섯 퍼짐) | 머지(#15), 인게임 확인 대기 |
| 2026-11-26 | `/economy` 하위 화면에 뒤로가기 없음 | Market 메뉴에 허브 버튼 없음 | `/희귀상점`·`/판매` 45, `/뽑기` 18에 메인 허브(aa3bf4a). 서버 상점(ESGUI)은 하단 바에 명령 동작이 없어 제외 | 머지(#15), 인게임 확인 대기 |
| 2026-11-26 | `/판매`에 묶음(한 세트) 판매 요청 | 좌클릭 1단위·우클릭 전부만 있었음 | Shift+좌클릭 한 칸(최대 64개) 판매(f4837a0, common `setShiftable` opt-in), 클릭별 "지금 N개 · +M원" 안내(716ee4a) | 머지(#15), 인게임 확인 완료(사용자) |

## 5. 테스트 기록

| 날짜 | 기준 커밋 | 명령 | 결과 |
| --- | --- | --- | --- |
| 2026-11-26 | `266439f` (feat/market-stage-1-7) | `.\gradlew build` (로컬 Windows, JDK 25) | PASS — 단위 테스트 168개, 실패 0. 인게임 NOT RUN |
| 2026-11-26 | `fb2ac60` (로컬 `test/pr14-pr15` = main + #14 + #15) | `.\gradlew build`, 로컬 서버 기동(`plugins/market/run`, Paper 26.3-263), RCON 콘솔 점검 | PASS — 7개 플러그인 활성화·ERROR 0, `market status`·`economyhub status` 정상. GUI·채집 인게임 NOT RUN |

### QA 기록 (재검증은 마지막 통과 커밋 이후 차이만, AGENTS.md 13.1)

| 날짜 | 대상 (기준..검증) | QA 모델 | 결과 | 남은 것 |
| --- | --- | --- | --- | --- |
| 2026-11-26 | 통합 리뷰 `9f4da30..fb2ac60` | codex `gpt-6.2-astra` | High 4 · Medium 6 · Low 2 | High 1–4 수정 중 |
| 2026-11-26 | High #4 `266439f..1871906` | codex `gpt-6.2-astra` | 미해결: 생산자 규격 확인 없음(High), Medium 3, Low 1 | → 1ab34e4 |
| 2026-11-26 | High #1–3 `266439f..acfa576` | codex `gpt-6.2-astra` | H1 통과. H2·H3 잔여 P1 2 · P2 3 | → c70b171 |
| 2026-11-26 | 작물 `1510bd5..39c42fd` | antigravity `gemini-4-neon` (Codex 한도 소진으로 대체) | 가격·API·테스트 65/65 PASS. 신규 작물 설치 mark 누락 CRITICAL | → 1ab34e4 |
| 2026-11-26 | High #4·작물 보완 `39c42fd..7899a30` | antigravity `gemini-4-neon` | 이전 발견 1–6 모두 해결, 회귀 없음, 테스트 139 PASS | 없음 |
| 2026-11-26 | H2·H3 보완 `acfa576..c70b171` | antigravity `gemini-4-neon` | 1·2·3·5 해결, 4 부분(메모리 격리가 거래 진입 검사에 빠짐, P2) | a2b483b 에서 Coordinator 직접 수정(재현 테스트 FAIL→PASS) |
| 2026-11-26 | **마지막 QA 통과 기준: `a2b483b`** (origin/feat/market-stage-1-7) | — | `.\gradlew build` PASS, 실패 0. 로컬 서버 반영, `/market status` 블록 집계 켜짐 | → f4837a0 |
| 2026-11-26 | Shift+좌클릭 판매 `a2b483b..f4837a0` | antigravity `claude-sonnet-5-5` high (Codex 한도로 Level 2 대체 ②) | 문제 없음, common·market·economy-hub 테스트 PASS | 없음 |
| 2026-11-26 | **마지막 QA 통과 기준: `716ee4a`** (= main `9f88540` 의 Market) | — (`716ee4a`는 문구만, Level 0) | `.\gradlew build` PASS, CI PASS | 이후 재검증은 이 커밋 이후 diff만 |

## 6. 미해결·기술 부채

- 테스트 서버 확인 항목(ECONOMY.md 8장 체크리스트, PR #14·#15 본문)의 대부분이 아직 체크되지 않음. 사용자 인게임 확인 완료: `/판매` 3가지 클릭. 확인 대기: 신규 작물 판매 가능량(직접 놓은 블록 0개 포함), 메인 허브 버튼.
- 로컬 테스트 서버가 Paper 26.3-**263**(run-paper 최신)이고 운영은 **258**. 배포 전 258 JAR로 한 번 기동 확인 권장.
- 이미 `plugins/Market/config.yml`이 있는 서버는 새 농부 품목 5줄을 직접 추가해야 함(운영은 첫 설치라 해당 없음).
- QA Medium 지적 중 정책 결정이 남은 것: 업그레이드 전에 지은 건축 블록 판정, 환경 피해로 죽은 몹의 사냥 실적, VJobs AFK 판정 불가 시 낚시 집계, 다른 플러그인이 이벤트를 취소해도 집계를 되돌리지 않음, 설치 표시 쓰기 예외 무시, 뽑기 확률창 슬롯 검증, Bank v2 마이그레이션 테스트 보강(9f4da30..fb2ac60 통합 리뷰).
- Travel `TpaMenu`가 `common.gui` 이전 코드 (AGENTS.md 7장, 고칠 일이 생기면 옮김).

## 7. 다음 작업 (남은 개발 단계, ECONOMY.md 7.1 기준)

| 순서 | 단계 | 내용 | 상태 |
| --- | --- | --- | --- |
| 1 | 테스트 서버 검증 (9.1) | 8장 악용 방지 체크리스트, 2인 테스트, 벌목꾼 실측(시간당 원목 1,500개 넘으면 보상 하향), 겸업 합산 수입, Travel·Pets·CustomItems 동시 기동, 258 빌드로 기동 확인 | 진행 중 (일부 인게임 확인됨) |
| 2 | 운영 첫 배포 (9.2) | Bank + 외부 JAR 4개 + EconomyHub + Market 한 번에. 설정 선배치, 판매 권한 잠금, LuckPerms 권한, 오프라인 기준 백업, 일반 권한 검증. **배포는 사용자가 `deploy.ps1`로** | 미착수 |
| 3 | 2. 정책 조정 | 운영 1–2주 데이터(10장 모니터링)로 보상·가격·한도·확률 조정 → ECONOMY.md v1.0 | 배포 후 |
| 4 | 2.5a 실측·조정 | Lv.10·20 편의 스킬은 코드 머지됨. 시간당 수입 상승 +15% 이내인지 실측 | 테스트 서버에서 1번과 함께 |
| 5 | 2. `/trade` 1:1 교환 | 새 플러그인 `plugins/trade` (7.3, 7.4.6). 본인 4×4 칸만 아이템 이동 허용하는 예외 GUI | 미착수, 사용자 승인 후 |
| 6 | 2.5b 편의 스킬 2차 | Lv.30·50 연쇄 파괴(광맥·일괄 벌목·3×3 수확) + Lv.50 칭호. 설치 블록 기록(규격 v1)은 이미 있음 | 미착수, 사용자 승인 후 |
| 7 | 3. 소비 콘텐츠 | 칭호·파티클·펫 치장·커스텀 장식(CustomItems 연동). 리소스팩은 Trade 이후 opt-in 재검토 | 추후 |
