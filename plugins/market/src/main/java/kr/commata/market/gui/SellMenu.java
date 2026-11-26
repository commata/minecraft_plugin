package kr.commata.market.gui;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.commata.common.gui.Items;
import kr.commata.common.gui.Menu;
import kr.commata.common.time.Durations;
import kr.commata.market.MarketConfig;
import kr.commata.market.core.MarketPeriods;
import kr.commata.market.core.MarketStore;
import kr.commata.market.core.SellCatalog;
import kr.commata.market.core.SellMath;
import kr.commata.market.service.ItemFactory;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

/**
 * /판매 9×6 GUI (ECONOMY.md 7.4.5, 14.3, 14.4).
 *
 * <p>1줄 직업 탭(2~6), 2–4줄 품목 21칸(10–16, 19–25, 28–34), 5줄 일일 한도 게이지 7칸(37–43),
 * 6줄 내 잔액(45)·일일 초기화 안내(49)·닫기(53).
 */
final class SellMenu extends Menu {

    private static final int GAUGE_START = 37;
    private static final int SLOT_BALANCE = 45;
    private static final int SLOT_RESET = 49;
    private static final int SLOT_CLOSE = 53;

    private final MarketMenus menus;
    private String selectedJobId;

    SellMenu(MarketMenus menus, Player viewer) {
        super(6, Items.text("<#F5C542>아이템 판매 (/판매)"));
        this.menus = menus;
        this.selectedJobId = initialTab(menus, viewer);
    }

    private static String initialTab(MarketMenus menus, Player viewer) {
        SellCatalog catalog = menus.market().config().sell();
        Set<String> joined = menus.market().vjobs().joinedJobIds(viewer.getUniqueId());
        if (joined != null) {
            for (SellCatalog.Category cat : catalog.categories()) {
                if (joined.contains(cat.jobId())) {
                    return cat.jobId();
                }
            }
        }
        return catalog.categories().isEmpty() ? null : catalog.categories().get(0).jobId();
    }

