package kr.commata.trade;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.List;
import kr.commata.common.gui.MenuListener;
import kr.commata.trade.command.TradeCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 플레이어 간 1:1 안전 교환 플러그인 ({@code /trade}, {@code /거래}, TRADE.md, ECONOMY.md 7.3·7.4.6).
 */
public final class TradePlugin extends JavaPlugin {

    private TradeConfig tradeConfig;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        tradeConfig = TradeConfig.load(getConfig(), getLogger());

        getServer().getPluginManager().registerEvents(new MenuListener(), this);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            var r = event.registrar();
            r.register("trade", "1:1 안전 거래 (/trade [닉네임|accept|deny|cancel|claim])", List.of("거래"),
                    new TradeCommand());
        });

        getLogger().info("Trade " + getPluginMeta().getVersion() + " 활성화 (요청 "
                + tradeConfig.requestTimeoutSeconds() + "초, 카운트다운 "
                + tradeConfig.countdownSeconds() + "초, 재요청 금지 "
                + tradeConfig.reRequestCooldownSeconds() + "초, 대화창 만료 "
                + tradeConfig.dialogTimeoutSeconds() + "초)");
    }

    @Override
    public void onDisable() {
        MenuListener.closeAll(getServer());
        getServer().getScheduler().cancelTasks(this);
    }
}
