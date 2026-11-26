package kr.commata.market;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import kr.commata.common.gui.MenuListener;
import kr.commata.market.bridge.BankBridge;
import kr.commata.market.bridge.VJobsBridge;
import kr.commata.market.command.MarketCommand;
import kr.commata.market.core.MarketBootstrap;
import kr.commata.market.core.MarketStore;
import kr.commata.market.core.PlayerDatReader;
import kr.commata.market.gui.MarketMenus;
import kr.commata.market.service.MarketService;
import kr.commata.market.service.PlayerDataGuard;
import kr.commata.market.service.QuotaTracker;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 희귀 상점(주간 한도), 인첸트 책 뽑기(확률 공개·천장), 직업별 서버 매입 플러그인 (ECONOMY.md 13·14장).
 */
public final class MarketPlugin extends JavaPlugin {

    /** 미완료 거래 재확인·재지급·재입금 주기: 1분 (ECONOMY.md 13.6, 14.3). */
    private static final long RECOVERY_PERIOD_TICKS = 20L * 60;
    /** 판매 가능량 메모리 버퍼 일괄 반영 주기: 20틱(1초, ECONOMY.md 14.2). */
    private static final long QUOTA_FLUSH_PERIOD_TICKS = 20L;

    private MarketStore store;
    private MarketService market;
    private QuotaTracker quotaTracker;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        MarketConfig config = MarketConfig.load(getConfig(), getLogger());

        Path dataDir = getDataFolder().toPath();
        String lockReason = null;
        MarketBootstrap.Result bootResult;
        try {
            bootResult = MarketBootstrap.open(dataDir, System::currentTimeMillis);
        } catch (LinkageError e) {
            bootResult = new MarketBootstrap.Locked("SQLite 드라이버(org.sqlite)를 찾지 못함: " + e);
        }
        if (bootResult instanceof MarketBootstrap.Ready ready) {
            store = ready.store();
            if (ready.firstInstall()) {
                getLogger().info("첫 설치: 빈 " + MarketStore.DB_FILE + " 를 만들었습니다.");
            }
        } else {
            lockReason = ((MarketBootstrap.Locked) bootResult).reason();
        }

        Path worldRoot = resolveWorldRoot();
        BankBridge bank = new BankBridge(getServer(), getLogger());
        Plugin vjobsPlugin = getServer().getPluginManager().getPlugin("VJobs");
        VJobsBridge vjobs = new VJobsBridge(this, vjobsPlugin);

        market = new MarketService(this, store, lockReason, bank, vjobs, worldRoot, config);
        MarketMenus menus = new MarketMenus(this, market);
        PlayerDataGuard dataGuard = new PlayerDataGuard(this, store, market, worldRoot);
        quotaTracker = new QuotaTracker(market, getLogger());
        market.setQuotaFlusher(quotaTracker::flushPlayer);

        getServer().getPluginManager().registerEvents(new MenuListener(), this);
        getServer().getPluginManager().registerEvents(dataGuard, this);
        getServer().getPluginManager().registerEvents(quotaTracker, this);

        if (vjobs.available()) {
            String err = vjobs.listenLeave((player, jobId) -> market.onJobLeave(player.getUniqueId(), jobId));
            if (err != null) {
                getLogger().warning("VJobs 탈퇴 이벤트 연결 실패 (접속·/판매 시 탈퇴 이관 보정으로 동작): " + err);
            }
        } else {
            getLogger().warning("VJobs 가 없거나 꺼져 있어 직업 보너스는 적용되지 않습니다.");
        }

        if (store != null) {
            // 이전 기동의 미완료 주문·판매 복구 및 1분 주기 복구 타이머, 20틱 판매 가능량 일괄 반영 타이머 시작
            market.reconcileOnStartup();
            getServer().getScheduler().runTaskTimer(this, market::tickRecovery,
                    RECOVERY_PERIOD_TICKS, RECOVERY_PERIOD_TICKS);
            getServer().getScheduler().runTaskTimer(this, quotaTracker::flushAll,
                    QUOTA_FLUSH_PERIOD_TICKS, QUOTA_FLUSH_PERIOD_TICKS);
        }

