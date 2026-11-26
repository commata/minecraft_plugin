# `/trade` 1:1 교환 — 로직 설계 (Trade 플러그인)

> 상태: **로직 설계 확정** (2026-11-27, 10장 결정 반영). 규칙·레이아웃의 원본은 [ECONOMY.md 7.3·7.4.6](ECONOMY.md)이고, 이 문서는 그것을 어떻게 구현할지(상태·저장·정산·장애 복구)를 정합니다. 남은 "제안"은 구현 방식(사용자 결정 대상 아님)입니다.
> 단계: ① 로직 설계(이 문서) → ② GUI 설계(7.4.6 보강) → ③ 구현(DEV) → ④ QA(Level 4: 아이템·재화 정합성) → ⑤ 피드백 수정 → 테스트 서버 검증 → 운영 첫 배포에 포함(STATUS.md 7장).

## 1. 범위

- 새 플러그인 `plugins/trade`, `plugin.yml` 이름 **`Trade`**, 패키지 `kr.commata.trade`.
- 의존: `depend: [Vault]`(안전망, 실제 돈 이동은 Bank API), `softdepend: [Bank, Travel, EconomyHub]`. Bank가 없거나 잠겨 있으면 **거래 기능 전체 잠금**(돈 0원 거래도 같은 정산 경로를 타게 해서 경로를 하나로 유지, AGENTS.md 2.8, 10장 5).
- 명령: `/trade` (`/거래`) — 접속자 선택 창, `/trade <닉네임>` 요청, `/trade accept|deny [닉네임]`, `/trade status`(관리자), `/trade reload`(관리자), `/trade returns`(받지 못한 반환품 수령). 권한 `trade.use`(기본 모두), `trade.admin`(op).

## 2. 상태 기계

```
            /trade B (A가 요청)
 (없음) ───────────────────────────▶ REQUESTED ──30초 경과·거절·한쪽 로그아웃──▶ (없음)
                                       │ B 수락
                                       ▼
                     ┌──────────────── OPEN ◀────────────────┐
                     │  아이템·금액 변경 → 양쪽 준비 해제       │ 카운트다운 중 변경·준비 해제
                     │  양쪽 준비 완료                         │
                     ▼                                          │
                 COUNTDOWN (3초, 22번 칸 CLOCK 3→2→1) ──────────┘
                     │ 0초 + 재검증 통과
                     ▼
                 SETTLING (한 틱 안에서 끝남, 7장)
                     │
            ┌────────┴────────┐
            ▼                 ▼
         COMPLETED         CANCELLED (반환)
```

- 한 플레이어는 동시에 **세션 하나**(요청 보낸 것·받은 것 포함)만. 이미 세션이 있으면 새 요청 거부.
- 취소 사유(즉시 CANCELLED + 반환): 취소 버튼, 창 닫기, 로그아웃, 사망, 월드 이동·텔레포트(제안: 허용 — 7.3 "거리 제한 없음"이므로 위치는 보지 않음), 전투 태그 발생, 플러그인 종료, 상대 쪽 같은 사유.
- 요청 시점과 정산 직전에 둘 다 **전투 중이 아닌지** 확인(Travel PDC `home:combat_until` 을 읽음 — 컴파일 의존 없이 Market이 EconomyHub 표시를 읽는 방식과 같음, 키 이름은 AGENTS.md 2.4로 고정). Travel이 없으면 전투 검사 없이 진행하고 `/trade status`에 표시.

## 3. 아이템 보관

