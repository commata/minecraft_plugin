package kr.commata.market.gui;

import java.sql.SQLException;
import kr.commata.common.gui.Items;
import kr.commata.common.gui.Menu;
import kr.commata.market.core.GachaCatalog;
import kr.commata.market.core.MarketPeriods;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * /뽑기 9×3 GUI (ECONOMY.md 7.4.4, 13.5).
 *
 * <p>2줄: 1회(11), 10회(13), 확률 정보(15). 3줄: 내 잔액(18), 전설 천장 현황(22), 닫기(26).
 */
final class GachaMenu extends Menu {

    private static final int SLOT_PULL_1 = 11;
    private static final int SLOT_PULL_10 = 13;
    private static final int SLOT_RATES = 15;
    private static final int SLOT_BALANCE = 18;
    private static final int SLOT_PITY = 22;
    private static final int SLOT_CLOSE = 26;

    private final MarketMenus menus;
    private boolean readErrorLogged;

    GachaMenu(MarketMenus menus) {
        super(3, Items.text("<#F5C542>인첸트 책 뽑기"));
        this.menus = menus;
    }

    @Override
    protected void draw(Player viewer) {
        GachaCatalog gacha = menus.market().config().gacha();
        String dayKey = menus.market().currentDayKey();
        int used = 0;
        int pity = 0;
        boolean readError = false;
        try {
            used = menus.market().store().dailyPullsUsed(viewer.getUniqueId(), dayKey);
            pity = menus.market().store().pity(viewer.getUniqueId());
        } catch (SQLException e) {
            readError = true;
            if (!readErrorLogged) {
                readErrorLogged = true;
                menus.plugin().getLogger().warning("뽑기 한도·천장 조회 실패 (" + viewer.getName() + "): " + e.getMessage());
            }
        }
        int remaining = Math.max(0, gacha.dailyLimit() - used);
        boolean locked = !readError && menus.market().isPlayerLocked(viewer.getUniqueId());
        boolean bankReady = menus.market().bank().available();

        if (readError) {
            set(SLOT_PULL_1, menus.readErrorIcon("1회 뽑기"));
            set(SLOT_PULL_10, menus.readErrorIcon("10회 뽑기"));
        } else if (locked) {
            set(SLOT_PULL_1, menus.pendingLockIcon(viewer, "1회 뽑기"));
            set(SLOT_PULL_10, menus.pendingLockIcon(viewer, "10회 뽑기"));
        } else {
            drawPullButton(SLOT_PULL_1, 1, gacha, remaining, pity, bankReady);
            drawPullButton(SLOT_PULL_10, 10, gacha, remaining, pity, bankReady);
        }

        set(SLOT_RATES, Items.of(Material.PAPER)
                .name("<#F5C542>확률 정보")
                .lore("<#AAAAAA>등급별 확률과 등장하는 인첸트 책 전체 목록을 확인합니다.",
                        "",
                        "<#55FF55>▶ 클릭하여 확률표 보기 (/뽑기 확률)")
                .build(), (player, click) -> menus.later(() -> menus.openGachaRates(player)));

        set(SLOT_BALANCE, menus.balanceHead(viewer));
        if (readError) {
            set(SLOT_PITY, Items.of(Material.NETHER_STAR)
                    .name("<#F5C542>전설 천장 현황")
                    .lore("<#FF5555>전설 천장: 확인 불가",
                            "<#FF5555>오늘 남은 뽑기: 확인 불가",
                            "",
                            "<#AAAAAA>잠시 후 다시 열어 주세요.")
                    .build());
        } else {
            set(SLOT_PITY, Items.of(Material.NETHER_STAR)
                    .name("<#F5C542>전설 천장 현황")
                    .lore("<#FFDF6B>전설 천장: " + pity + " / " + gacha.pityThreshold(),
                            "<#FFDF6B>오늘 남은 뽑기: " + remaining + " / " + gacha.dailyLimit() + "회",
                            "",
                            "<#AAAAAA>" + gacha.pityThreshold() + "회째 전설 확정 (전설 획득 시에만 초기화)",
                            "<#AAAAAA>일간 한도는 매일 00:00 KST 초기화")
                    .build());
        }
        set(SLOT_CLOSE, Items.close(), (player, click) -> menus.later(player::closeInventory));
    }

    private void drawPullButton(int slot, int count, GachaCatalog gacha, int remaining, int pity, boolean bankReady) {
        long cost = gacha.price() * count;
        boolean enoughLimit = remaining >= count;
        boolean clickable = enoughLimit && bankReady;
        String name = enoughLimit
                ? "<#F5C542>" + count + "회 뽑기"
                : "<#AAAAAA>" + count + "회 뽑기 [오늘 한도 부족]";
        String actionLine;
        if (!enoughLimit) {
            actionLine = "<#FF5555>오늘 남은 뽑기 횟수가 부족합니다.";
        } else if (!bankReady) {
            actionLine = "<#FF5555>은행(Bank)과 연결되지 않아 뽑을 수 없습니다.";
        } else {
            actionLine = "<#55FF55>▶ 좌클릭하여 " + count + "회 뽑기";
        }
        ItemStack icon = Items.of(enoughLimit ? Material.ENCHANTED_BOOK : Material.GRAY_DYE)
                .name(name)
                .lore("<#AAAAAA>가격: <#FFDF6B>" + MarketPeriods.won(cost),
                        "<#AAAAAA>필요 빈칸: <white>" + count + "칸",
                        "<#AAAAAA>오늘 남은 횟수: <#FFDF6B>" + remaining + " / " + gacha.dailyLimit() + "회",
                        "<#AAAAAA>전설 천장: <#FFDF6B>" + pity + " / " + gacha.pityThreshold(),
                        "",
                        actionLine)
                .build();
        if (count > 1 && enoughLimit) {
            // ENCHANTED_BOOK 기본 스택 상한이 1이라 아이콘에 "10" 수량을 표시하려면 maxStackSize 지정
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                meta.setMaxStackSize(count);
                icon.setItemMeta(meta);
            }
            icon.setAmount(count);
        }
        if (clickable) {
            set(slot, icon, (player, click) -> {
                if (click == ClickType.LEFT) {
                    menus.market().pullGacha(player, count);
                    if (isViewing(player)) {
                        render(player);
                    }
                }
            });
        } else {
            set(slot, icon);
        }
    }
}
