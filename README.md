# minecraft_plugin

Ubuntu 미니PC의 Docker Paper 서버(MC 26.3)에서 사용할 자체 개발 Paper 플러그인 모음입니다.
설계와 배포 방식은 [docs/PLAN.md](docs/PLAN.md)를 참고하세요.

## 요구 사항

- JDK 25 (Temurin 25 권장). 없으면 Gradle이 자동으로 내려받습니다.
- IntelliJ IDEA: Settings → Build Tools → Gradle → Gradle JVM = 25

## 구조

| 경로 | 내용 |
| --- | --- |
| `build-logic/` | 공통 빌드 규칙 (`java-conventions`, `paper-plugin-conventions`) |
| `common/` | 공통 코드 (메시지, 쿨다운). 각 플러그인 JAR에 포함됩니다 |
| `plugins/custom-items/` | 커스텀 아이템·스킬 플러그인 (현재: 접속 환영 메시지, `/ci`) |
| `plugins/home/` | `/sethome`, `/home` 귀환 플러그인 |
| `gradle.properties` | MC / Paper API / Java 버전 (여기서만 관리) |

## 자주 쓰는 명령 (Windows PowerShell)

```powershell
# 전체 빌드 + 테스트
.\gradlew build

# 로컬 테스트 서버 실행 (Paper 26.3 자동 다운로드, EULA 자동 동의)
.\gradlew :plugins:custom-items:runServer
.\gradlew :plugins:home:runServer
```

- 테스트 서버 파일은 `plugins/custom-items/run/`에 생기며 Git에는 올라가지 않습니다.
- Minecraft에서 `localhost`로 접속합니다. 서버 콘솔에서 `op <내 닉네임>`을 입력하면 OP가 됩니다.
- IntelliJ Gradle 창에서 `runServer`를 **Debug**로 실행하면 브레이크포인트가 동작합니다.
- 배포용 JAR: `plugins/custom-items/build/libs/custom-items-<버전>.jar` (`-plain.jar`은 사용하지 않음)

## 명령어

| 명령 | 권한 | 설명 |
| --- | --- | --- |
| `/ci version` | 모두 | 플러그인·서버 버전 표시 |
| `/ci reload` | `customitems.reload` (기본 OP) | `config.yml` 다시 불러오기 |
| `/sethome` | 모두 | 지금 위치를 집으로 저장 (1인 1개, 오버월드만, 다시 쓰면 덮어씀) |
| `/home` | 모두 | 7초 시전 후 집으로 이동, 성공 후 5분 쿨타임 |
| `/home reload` | `home.reload` (기본 OP) | `plugins/Home/config.yml` 다시 불러오기 |

### `/home` 규칙 (시간은 `plugins/home/src/main/resources/config.yml`에서 변경)

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

- 오버월드에서만 `/sethome`, `/home` 가능. 네더·엔드에서는 거부합니다.
- 이동 직전 검사: 3블록 넘게 떨어지는 중, 겉날개 비행, 용암·불, 물속(수중 호흡 없음)이면 이동하지 않고 취소(쿨타임 없음).
- 집 위치가 막혔거나(블록이 생김) 바닥이 사라졌거나 용암 등 위험하면 이동하지 않습니다.
- 남은 시간은 화면 아래 액션바에 `귀환까지 7초 → 6초 → …`로 표시되고, 메시지는 본인에게만 보입니다.
- 집, 쿨타임, 전투 제한은 플레이어 데이터(PDC)에 저장되어 재접속·서버 재시작 후에도 유지됩니다.