- 거래 칸(본인 4×4 = 16칸)에 넣은 아이템은 **플레이어 인벤토리에서 빠져 세션 메모리(거래 창 인벤토리)에만 있습니다.** 그래서 강제 종료 대비 기록이 필요합니다(7.3 "아직 정할 것" ①).
- **저널** `plugins/Trade/journal/<tradeId>.bin`: 세션 상태, 양쪽 UUID·닉네임, 양쪽 16칸 내용(`ItemStack#serializeAsBytes`, Paper 26.3-258 확인 예정), 양쪽 제시 금액, 정산 거래번호. **내용이 바뀔 때마다** 임시 파일 → fsync → 원자적 이동(`AtomicFiles`, EconomyHub와 같은 방식). 저널을 쓰지 못하면 그 변경을 되돌리고(아이템은 원래 칸으로) 세션을 취소합니다(fail-closed).
- 쓰기 빈도: 클릭마다 디스크 쓰기는 무거우므로 **제안: 같은 틱의 변경을 모아 틱 끝에 한 번**(Market `QuotaBuffer`처럼), 단 SETTLING 진입 직전에는 반드시 즉시 기록.

## 4. 돈

- 양쪽 다 금액을 제시할 수 있음(A→B 금액, B→A 금액). 정수 원, `Amounts.check`(ECONOMY.md 2.2).
- **확정 순간에만** 이동(7.3). 제시 단계에서는 잔액을 잡아 두지 않고, 정산 직전에 잔액을 다시 확인.
- Bank에 새 API **`settleTrade(extRef, a, aPays, b, bPays, context)`**: 두 방향 출금·입금과 기록을 **한 SQLite 트랜잭션**으로, `extRef = "trade:<tradeId>"` 로 멱등(이미 같은 ref가 있으면 그 결과를 돌려줌). 3장 "돈은 Vault로만"의 예외이며 Market과 같은 근거(멱등성, 7.3 ②). 결과는 3상태 `OK / REJECTED(잔액 부족 등) / UNKNOWN`.
- 양쪽 금액이 모두 0원이면 Bank를 부르지 않음.
- 수수료·거래 한도: **없음**(한도는 잔액, 10장 3).

## 5. 정산 순서 (SETTLING, 메인 스레드 한 틱)

1. 재검증: 둘 다 온라인·같은 세션·전투 아님·저널 최신, 양쪽 제시 금액 ≤ 잔액, **받을 아이템이 들어갈 빈칸**(스택 합치기 포함 시뮬레이션). 하나라도 실패 → OPEN으로 되돌리고 준비 해제 + 이유 안내(취소 아님).
2. 저널을 `SETTLING` + `extRef` 로 즉시 기록(fsync).
3. 돈: `settleTrade`. `REJECTED` → 저널 `OPEN`으로 되돌리고 준비 해제. `UNKNOWN` → **세션 잠금**: 아이템은 거래 창에서 빼서 양쪽 모두 "보류"(반환도 지급도 안 함), 관리자 `/trade resolve <tradeId>` 로 Bank 기록 확인 후 처리(자동으로 되돌리지 않음, AGENTS.md 2.7).
4. 아이템 지급: 각자 받을 아이템을 인벤토리에 넣고, 플레이어 PDC에 `trade:applied` 토큰(`tradeId`)을 붙이고 `saveData()` → 디스크 `.dat`에서 토큰 확인(Market `PlayerDatReader` 방식, 5.4). 양쪽 다 확인되면 저널 `COMPLETED` → 삭제.
5. 디스크 확인이 안 되면(`ABSENT`/`UNREADABLE`) 저널 `DELIVERING` 유지, 1분 타이머·재접속 때 다시 확인(Market과 같은 3상태, 자동 재지급 안 함).

- `PlayerDatReader`(Bukkit 비의존 NBT 파서)는 Market과 Trade 두 곳이 쓰게 되므로 **`common`으로 옮김**(AGENTS.md 3.2). Market 동작은 그대로(리팩터링, 동작 변경 없음).

## 6. 반환

