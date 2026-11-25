# 외부 플러그인 고정 버전 (ECONOMY.md 9.3)

테스트 서버에서 검증한 **이 JAR 파일만** 운영에 올린다. 운영에 올리기 전에 `sha256sum`으로 아래 값과 같은지 확인한다.

| 플러그인 | JAR 파일명 | 버전 | plugin.yml `name` | SHA-256 |
| --- | --- | --- | --- | --- |
| VaultUnlocked | `VaultUnlocked-2.20.3.jar` | 2.20.3 | `Vault` | `fbc6651aca11e13376c115df66eeffa79592e79517be398299aa6beb49c22695` |
| VJobs | `VJobs-2.2.0-RELEASE.jar` | 2.2.0-RELEASE | `VJobs` | `97ef84a243d0a53153a6ba5349dd86d4b83ea03e35350ca31a85888ad31bfda4` |
| EconomyShopGUI (무료) | `EconomyShopGUI-7.3.4.jar` | 7.3.4 | `EconomyShopGUI` | `26c26a2523f4d504b29ee2ada9f37639983938e17ca54bc61837d751d2ed1c3e` |
| LuckPerms (Bukkit) | `LuckPerms-Bukkit-5.5.91.jar` | 5.5.91 | `LuckPerms` | `2a6834c51830b133ec8763703391b20240f7a3b4748746503251a45d70cf1b39` |

- 확인일: 2026-11-25 (로컬 테스트 서버 `plugins/bank/run`, Paper 26.3-262. 운영은 26.3-258)
- 첫 기동 로그: 4개 모두 오류 없이 활성화, EconomyShopGUI `Successfully hooked into Vault and using Bank as economy provider`, LuckPerms `Registered Vault permission & chat hook`

```bash
# 미니PC에서 확인
sha256sum ~/plugin-staging/economy/*.jar
```
