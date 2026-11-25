package kr.commata.bank;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.commata.bank.command.BaltopCommand;
import kr.commata.bank.command.BankAdminCommand;
import kr.commata.bank.command.MoneyCommand;
import kr.commata.bank.command.PayCommand;
import kr.commata.bank.core.BankStore;
import kr.commata.bank.core.BootReport;
import kr.commata.bank.core.Bootstrap;
import kr.commata.bank.core.PlayerFiles;
import kr.commata.bank.listener.JoinListener;
import kr.commata.bank.vault.BankEconomy;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 서버 화폐(원). VaultUnlocked 에 경제 제공자로 등록되어 VJobs·EconomyShopGUI 가 이 잔액을 씁니다.
 * 잔액·거래 기록은 plugins/Bank/bank.db (ECONOMY.md 2.1~2.4).
 */
public final class BankPlugin extends JavaPlugin {

    /** heartbeat 주기 30초. 비정상 종료 때 장애 추정 시각의 정밀도 */
    private static final long HEARTBEAT_TICKS = 20L * 30;

    private BankStore store;
    private BankService bank;
    private BaltopCommand baltop;

    @Override
    public void onEnable() {
        Path dir = getDataFolder().toPath();
        // 무엇이든 만들기 전에 확인: 폴더가 없을 때만 첫 설치 (ECONOMY.md 2.3)
        boolean folderExisted = Files.exists(dir);

        String lockReason = null;
        BootReport boot = null;
        boolean firstInstall = false;
        Bootstrap.Result result;
        try {
            result = Bootstrap.open(dir, folderExisted, System::currentTimeMillis);
        } catch (LinkageError e) {
            result = new Bootstrap.Locked("SQLite 드라이버(org.sqlite)를 찾지 못함: " + e);
        }
        if (result instanceof Bootstrap.Ready ready) {
            store = ready.store();
            firstInstall = ready.firstInstall();
            try {
                boot = store.startBoot(UUID.randomUUID().toString(), new PlayerFiles());
            } catch (SQLException e) {
                store.close();
                store = null;
                lockReason = "기동 기록 실패: " + e.getMessage();
            }
        } else {
            lockReason = ((Bootstrap.Locked) result).reason();
        }

        if (store != null) {
            if (firstInstall) {
                saveDefaultConfig();
                getConfig().set("installed", true);
                getConfig().set("installed-at", Instant.now().toString());
                saveConfig();
                getLogger().info("첫 설치: 빈 " + BankStore.DB_FILE + " 를 만들었습니다.");
            } else {
                saveDefaultConfig();
            }
        }

        bank = new BankService(getServer(), getLogger(), store, lockReason, boot);
        reloadSettings();

        // 잠금 모드에서도 제공자는 등록 (모든 입출금이 실패 → 상점 구매·직업 보상 정지)
        getServer().getServicesManager().register(Economy.class, new BankEconomy(bank), this, ServicePriority.Highest);

        if (store != null) {
            BankStore s = store;
            getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
                try {
                    s.heartbeat();
                } catch (SQLException e) {
                    if (!s.isClosed()) {
                        getLogger().warning("heartbeat 기록 실패: " + e.getMessage());
                    }
                }
            }, HEARTBEAT_TICKS, HEARTBEAT_TICKS);
        }

        getServer().getPluginManager().registerEvents(new JoinListener(bank), this);
        baltop = new BaltopCommand(bank);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            var r = event.registrar();
            r.register("money", "내 잔액 보기 (/money 닉네임: 다른 사람 잔액)", List.of("bal", "돈"),
                    new MoneyCommand(bank));
            r.register("pay", "돈 보내기: /pay 닉네임 금액", new PayCommand(bank));
            r.register("baltop", "부자 순위 (/baltop 쪽)", baltop);
            r.register("bank", "은행 관리자 명령", new BankAdminCommand(bank, this::reloadAll));
        });

        if (store == null) {
            getLogger().severe("==========================================================");
            getLogger().severe("Bank 잠금 모드: " + lockReason);
            getLogger().severe("모든 입출금이 실패합니다 (상점 구매·직업 보상 정지). 새 DB 는 만들지 않았습니다.");
            getLogger().severe("같은 시점의 전체 백업(월드 + plugins)으로 복원한 뒤 재시작하세요 (ECONOMY.md 2.3).");
            getLogger().severe("정말 첫 설치라면 plugins/Bank 폴더를 통째로 지운 뒤 재시작하면 새로 만듭니다.");
            getLogger().severe("==========================================================");
            return;
        }
        if (boot != null && boot.previousUnclean()) {
            String when = BootReport.PLAYER_SAVE_MISSING.equals(boot.reason())
                    ? "장애 추정 시각(Bank 종료 시각) " + Instant.ofEpochMilli(boot.crashEstimateMs())
                    + ", 저장 안 된 플레이어 " + boot.unsavedPlayers()
                    : "장애 추정 시각(마지막 heartbeat) " + Instant.ofEpochMilli(boot.crashEstimateMs())
                    + ", 실제 종료는 그 뒤 30초 안";
            getLogger().warning("직전 기동 " + boot.previousBootId() + " 비정상 종료 (" + boot.describe() + "). "
                    + when + ". 구매 유실 신고는 ECONOMY.md 2.4 절차로 처리하세요.");
        }
        getLogger().info("Bank " + getPluginMeta().getVersion() + " 활성화 (계좌 "
                + store.accountCount() + "개, 기동 ID " + store.bootId() + ")");
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (store != null) {
            try {
                // 이 뒤에 서버가 플레이어를 저장하므로, 접속자와 월드 폴더를 남겨 다음 기동에서 저장 완료를 확인
                List<UUID> online = getServer().getOnlinePlayers().stream().map(p -> p.getUniqueId()).toList();
                String worldDir = getServer().getWorlds().isEmpty() ? null
                        : getServer().getWorlds().get(0).getWorldFolder().getAbsolutePath();
                store.closeClean(online, worldDir);
                getLogger().info("정상 종료 기록 완료 (접속자 " + online.size() + "명의 저장은 다음 기동에서 확인)");
            } catch (SQLException e) {
                getLogger().severe("정상 종료 기록 실패 (다음 기동에서 비정상 종료로 보입니다): " + e.getMessage());
            }
        }
    }

    private void reloadSettings() {
        File file = new File(getDataFolder(), "config.yml");
        if (file.isFile()) {
            reloadConfig();
            bank.settings(BankSettings.from(getConfig()));
        } else {
            // 잠금 모드에서는 아무 파일도 만들지 않음
            bank.settings(BankSettings.from(new YamlConfiguration()));
        }
    }

    private void reloadAll() {
        reloadSettings();
        if (baltop != null) {
            baltop.invalidate();
        }
    }
}