- CANCELLED: 각자 넣은 아이템을 본인 인벤토리로. 빈칸이 모자라면 남은 것을 **발밑에 드롭**(7.3).
- 오프라인(로그아웃 취소)일 때: 저널을 `RETURN_PENDING`으로 남기고, 다음 접속 때 자동 반환(인벤토리 부족분은 `/trade returns` 로 나눠 받기). 발밑 드롭은 온라인일 때만(오프라인 상태에서 월드에 떨구면 남이 주울 수 있음, 10장 6).
- 서버 종료(`onDisable`): 열린 세션 모두 취소 + 즉시 반환 시도 → 저장. 강제 종료 후 기동 때: `OPEN/COUNTDOWN/RETURN_PENDING` 저널 → 반환 대기, `SETTLING` → 4장 3번과 같이 `findByExtRef` 로 돈 이동 여부 확인: 이동 없음 → 반환, 이동 있음 → 지급 대기, 모름 → 보류(관리자), `DELIVERING` → 디스크 토큰 확인.

## 7. 이벤트·스레드

- 모든 처리는 메인 스레드. 저널 쓰기는 작은 파일이라 메인 스레드 동기(fsync) — 정산 때만 필수, 평소엔 틱당 1회(3장).
- `PlayerQuitEvent`, `PlayerDeathEvent`, `InventoryCloseEvent`(MONITOR, 결과만 읽고 다음 틱에 취소 처리), 전투 태그(다음 틱에 Travel PDC 재확인 또는 `EntityDamageByEntityEvent` MONITOR).
- 거래 창 클릭: `common.gui` `MenuListener`의 "전부 취소"를 쓰지 않는 **예외 창**(7.4.6, AGENTS.md 7장). 허용 동작은 화이트리스트: 본인 칸에 놓기·빼기, 아래 인벤토리 → 본인 칸 빈자리로 Shift 이동, 본인 칸 → 아래 인벤토리 Shift 이동. 그 밖(숫자키·`F`·더블클릭 모으기·드래그·상대 칸·중앙 열 클릭의 아이템 이동)은 전부 취소. 세부는 ② GUI 설계에서.
- 요청 타임아웃 30초(7.3), 카운트다운 3초(7.3), 연타 방지 `ClickLock`.

## 8. 기록

- 돈: Bank 원장(`source=Trade`, `extRef=trade:<id>`).
- 거래 로그: `plugins/Trade/trades.log` 한 줄 JSON(시각, tradeId, 양쪽 UUID·닉, 주고받은 아이템 요약·금액, 결과). 7.3 "모든 거래는 로그 파일에 기록". 파일은 하루 단위(`trades-YYYY-MM-DD.log`, 10장 7).

## 9. 테스트 (순수 로직, JUnit)

- `TradeSession` 상태 전이(변경 시 준비 해제, 카운트다운 취소, 세션 하나 규칙)
- `SlotRules` 허용/거부 클릭 표(ClickType·칸 번호 → 결과; Bukkit 타입을 쓰지 않는 값으로 변환해 테스트)
- `InventoryFit` 받을 아이템이 들어가는지(스택 합치기)
- `Journal` 직렬화·원자적 쓰기·손상 파일 처리, 기동 복구 결정표(상태 × Bank 결과 × 디스크 토큰)
- Bank `settleTrade` 멱등·잔액 부족·한 트랜잭션(임시 폴더 실제 SQLite)

## 10. 결정 사항 (2026-11-27 사용자 확정)

1. 금액 입력: **Paper Dialog API**(26.3-258 `io.papermc.paper.dialog`, `TextDialogInput`/`NumberRangeDialogInput`). 채팅 입력 안 씀. 대화창이 열려 있는 동안은 거래 창이 닫힌 것으로 보지 않음(창 닫기 취소 규칙의 예외, ② GUI 설계에서 흐름 확정).
2. 거래 가능 아이템: **바닐라 아이템 전부**(셜커 상자·꾸러미 포함). 저널이 내용 전체를 저장.
3. 수수료·한도: **없음**(한도는 잔액).
4. 같은 상대에게 거절·만료 뒤 **10초 재요청 금지**.
5. Bank가 없거나 잠기면 **거래 기능 전체 잠금**(돈 0원 거래도 같은 경로).
6. 오프라인 취소 반환품은 **다음 접속 때 자동 반환**, 발밑 드롭은 온라인일 때만.
7. 거래 로그는 **하루 단위 파일** `plugins/Trade/trades-YYYY-MM-DD.log`.