        registerCommands(menus, dataGuard);

        if (store == null) {
            getLogger().severe("==========================================================");
            getLogger().severe("Market 잠금 모드: " + lockReason);
            getLogger().severe("모든 구매·뽑기·판매가 차단됩니다. 새 DB 는 만들지 않았습니다.");
            getLogger().severe("같은 시점의 전체 백업(월드 + plugins)으로 복원한 뒤 재시작하세요.");
            getLogger().severe("==========================================================");
            return;
        }

        getLogger().info("Market " + getPluginMeta().getVersion() + " 활성화 (Bank " + bank.statusText()
                + ", 희귀상점 " + (config.rare().available() ? "켜짐" : "잠김")
                + ", 뽑기 " + (config.gacha().available() ? "켜짐" : "잠김")
                + ", 매입 " + (config.sell().available() ? "켜짐" : "잠김") + ")");
    }

    private void registerCommands(MarketMenus menus, PlayerDataGuard guard) {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            var r = event.registrar();
            r.register("market", "희귀 상점 열기 및 마켓 관리 (/market [claim|status|reload|log|reset|pending|resolve])",
                    new MarketCommand(getServer(), market, menus, guard, this::reloadSettings));
            r.register("희귀상점", "희귀 상점 열기 (주간 한도)", List.of("rareshop"),
                    playerOnly(menus::openRareShop));
            r.register("뽑기", "인첸트 책 뽑기 (/뽑기 확률: 확률표)", List.of("gacha"),
                    new GachaCommand(menus));
            r.register("판매", "직접 채집·획득한 아이템을 서버에 판매", List.of("sell"),
                    playerOnly(menus::openSell));
        });
    }

    private void reloadSettings() {
        reloadConfig();
        market.updateConfig(MarketConfig.load(getConfig(), getLogger()));
    }

    private Path resolveWorldRoot() {
        if (!getServer().getWorlds().isEmpty()) {
            return PlayerDatReader.worldRoot(getServer().getWorlds().get(0).getWorldFolder().toPath());
        }
        return getServer().getWorldContainer().toPath().resolve("world").toAbsolutePath().normalize();
    }

    @Override
    public void onDisable() {
        // 리스너가 풀린 뒤 메뉴에서 아이템을 꺼내지 못하게 먼저 닫음 (AGENTS.md 3.4, 7)
        MenuListener.closeAll(getServer());
        getServer().getScheduler().cancelTasks(this);
        if (quotaTracker != null) {
            quotaTracker.flushAll();
        }
        if (store != null) {
            store.closeClean();
        }
    }

    private static BasicCommand playerOnly(Consumer<Player> action) {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack source, String[] args) {
                if (source.getExecutor() instanceof Player player) {
                    action.accept(player);
                } else {
                    Msg.error(source.getSender(), "게임 안에서만 쓸 수 있습니다.");
                }
            }
        };
    }

    private static final class GachaCommand implements BasicCommand {
        private final MarketMenus menus;

        GachaCommand(MarketMenus menus) {
            this.menus = menus;
        }

        @Override
        public void execute(CommandSourceStack source, String[] args) {
            if (!(source.getExecutor() instanceof Player player)) {
                Msg.error(source.getSender(), "게임 안에서만 쓸 수 있습니다.");
                return;
            }
            if (args.length >= 1) {
                String arg = args[0].toLowerCase(Locale.ROOT);
                if ("확률".equals(arg) || "rates".equals(arg) || "prob".equals(arg)) {
                    menus.openGachaRates(player);
                    return;
                }
            }
            menus.openGacha(player);
        }

        @Override
        public Collection<String> suggest(CommandSourceStack source, String[] args) {
            if (args.length <= 1) {
                String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
                return List.of("확률", "rates").stream().filter(s -> s.startsWith(prefix)).toList();
            }
            return List.of();
        }
    }
}
