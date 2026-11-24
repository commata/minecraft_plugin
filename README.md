# minecraft_plugin

Ubuntu 미니PC의 Docker Paper 서버(MC 26.3)에서 사용할 자체 개발 Paper 플러그인 모음입니다.
설계와 배포 방식은 [docs/PLAN.md](docs/PLAN.md), 경제·직업 기획은 [docs/ECONOMY.md](docs/ECONOMY.md)를 참고하세요.

## 요구 사항

- JDK 25 (Temurin 25 권장). 없으면 Gradle이 자동으로 내려받습니다.
- IntelliJ IDEA: Settings → Build Tools → Gradle → Gradle JVM = 25

## 구조

| 경로 | 내용 |
| --- | --- |
| `build-logic/` | 공통 빌드 규칙 (`java-conventions`, `paper-plugin-conventions`) |
| `common/` | 공통 코드 (메시지, 쿨다운). 각 플러그인 JAR에 포함됩니다 |
| `plugins/custom-items/` | 커스텀 아이템·스킬 플러그인 (현재: 접속 환영 메시지, `/ci`) |
| `plugins/travel/` | `/sethome`, `/home` 귀환 + `/tpa` 이동 요청 플러그인 (예전 `plugins/home`) |
| `plugins/pets/` | `/callpet` 길들인 펫 불러오기 플러그인 |
| `plugins/bank/` | 서버 화폐(원). VaultUnlocked 경제 제공자 + `/money` `/pay` `/baltop` `/bank` ([ECONOMY.md 2.1](docs/ECONOMY.md)) |
| `gradle.properties` | MC / Paper API / Java 버전 (여기서만 관리) |

## 자주 쓰는 명령 (Windows PowerShell)

```powershell
# 전체 빌드 + 테스트
.\gradlew build

# 로컬 테스트 서버 실행 (Paper 26.3 자동 다운로드, EULA 자동 동의)
.\gradlew :plugins:custom-items:runServer
.\gradlew :plugins:travel:runServer
.\gradlew :plugins:pets:runServer
.\gradlew :plugins:bank:runServer   # VaultUnlocked JAR 을 plugins/bank/run/plugins/ 에 먼저 넣어야 켜짐
```

- 테스트 서버 파일은 `plugins/custom-items/run/`에 생기며 Git에는 올라가지 않습니다.
- Minecraft에서 `localhost`로 접속합니다. 테스트 서버도 화이트리스트가 기본으로 켜져 있으므로, 처음 한 번 서버 콘솔에서
  `whitelist add <내 닉네임>`, `op <내 닉네임>`을 입력합니다. (`You are not whitelisted` 오류의 원인)
- IntelliJ Gradle 창에서 `runServer`를 **Debug**로 실행하면 브레이크포인트가 동작합니다.
- 배포용 JAR: `plugins/custom-items/build/libs/custom-items-<버전>.jar` (`-plain.jar`은 사용하지 않음)

## 명령어

| 명령 | 권한 | 설명 |
| --- | --- | --- |
| `/ci version` | 모두 | 플러그인·서버 버전 표시 |
| `/ci reload` | `customitems.reload` (기본 OP) | `config.yml` 다시 불러오기 |
| `/sethome` | 모두 | 지금 위치를 집으로 저장 (1인 1개, 오버월드만, 다시 쓰면 덮어씀) |
| `/home` | 모두 | 7초 시전 후 집으로 이동, 성공 후 5분 쿨타임 (어느 차원에서든 사용 가능) |
| `/home reload` | `home.reload` (기본 OP) | `plugins/Travel/config.yml` 다시 불러오기 (/home, /tpa 모두) |
| `/tpa` | 모두 | 접속자 목록 GUI 열기 (머리 클릭 = 이동 요청) |
| `/tpa <닉네임>` | 모두 | 그 사람에게 이동 요청 |
| `/tpaccept [닉네임]` | 모두 | 받은 요청 수락 (닉네임 없으면 가장 최근 요청) |
| `/tpdeny [닉네임]` | 모두 | 받은 요청 거절 |
| `/tpcancel` | 모두 | 보낸 요청 취소 |
| `/tpatoggle` | 모두 | 요청 받기 켜기·끄기 |
| `/money` (`/bal`, `/돈`) `[닉네임]` | 모두 | 내 잔액 / 다른 사람 잔액 |
| `/pay <닉네임> <금액>` | 모두 | 송금 (정수만, 최소 10원, 접속한 적 있는 플레이어면 오프라인도 가능) |
| `/baltop [쪽]` | 모두 | 잔액 순위 10명씩 (1분마다 갱신) |
| `/bank give·take·set <닉네임> <금액>` | `bank.admin` (기본 OP) | 관리자 잔액 변경 (기록에 실행자 남음) |
| `/bank log <닉네임> [쪽]` | `bank.admin` | 거래 기록 (마우스를 올리면 기동 ID·위치·창 제목) |
| `/bank refund <거래번호>` | `bank.admin` | 비정상 종료된 기동의 EconomyShopGUI 출금만 1회 환불 ([ECONOMY.md 2.4](docs/ECONOMY.md)) |
| `/bank reload` | `bank.admin` | `plugins/Bank/config.yml` 다시 불러오기 |
| `/callpet` | 모두 | 내가 길들인 늑대·고양이·앵무새·말·당나귀·노새·라마를 내 옆으로 순간이동 (쿨타임 없음, 다른 차원에서도 가능, 앉은 펫은 앉은 채로). 멀리 두고 온 펫도 마지막 위치의 청크를 불러와서 데려옴 |

