# CLAUDE.md

이 저장소의 개발 규칙은 모든 AI 에이전트가 같이 쓰도록 [AGENTS.md](AGENTS.md)에 있습니다. 먼저 그 문서를 따르세요.

@AGENTS.md

## Claude Code 전용 메모

- 작업을 시작하면 `docs/ECONOMY.md`에서 관련 절을 먼저 읽고, 기획에 없는 숫자·레이아웃은 정하지 말고 사용자에게 확인합니다.
- 클라우드 세션에서는 `repo.papermc.io`·`api.modrinth.com`이 막혀 Gradle 전체 빌드가 안 될 수 있습니다. Bukkit 비의존 클래스만 `javac` + JUnit standalone으로 확인하고, 전체 빌드는 GitHub Actions 결과로 판단합니다 (AGENTS.md 9장).
- 운영 서버(SSH, `docker`, `deploy.ps1`)는 실행하지 않습니다. 배포 명령은 사용자에게 안내만 합니다.
