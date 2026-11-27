package kr.commata.trade;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.nio.file.Path;
import java.util.List;
import kr.commata.common.gui.MenuListener;
import kr.commata.common.playerdata.PlayerDatReader;
import kr.commata.trade.bridge.BankBridge;
import kr.commata.trade.bridge.TravelBridge;
import kr.commata.trade.command.TradeCommand;
import kr.commata.trade.gui.TradeUiManager;
import kr.commata.trade.listener.TradeListener;
import kr.commata.trade.service.TradeService;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 플레이어 간 1:1 안전 교환 플러그인 ({@code /trade}, {@code /거래}, TRADE.md, ECONOMY.md 7.3·7.4.6).
 */
public final class TradePlugin extends JavaPlugin {

    /** 미완료 저널 재확인·재지급·재반환 주기: 1분 (TRADE.md 5·6장). */
    private static final long RECOVERY_PERIOD_TICKS = 20L * 60;

    private TradeService tradeService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        TradeConfig config = TradeConfig.load(getConfig(), getLogger());

        Path dataDir = getDataFolder().toPath();
        Path worldRoot = resolveWorldRoot();
        BankBridge bank = new BankBridge(getServer(), getLogger());
        TravelBridge travel = new TravelBridge(getServer());

        tradeService = new TradeService(this, dataDir, worldRoot, bank, travel, config);
        TradeUiManager uiManager = new TradeUiManager(this, tradeService);
        tradeService.setUiHooks(uiManager);
        tradeService.startupLoadAndReconcile();

        getServer().getPluginManager().registerEvents(new MenuListener(), this);
        getServer().getPluginManager().registerEvents(new TradeListener(this, tradeService, travel), this);

        // 매 틱: 요청·대화창 만료, 제시 슬롯 실시간 검증, 카운트다운 진행
        getServer().getScheduler().runTaskTimer(this, tradeService::tickPerTick, 1L, 1L);
        // 1분마다: 미완료 저널 복구·자동 지급·자동 반환
        getServer().getScheduler().runTaskTimer(this, tradeService::tickRecovery,
                RECOVERY_PERIOD_TICKS, RECOVERY_PERIOD_TICKS);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            var r = event.registrar();
            r.register("trade", "1:1 안전 거래 (/trade [닉네임|accept|deny|cancel|claim])", List.of("거래"),
                    new TradeCommand(getServer(), getLogger(), tradeService, uiManager, this::reloadTrade));
        });

        if (!bank.available()) {
            getLogger().warning("Bank 가 없거나 잠금 모드여서 Trade 거래 기능이 잠겼습니다 (" + bank.statusText() + ").");
        }
        if (!travel.available()) {
            getLogger().info("Travel 플러그인이 없어 전투 태그 검사를 생략합니다.");
        }

        getLogger().info("Trade " + getPluginMeta().getVersion() + " 활성화 (Bank " + bank.statusText()
                + ", Travel " + travel.statusText()
                + ", 저널 " + (tradeService.journalStore().isLocked() ? "잠김" : "정상")
                + ", 미완료 저널 " + tradeService.openJournals().size() + "건)");
    }

    private String reloadTrade() {
        reloadConfig();
        TradeConfig reloaded = TradeConfig.load(getConfig(), getLogger());
        tradeService.updateConfig(reloaded);
        String journalResult = tradeService.reloadJournalsIfLocked();
        if (journalResult != null) {
            return journalResult;
        }
        getLogger().info("Trade 설정 리로드 완료 (요청 " + reloaded.requestTimeoutSeconds() + "초, 카운트다운 "
                + reloaded.countdownSeconds() + "초, 재요청 금지 " + reloaded.reRequestCooldownSeconds()
                + "초, 대화창 만료 " + reloaded.dialogTimeoutSeconds() + "초)");
        return "Trade 설정을 다시 불러왔습니다.";
    }

    @Override
    public void onDisable() {
        MenuListener.closeAll(getServer());
        getServer().getScheduler().cancelTasks(this);
        if (tradeService != null) {
            tradeService.shutdown();
        }
    }

    private Path resolveWorldRoot() {
        if (!getServer().getWorlds().isEmpty()) {
            return PlayerDatReader.worldRoot(getServer().getWorlds().get(0).getWorldFolder().toPath());
        }
        return getServer().getWorldContainer().toPath().resolve("world").toAbsolutePath().normalize();
    }
}
