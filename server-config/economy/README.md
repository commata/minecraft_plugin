# 경제 플러그인 설정 (server-config/economy)

docs/ECONOMY.md v0.8의 5장(직업)·6장(상점) 값을 실제 설정 파일로 옮긴 것. 가격·보상을 바꿀 때는 ECONOMY.md와 이 폴더를 같은 PR에서 고친다.

| 폴더 | 서버 위치 | 내용 |
| --- | --- | --- |
| `VJobs/` | `plugins/VJobs/` | `config.yml` (5개 직업, 보상표, XP 곡선, 정수 보상), `messages_ko.yml` (한국어) |
| `EconomyShopGUI/` | `plugins/EconomyShopGUI/` | `config.yml` (한국어, 판매 끔, 스포너 끔), `sections/`·`shops/` (6.3 가격표 8개 상점) |
| `VERSIONS.md` | - | 외부 JAR 고정 버전과 SHA-256 |

데이터 파일(`local.db`, `luckperms-h2-*.db`, `transactions/` 등)은 넣지 않는다. LuckPerms·Vault는 기본 설정 그대로 쓰므로 파일이 없다. LuckPerms 권한은 9.2의 4번 명령으로 넣는다.

## 적용 (로컬 테스트 서버, 서버를 끈 상태에서)

EconomyShopGUI는 **기본 `shops/`·`sections/` 폴더를 먼저 치워야** 한다. 남아 있으면 기본 상점 28개(판매 가능)가 그대로 같이 뜬다.

```powershell
$run = "C:\minecraft\minecraft_plugin\plugins\bank\run\plugins"
$cfg = "C:\minecraft\minecraft_plugin\server-config\economy"
Rename-Item "$run\EconomyShopGUI\shops"    "shops.default"
Rename-Item "$run\EconomyShopGUI\sections" "sections.default"
Copy-Item "$cfg\VJobs\*"          "$run\VJobs\"          -Recurse -Force
Copy-Item "$cfg\EconomyShopGUI\*" "$run\EconomyShopGUI\" -Recurse -Force
```

### 상점 GUI 디자인만 다시 적용할 때 (ECONOMY.md 6.6)

서버를 끄고 `EconomyShopGUI` 폴더만 다시 복사한다. `LanguageFiles/lang-ko.yml`(창 제목)도 함께 덮어쓴다. 메인 메뉴 줄 수는 재시작해야 바뀐다.

```powershell
Copy-Item "$cfg\EconomyShopGUI\*" "$run\EconomyShopGUI\" -Recurse -Force
```

확인: `/shop` 6줄 메인 메뉴, 카테고리 안쪽 45칸 + 하단 바, 품목 좌클릭 → 5줄 구매 수량 화면, 설명에 판매 문구 없음, 건축·장식·인테리어에서 다음 페이지 동작.

## EconomyHub (1b에 함께 설치)

VJobs만으로는 막을 수 없는 두 가지를 `plugins/economy-hub`(플러그인 이름 `EconomyHub`)가 맡는다 (ECONOMY.md 7.2).

- 직업 쿨타임은 **탈퇴 후 1시간만**: VJobs는 가입 때도 쿨타임을 걸어서, 탈퇴 기록이 없거나 1시간이 지난 플레이어에게 `jobs.cooldown.instant`를 붙여 줌. 쿨타임 길이는 `VJobs/config.yml`의 `job-switch-cooldown.default-seconds` 하나만 보고, EconomyHub 설정에는 따로 없음 (예전 `job-cooldown.after-leave-minutes`가 남아 있으면 기동 때 경고. `plugins/EconomyHub/config.yml`을 지우고 재기동하면 새 기본값으로 다시 만들어짐)
- 피스톤으로 움직인 블록과 용암·물로 생긴 돌·조약돌·현무암은 캐도 직업 보상 0원
- 탈퇴 시각은 `plugins/EconomyHub/job-leaves.tsv`에도 저장 (강제 종료 후에도 쿨타임 유지)
- 직업을 탈퇴했다가 다시 가입해도 그 레벨에서 쌓던 XP 유지 (VJobs는 레벨만 보관하고 XP를 0으로 만듦). 기록은 `plugins/EconomyHub/job-xp.tsv`, VJobs DB에 저장된 게 확인되면 지워짐. 서버가 켜져 있을 때 고치지 말 것. 백업·복원 때 VJobs DB와 같이 다룸

로컬 테스트 서버에 넣기:

```powershell
.\gradlew :plugins:economy-hub:build
Copy-Item plugins\economy-hub\build\libs\economy-hub-*.jar plugins\bank\run\plugins\ -Exclude *-plain.jar
```

확인할 로그: `EconomyHub 0.1.0 활성화 (탈퇴 후 쿨타임 60분, 보상 제외 켜짐, 탈퇴 후 XP 유지 켜짐 (기록 0건))`. 게임 안에서 `/economyhub status <닉>`.

## 적용 (운영, ECONOMY.md 9.2)

운영은 첫 설치라 `plugins/EconomyShopGUI/`가 없다. 설정을 먼저 복사해 두면 `shops/`가 이미 있어서 기본 상점을 만들지 않는다(첫 기동 로그에 `Creating default shops`가 **없어야** 함).

## 확인할 로그

- `[VJobs] Config loaded: 5 job(s)` (18이면 config.yml이 안 바뀐 것)
- `[EconomyShopGUI] Completed loading 8 section configs` / `8 shop configs`
- `[Bank]` 소수 금액 거부 경고가 없어야 함 (2.2)
- `EconomyHub` 경고 없음 (`연결 실패`가 나오면 VJobs 버전이 바뀐 것)
