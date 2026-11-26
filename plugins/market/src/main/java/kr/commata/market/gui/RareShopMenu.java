package kr.commata.market.gui;

import java.sql.SQLException;
import java.util.Map;
import kr.commata.common.gui.Items;
import kr.commata.common.gui.Menu;
import kr.commata.common.time.Durations;
import kr.commata.market.MarketConfig;
import kr.commata.market.core.MarketPeriods;
import kr.commata.market.core.RareCatalog;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

/**
 * /희귀상점 9×6 GUI (ECONOMY.md 7.4.3, 13.4).
 *
 * <p>2줄 자원 5종(11~15), 3줄 책 6권(19~21, 23~25), 4줄 책 5권(29~33),
 * 6줄 내 잔액(45)·주간 초기화 안내(49)·닫기(53).
 */
final class RareShopMenu extends Menu {

    private static final int SLOT_BALANCE = 45;
    private static final int SLOT_RESET = 49;
    private static final int SLOT_CLOSE = 53;

    private final MarketMenus menus;
    private int shownBookUsed;

    RareShopMenu(MarketMenus menus, Player viewer) {
        this(menus, readBookUsed(menus, viewer));
    }

    private RareShopMenu(MarketMenus menus, int bookUsed) {
        super(6, title(bookUsed, menus.market().config().rare().bookWeeklyLimit()));
        this.menus = menus;
        this.shownBookUsed = bookUsed;
    }

    private static Component title(int bookUsed, int bookLimit) {
        return Items.text("<#F5C542>희귀 상점 (책 " + bookUsed + "/" + bookLimit + "권)");
    }

    private static int readBookUsed(MarketMenus menus, Player viewer) {
        try {
            return menus.market().store().weeklyUsed(
                    viewer.getUniqueId(), menus.market().currentWeekKey(), RareCatalog.BOOK_LIMIT_KEY);
        } catch (SQLException e) {
            return 0;
        }
    }

    @Override
    protected void draw(Player viewer) {
        MarketConfig cfg = menus.market().config();
        RareCatalog catalog = cfg.rare();
        String weekKey = menus.market().currentWeekKey();
        Map<String, Integer> usedMap;
        try {
            usedMap = menus.market().store().allWeeklyUsed(viewer.getUniqueId(), weekKey);
        } catch (SQLException e) {
            usedMap = Map.of();
        }
        int bookUsed = usedMap.getOrDefault(RareCatalog.BOOK_LIMIT_KEY, 0);
        boolean locked = menus.market().isPlayerLocked(viewer.getUniqueId());
        boolean bankReady = menus.market().bank().available();

        for (RareCatalog.Entry entry : catalog.entries()) {
            if (locked) {
                set(entry.slot(), menus.pendingLockIcon(viewer, entry.displayName()));
                continue;
            }
            int used = usedMap.getOrDefault(entry.limitKey(), 0);
            int remaining = Math.max(0, entry.weeklyLimit() - used);
            boolean soldOut = remaining <= 0;
            ItemStack icon = buildEntryIcon(entry, remaining, soldOut, bankReady);
            if (!soldOut && bankReady) {
                set(entry.slot(), icon, (player, click) -> {
                    if (click == ClickType.LEFT) {
                        menus.market().buyRare(player, entry.id());
                        if (isViewing(player)) {
                            render(player);
                        }
                    }
                });
            } else {
                set(entry.slot(), icon);
            }
        }

        set(SLOT_BALANCE, menus.balanceHead(viewer));
        String until = Durations.korean(MarketPeriods.untilNextMonday(System.currentTimeMillis(), cfg.zone()));
        set(SLOT_RESET, Items.of(Material.CLOCK)
                .name("<#F5C542>주간 초기화 안내")
                .lore("<#AAAAAA>매주 월요일 00:00 KST 초기화",
                        "<#FFDF6B>남은 시간: " + until,
                        "",
                        "<#AAAAAA>인첸트 책은 모든 종류 합산 주 " + catalog.bookWeeklyLimit() + "권까지 구매 가능합니다.")
                .build());
        set(SLOT_CLOSE, Items.close(), (player, click) -> menus.later(player::closeInventory));

        if (bookUsed != shownBookUsed) {
            shownBookUsed = bookUsed;
            retitle(viewer, title(bookUsed, catalog.bookWeeklyLimit()));
        }
    }

    private static ItemStack buildEntryIcon(RareCatalog.Entry entry, int remaining, boolean soldOut, boolean bankReady) {
        Material mat = entry.kind() == RareCatalog.Kind.BOOK
                ? Material.ENCHANTED_BOOK
                : Material.matchMaterial(entry.materialName());
        if (mat == null) {
            mat = Material.PAPER;
        }
        String name = soldOut
                ? "<#AAAAAA>" + entry.displayName() + " [이번 주 한도 소진]"
                : "<#F5C542>" + entry.displayName();
        String limitLine = entry.kind() == RareCatalog.Kind.BOOK
                ? "<#AAAAAA>책 공유 한도: <#FFDF6B>" + remaining + "/" + entry.weeklyLimit() + "권"
                : "<#AAAAAA>남은 한도: <#FFDF6B>" + remaining + "/" + entry.weeklyLimit() + "개";
        String actionLine;
        if (soldOut) {
            actionLine = "<#FF5555>이번 주 구매 한도를 모두 사용했습니다.";
        } else if (!bankReady) {
            actionLine = "<#FF5555>은행(Bank)과 연결되지 않아 구매할 수 없습니다.";
        } else {
            actionLine = "<#55FF55>▶ 좌클릭하여 구매";
        }
        return Items.of(mat)
                .name(name)
                .lore("<#AAAAAA>가격: <#FFDF6B>" + MarketPeriods.won(entry.price()),
                        limitLine,
                        "",
                        actionLine)
                .build();
    }
}
