package kr.commata.market.gui;

import kr.commata.common.gui.Items;
import kr.commata.market.Msg;
import kr.commata.market.service.MarketService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Market 자체 GUI 열기와 공통 아이콘 도우미 (ECONOMY.md 7.4). */
public final class MarketMenus {

    private final Plugin plugin;
    private final MarketService market;

    public MarketMenus(Plugin plugin, MarketService market) {
        this.plugin = plugin;
        this.market = market;
    }

    public Plugin plugin() {
        return plugin;
    }

    public MarketService market() {
        return market;
    }

    public void later(Runnable action) {
        plugin.getServer().getScheduler().runTask(plugin, action);
    }

    public void openRareShop(Player player) {
        if (market.dbLocked()) {
            Msg.error(player, "마켓이 잠금 모드입니다. 관리자에게 문의해 주세요.");
            return;
        }
        if (!market.config().rare().available()) {
            Msg.error(player, "희귀 상점이 현재 잠겨 있습니다 (" + market.config().rare().lockReason() + ").");
            return;
        }
        new RareShopMenu(this, player).open(player);
    }

    public void openGacha(Player player) {
        if (market.dbLocked()) {
            Msg.error(player, "마켓이 잠금 모드입니다. 관리자에게 문의해 주세요.");
            return;
        }
        if (!market.config().gacha().available()) {
            Msg.error(player, "인첸트 뽑기가 현재 잠겨 있습니다 (" + market.config().gacha().lockReason() + ").");
            return;
        }
        new GachaMenu(this).open(player);
    }

    public void openGachaRates(Player player) {
        if (!market.config().gacha().available()) {
            Msg.error(player, "인첸트 뽑기가 현재 잠겨 있습니다 (" + market.config().gacha().lockReason() + ").");
            return;
        }
        new GachaRatesMenu(this).open(player);
    }

    public void openSell(Player player) {
        if (market.dbLocked()) {
            Msg.error(player, "마켓이 잠금 모드입니다. 관리자에게 문의해 주세요.");
            return;
        }
        if (!market.config().sell().available()) {
            Msg.error(player, "아이템 판매가 현재 잠겨 있습니다 (" + market.config().sell().lockReason() + ").");
            return;
        }
        // /판매를 열 때마다 탈퇴 이관 보정 (ECONOMY.md 14.2)
        market.reconcileJobQuotas(player);
        new SellMenu(this, player).open(player);
    }

    public ItemStack balanceHead(Player viewer) {
        return Items.of(Material.PLAYER_HEAD)
                .head(viewer)
                .name("<#F5C542>내 잔액")
                .lore("<#FFDF6B>" + market.bank().formattedBalance(viewer))
                .build();
    }

    /** 저장 확인 대기 등으로 플레이어 Market 이 잠겼을 때 거래 버튼 대체 아이콘 (ECONOMY.md 7.4.5). */
    public ItemStack pendingLockIcon(Player viewer, String title) {
        boolean claimable = market.hasClaimableOrder(viewer.getUniqueId());
        if (claimable) {
            return Items.of(Material.GRAY_DYE)
                    .name("<#AAAAAA>" + title)
                    .lore("<#FF5555>처리 중(저장 확인 대기)",
                            "<#FFDF6B>미수령 구매품이 있습니다.",
                            "<#55FF55>▶ /market claim 으로 먼저 수령해 주세요.")
                    .build();
        }
        return Items.of(Material.GRAY_DYE)
                .name("<#AAAAAA>" + title)
                .lore("<#FF5555>처리 중(저장 확인 대기)",
                        "<#AAAAAA>이전 거래가 확정되면 자동으로 풀립니다.")
                .build();
    }

    /** DB 조회 실패 시 거래 버튼을 대체하는 아이콘 (AGENTS.md 2.8 fail-closed). 클릭 핸들러 없이 사용. */
    public ItemStack readErrorIcon(String title) {
        return Items.of(Material.GRAY_DYE)
                .name("<#AAAAAA>" + title)
                .lore("<#FF5555>기록을 읽지 못했습니다",
                        "<#AAAAAA>잠시 후 다시 열어 주세요")
                .build();
    }
}
