package kr.commata.trade.gui;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import kr.commata.trade.Msg;
import kr.commata.trade.core.TradeSession;
import kr.commata.trade.service.TradeInventoryHelper;
import kr.commata.trade.service.TradeService;
import kr.commata.trade.service.TradeUiHooks;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * 열려 있는 {@link TradeMenu}·{@link PlayerSelectMenu}·{@link MoneyDialog} 를 관리하는 {@link TradeUiHooks} 구현.
 */
public final class TradeUiManager implements TradeUiHooks {

    private final Plugin plugin;
    private final Server server;
    private final TradeService service;
    private final Map<UUID, TradeMenu> openMenus = new HashMap<>();

    public TradeUiManager(Plugin plugin, TradeService service) {
        this.plugin = plugin;
        this.server = plugin.getServer();
        this.service = service;
    }

    @Override
    public void openTradeMenu(Player player, TradeSession session) {
        if (player == null || !player.isOnline()) {
            return;
        }
        TradeSession.Side side = session.sideOf(player.getUniqueId());
        if (side == null) {
            return;
        }
        // 커서에 아이템이 있으면 창을 열지 않음 (TRADE.md 7·13장)
        if (!TradeInventoryHelper.isEmpty(player.getItemOnCursor())) {
            Msg.error(player, "손에 든 아이템을 먼저 내려놓아 주세요.");
            return;
        }
        TradeMenu menu = new TradeMenu(server, service, this, session, side);
        openMenus.put(player.getUniqueId(), menu);
        menu.open(player);
    }

    @Override
    public UUID activeTradeMenuId(Player player, TradeSession session) {
        if (player == null || !player.isOnline()) {
            return null;
        }
        TradeMenu menu = openMenus.get(player.getUniqueId());
        if (menu == null || menu.session() != session || !menu.isViewing(player)) {
            return null;
        }
        return menu.menuId();
    }

    @Override
    public void refreshSessionMenus(TradeSession session) {
        refreshSide(session.sideA().uuid(), session);
        refreshSide(session.sideB().uuid(), session);
    }

    private void refreshSide(UUID playerId, TradeSession session) {
        TradeMenu menu = openMenus.get(playerId);
        if (menu == null || menu.session() != session) {
            return;
        }
        Player player = server.getPlayer(playerId);
        if (player == null || !player.isOnline() || !menu.isViewing(player)) {
            openMenus.remove(playerId, menu);
            return;
        }
        menu.render(player);
    }

    @Override
    public void closeTradeMenuNextTick(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        TradeMenu menu = openMenus.get(uuid);
        if (menu == null) {
            return;
        }
        server.getScheduler().runTask(plugin, () -> {
            Player online = server.getPlayer(uuid);
            if (online != null && online.isOnline() && menu.isViewing(online)) {
                online.closeInventory();
            }
        });
    }

    @Override
    public void openMoneyDialog(Player player, TradeSession session, UUID dialogToken, long currentBalance) {
        TradeSession.Side side = session.sideOf(player.getUniqueId());
        if (side == null) {
            return;
        }
        MoneyDialog.open(plugin, service, player, session, side, dialogToken, currentBalance);
    }

    @Override
    public void closeDialog(Player player) {
        if (player != null && player.isOnline()) {
            player.closeDialog();
        }
    }

    @Override
    public void openPlayerSelectMenu(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        new PlayerSelectMenu(plugin, service, player).open(player);
    }

    void unregisterMenu(UUID playerId, TradeMenu menu) {
        openMenus.remove(playerId, menu);
    }
}
