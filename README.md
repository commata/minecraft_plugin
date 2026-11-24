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
| `gradle.properties` | MC / Paper API / Java 버전 (여기서만 관리) |

## 자주 쓰는 명령 (Windows PowerShell)

```powershell
# 전체 빌드 + 테스트
.\gradlew build

# 로컬 테스트 서버 실행 (Paper 26.3 자동 다운로드, EULA 자동 동의)
.\gradlew :plugins:custom-items:runServer
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

## 운영 서버 배포

백업·배포·되돌리기 방법은 [docs/DEPLOY.md](docs/DEPLOY.md)를 참고하세요.

```powershell
.\scripts\deploy.ps1 -Plugin custom-items -DryRun   # 확인만
.\scripts\deploy.ps1 -Plugin custom-items           # 실제 배포
```
