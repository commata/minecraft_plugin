# 개발 진행 상황 (STATUS)

새 세션·Worker가 이 문서와 [AGENTS.md](../AGENTS.md)만 읽고 이어서 작업할 수 있도록 **지금 상태**를 적습니다.
기획 원본은 [ECONOMY.md](ECONOMY.md), 규칙은 AGENTS.md입니다. 이 문서는 진행 기록만 둡니다(규칙·숫자를 새로 정하지 않음).

> 마지막 갱신: 2026-11-26 (Coordinator: Claude Code)

지금 쓰는 로컬 테스트 서버: `plugins/market/run` (`.\gradlew :plugins:market:runServer`, `localhost:25565`, RCON 127.0.0.1:25575 로컬 전용). 기준 브랜치는 로컬 `test/pr14-pr15`(main + PR #14 + PR #15, push 안 함).

## 1. 현재 단계: PR #14·#15 안정화

- 신규 기능 개발은 **멈춘 상태**입니다. 다음 로드맵(2단계 `Trade` 등)은 AGENTS.md 13장의 전환 조건을 채우고 사용자 승인을 받은 뒤 시작합니다.
- 사용자의 인게임 피드백(버그·불편·밸런스)이 최우선입니다. 접수하면 아래 4장에 적고 처리합니다.

## 2. PR 상태

| PR | 브랜치 | 내용 | 상태 (2026-11-26) |
| --- | --- | --- | --- |
| #14 | `feat/jobs-menu-status-bar` | EconomyHub `/직업` 상태 바·제목 (ECONOMY.md 7.4.2) | OPEN, CI 통과, 리뷰 없음, `main` 위 2커밋 |
| #15 | `feat/market-stage-1-7` | 경제 1.7 `Market` (희귀 상점·뽑기·직업별 매입) + Bank v3 API | DRAFT, CI 통과, `main` 위 9커밋 |

- 두 PR은 서로 독립(파일 겹침: `docs/ECONOMY.md`만). 머지 순서·시점은 사용자가 정합니다.
- 운영 배포: 두 PR 모두 **미배포**. 운영에는 CustomItems 0.1.0 등 머지 전 버전만 있음(Bank·EconomyHub·Market 배포 여부는 사용자 확인 필요).

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
| 2026-11-26 | 농부 매입에 사탕수수·선인장·달콤한 열매·버섯 추가 요청 | 기획 14.4가 "성장이 빨라 매입 안 함"으로 확정돼 있었음 | 사용자 결정으로 ECONOMY.md v0.9.3(63097cd), 구현 39c42fd(DEV-B). QA에서 설치 표시 누락(CRITICAL) → DEV-B 보완 중 | 진행 중 |
| 2026-11-26 | `/economy` 하위 화면에 뒤로가기 없음 | Market 메뉴에 허브 버튼 없음 | `/희귀상점`·`/판매` 45, `/뽑기` 18에 메인 허브(aa3bf4a). 서버 상점(ESGUI)은 하단 바에 명령 동작이 없어 제외 | 구현 완료, 서버 미반영 |

## 5. 테스트 기록

| 날짜 | 기준 커밋 | 명령 | 결과 |
| --- | --- | --- | --- |
| 2026-11-26 | `266439f` (feat/market-stage-1-7) | `.\gradlew build` (로컬 Windows, JDK 25) | PASS — 단위 테스트 168개, 실패 0. 인게임 NOT RUN |
| 2026-11-26 | `fb2ac60` (로컬 `test/pr14-pr15` = main + #14 + #15) | `.\gradlew build`, 로컬 서버 기동(`plugins/market/run`, Paper 26.3-263), RCON 콘솔 점검 | PASS — 7개 플러그인 활성화·ERROR 0, `market status`·`economyhub status` 정상. GUI·채집 인게임 NOT RUN |

### QA 기록 (재검증은 마지막 통과 커밋 이후 차이만, AGENTS.md 13.1)

| 날짜 | 대상 (기준..검증) | QA 모델 | 결과 | 남은 것 |
| --- | --- | --- | --- | --- |
| 2026-11-26 | 통합 리뷰 `9f4da30..fb2ac60` | codex `gpt-6.2-astra` | High 4 · Medium 6 · Low 2 | High 1–4 수정 중 |
| 2026-11-26 | High #4 `266439f..1871906` | codex `gpt-6.2-astra` | 미해결: 생산자 규격 확인 없음(High), Medium 3, Low 1 | DEV-B 보완 중 |
| 2026-11-26 | High #1–3 `266439f..acfa576` | codex `gpt-6.2-astra` | H1 통과. H2·H3 잔여 P1 2 · P2 3 | DEV-A 수정 중 |
| 2026-11-26 | 작물 `1510bd5..39c42fd` | antigravity `gemini-4-neon` (Codex 한도 소진으로 대체) | 가격·API·테스트 65/65 PASS. 신규 작물 설치 mark 누락 CRITICAL | DEV-B 보완 중 |

## 6. 미해결·기술 부채

- PR #14·#15 본문의 "테스트 서버 확인 항목"이 아직 체크되지 않음.
- Travel `TpaMenu`가 `common.gui` 이전 코드 (AGENTS.md 7장, 고칠 일이 생기면 옮김).

## 7. 다음 작업

- High 1–4·작물 보완 → QA 재검증(차이만) → PR #15 반영 → 로컬 테스트 서버 재시작 반영.
- 그다음 사용자 인게임 피드백 대기.