### `/home` 규칙 (시간은 `plugins/travel/src/main/resources/config.yml`에서 변경)

| 플레이어 행동 | 귀환 | 이후 제한 |
| --- | --- | --- |
| 걷기·달리기·점프·시점 회전·아이템 줍기·빈 손 좌클릭 | 계속 진행 | 없음 |
| 블록 설치, 블록 캐기 시작, 도구·무기 휘두르기, 도구로 블록 손질 | 즉시 취소 | 없음 (바로 다시 `/home` 가능) |
| 음식 먹기 시작, 물약, 활·쇠뇌 당기기, 엔더 진주 등 아이템 사용 | 즉시 취소 | 없음 |
| 몬스터를 때리거나 몬스터에게 맞음 | 즉시 취소 | 15초 귀환 불가 |
| 다른 플레이어를 때리거나 맞음 (PvP 켜진 경우) | 즉시 취소 | 양쪽 30초 귀환 불가 |
| 낙하·불 등 환경 피해, 다른 곳으로 순간이동 | 즉시 취소 | 없음 |
| 로그아웃·사망 | 조용히 취소 | 없음 |
| 귀환 성공 | 집으로 이동 | 5분 쿨타임 (OP는 `home.bypass.cooldown`으로 무시) |

- `/sethome`은 오버월드에서만 가능합니다. `/home`은 네더·엔드에서도 쓸 수 있고, 오버월드 집으로 돌아옵니다.
- 이동 직전 검사: 3블록 넘게 떨어지는 중, 겉날개 비행, 용암·불, 물속(수중 호흡 없음)이면 이동하지 않고 취소(쿨타임 없음).
- 집 위치가 막혔거나(블록이 생김) 바닥이 사라졌거나 용암 등 위험하면 이동하지 않습니다.
- 남은 시간은 화면 아래 액션바에 `귀환까지 7초 → 6초 → …`로 표시되고, 메시지는 본인에게만 보입니다.
- 집, 쿨타임, 전투 제한은 플레이어 데이터(PDC)에 저장되어 재접속·서버 재시작 후에도 유지됩니다.

### `/tpa` 규칙

- 요청자가 수락자에게 갑니다 (`/tpahere`는 함정에 악용되기 쉬워 없음). 수락하면 **바로** 수락자의 지금 위치로 이동합니다
  (`tpa.cast-seconds`로 대기시간을 넣을 수 있고, 넣으면 `/home`과 같은 취소 규칙이 적용됨).
- 요청이 오면 효과음, 화면 위 보스바(남은 시간), 채팅의 클릭 버튼 `[수락]` `[거절]`이 함께 뜹니다. 60초 뒤 자동 만료.
- 보낼 수 있는 요청은 한 번에 하나 (새로 보내면 이전 요청 취소). 같은 사람에게는 10초 안에 다시 보낼 수 없습니다.
- 쿨타임은 이동에 성공한 요청자에게만 5분. `/home`과 **따로** 셉니다. 거절·만료·취소로 끝나면 쿨타임 없음. OP는 `tpa.bypass.cooldown`으로 무시.
- 전투 제한(몬스터 15초 / PvP 30초)은 `/home`과 공유합니다. 맞은 직후 `/tpa`로 도망칠 수 없습니다.
- 요청을 보낼 때, 수락할 때, 이동 직전에 검사합니다.
  - 보내는 사람이 전투 중·쿨타임·낙하·용암 등이면 이유를 알려 주고 보내지 않음.
  - 받는 사람이 전투 중이거나 떨어지는 중, 겉날개·비행·탈것, 용암·불·물속, 관전 모드이면
    "`<닉네임>`님은 현재 요청을 받을 수 없는 상황입니다."라고만 알려 줌 (상대 상태는 드러내지 않음).
  - 수락자가 그런 상태면 수락이 막히고, 요청은 만료 전까지 남아 있어 나중에 다시 수락할 수 있음.
- GUI에는 차원만 표시하고 좌표는 보여 주지 않습니다. 투명화 중인 사람은 차원도 "비공개". 요청을 꺼 둔 사람은 회색.
- 요청자나 수락자가 접속을 종료하면 요청이 자동으로 취소됩니다.

### Home → Travel 이름 변경

- `/home`과 `/tpa`가 전투 상태를 공유하도록 하나의 플러그인 **Travel**로 합쳤습니다.
- 집 위치·`/home` 쿨타임·전투 제한은 예전과 같은 PDC 키(`home:*`)를 그대로 써서 **옮길 필요 없이 이어집니다**.
- 처음 켜질 때 `plugins/Home/config.yml`이 있으면 값을 `plugins/Travel/config.yml`로 옮깁니다. 예전 `plugins/Home/` 폴더는 지워도 됩니다.
- 배포 스크립트는 Travel을 설치할 때 서버의 예전 Home JAR도 보관 폴더로 옮깁니다 (`mc-env.sh`의 `RENAMED_PLUGINS`).
  혹시 둘 다 들어 있으면 Travel이 Home을 끄고 경고를 남깁니다.

## 운영 서버 배포

백업·배포·되돌리기 방법은 [docs/DEPLOY.md](docs/DEPLOY.md)를 참고하세요.

```powershell
.\scripts\deploy.ps1 -Plugin custom-items -DryRun   # 확인만
.\scripts\deploy.ps1 -Plugin custom-items           # 실제 배포
.\scripts\deploy.ps1 -Plugin travel -DryRun         # /home + /tpa (예전 Home JAR 교체 대상으로 표시되는지 확인)
```