    @Override
    protected void draw(Player viewer) {
        MarketConfig cfg = menus.market().config();
        SellCatalog catalog = cfg.sell();
        Set<String> joined = menus.market().vjobs().joinedJobIds(viewer.getUniqueId());
        Map<String, MarketStore.Quota> quotas = menus.market().quotasOf(viewer.getUniqueId());
        String dayKey = menus.market().currentDayKey();
        long todayTotal = 0L;
        try {
            todayTotal = menus.market().store().dailySalesTotal(viewer.getUniqueId(), dayKey);
        } catch (SQLException ignored) {
            // 아래에서 처리
        }
        long dailyLimit = catalog.dailyLimit();
        long remainingLimit = Math.max(0L, dailyLimit - todayTotal);
        boolean locked = menus.market().isPlayerLocked(viewer.getUniqueId());
        boolean bankReady = menus.market().bank().available();

        // 1줄: 직업 탭
        SellCatalog.Category activeCat = catalog.category(selectedJobId);
        if (activeCat == null && !catalog.categories().isEmpty()) {
            activeCat = catalog.categories().get(0);
            selectedJobId = activeCat.jobId();
        }
        for (SellCatalog.Category cat : catalog.categories()) {
            boolean isJoined = joined != null && joined.contains(cat.jobId());
            boolean isSelected = activeCat != null && cat.jobId().equals(activeCat.jobId());
            Material iconMat = Material.matchMaterial(isJoined ? cat.joinedIconMaterial() : cat.iconMaterial());
            if (iconMat == null) {
                iconMat = Material.CHEST;
            }
            List<String> lore = new ArrayList<>();
            if (isJoined) {
                lore.add("<#55FF55>[직업 보너스 " + cat.bonusLabel() + " 적용 중]");
            } else {
                lore.add("<#AAAAAA>기본가로 판매 가능 (직업 보유 시 " + cat.bonusLabel() + ")");
            }
            if (isSelected) {
                lore.add("<#55FFFF>[현재 탭]");
            } else {
                lore.add("");
                lore.add("<#55FF55>▶ 클릭하여 탭 전환");
            }
            String titleColor = isJoined ? "<#55FF55>" : "<#F5C542>";
            ItemStack tabIcon = Items.of(iconMat)
                    .glow(isSelected || (isJoined && cat.glowWhenJoined()))
                    .name(titleColor + cat.displayName())
                    .lore(lore)
                    .build();
            set(cat.tabSlot(), tabIcon, (player, click) -> {
                if (!cat.jobId().equals(selectedJobId)) {
                    selectedJobId = cat.jobId();
                    render(player);
                }
            });
        }

        // 2–4줄: 선택된 탭의 품목
        if (activeCat != null) {
            boolean hasJob = joined != null && joined.contains(activeCat.jobId());
            List<SellCatalog.Item> items = activeCat.items();
            for (int i = 0; i < items.size() && i < SellCatalog.ITEM_SLOTS.length; i++) {
                SellCatalog.Item item = items.get(i);
                int slot = SellCatalog.ITEM_SLOTS[i];
                if (locked) {
                    set(slot, menus.pendingLockIcon(viewer, item.displayName()));
                    continue;
                }
                Material mat = Material.matchMaterial(item.id());
                if (mat == null) {
                    continue;
                }
                int owned = ItemFactory.countSellable(viewer, mat);
                MarketStore.Quota q = quotas.getOrDefault(item.id(), MarketStore.Quota.ZERO);
                long jobAmount = hasJob ? q.jobAmount() : 0L;
                long baseAmount = q.baseAmount();
                SellMath.Plan onePlan = SellMath.calculate(
                        owned, jobAmount, baseAmount, item.unit(),
                        item.basePrice(), item.bonusPrice(), remainingLimit, 1);

                List<String> lore = new ArrayList<>();
                lore.add("<#AAAAAA>판매 단위: <white>" + item.unit() + "개");
                if (hasJob) {
                    lore.add("<#AAAAAA>단가: <white>기본 " + MarketPeriods.won(item.basePrice())
                            + " → <#55FF55>보너스 " + MarketPeriods.won(item.bonusPrice()));
                } else {
                    lore.add("<#AAAAAA>단가: <#FFDF6B>기본 " + MarketPeriods.won(item.basePrice())
                            + " <#AAAAAA>(직업 보너스 " + MarketPeriods.won(item.bonusPrice()) + ")");
                }
                lore.add("");
                lore.add("<#AAAAAA>보유 수량: <white>" + owned + "개");
                lore.add("<#AAAAAA>보너스 판매 가능: <#55FF55>" + jobAmount + "개");
                lore.add("<#AAAAAA>기본 판매 가능: <#FFDF6B>" + baseAmount + "개");
                lore.add("<#AAAAAA>오늘 남은 한도: <#FFDF6B>" + MarketPeriods.won(remainingLimit));
                lore.add("");
                if (!bankReady) {
                    lore.add("<#FF5555>은행(Bank)과 연결되지 않아 판매할 수 없습니다.");
                } else if (onePlan.isEmpty()) {
                    lore.add("<#FF5555>판매 가능한 수량 또는 한도가 부족합니다.");
                } else {
                    lore.add("<#55FF55>▶ 좌클릭: 1묶음 판매 / 우클릭: 최대 판매");
                }

                ItemStack icon = Items.of(mat)
                        .name("<#F5C542>" + item.displayName())
                        .lore(lore)
                        .build();

                if (bankReady && !onePlan.isEmpty()) {
                    set(slot, icon, (player, click) -> {
                        if (click == ClickType.LEFT || click == ClickType.RIGHT) {
                            menus.market().sellItem(player, item.id(), click == ClickType.RIGHT);
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

        // 5줄: 일일 한도 게이지 7칸 (슬롯 37–43, ECONOMY.md 7.4.5)
        int filled = SellMath.gaugeFilled(todayTotal, dailyLimit);
        boolean full = todayTotal >= dailyLimit;
        String gaugeTitle = full ? "<#FF5555>일일 판매 한도 도달" : "<#F5C542>오늘 판매 한도";
        String gaugeLore = "<#FFDF6B>오늘 판매액: " + MarketPeriods.won(todayTotal)
                + " / " + MarketPeriods.won(dailyLimit);
        for (int i = 0; i < SellMath.GAUGE_SLOTS; i++) {
            Material pane;
            if (full) {
                pane = Material.RED_STAINED_GLASS_PANE;
            } else if (i < filled) {
                pane = Material.LIME_STAINED_GLASS_PANE;
            } else {
                pane = Material.LIGHT_GRAY_STAINED_GLASS_PANE;
            }
            set(GAUGE_START + i, Items.of(pane).name(gaugeTitle).lore(gaugeLore).build());
        }

        // 6줄: 내 잔액, 일일 초기화 안내, 닫기
        set(SLOT_BALANCE, menus.balanceHead(viewer));
        String until = Durations.korean(MarketPeriods.untilNextMidnight(System.currentTimeMillis(), cfg.zone()));
        set(SLOT_RESET, Items.of(Material.CLOCK)
                .name("<#F5C542>일일 초기화 안내")
                .lore("<#AAAAAA>매일 00:00 KST 초기화",
                        "<#FFDF6B>남은 시간: " + until,
                        "",
                        "<#AAAAAA>직접 채집·수확·사냥·낚시로 얻은 수량만 판매 가능합니다.",
                        "<#AAAAAA>내 직업 2개 품목은 보너스 가격이 적용됩니다.")
                .build());
        set(SLOT_CLOSE, Items.close(), (player, click) -> menus.later(player::closeInventory));
    }
}
