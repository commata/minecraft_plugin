package kr.commata.trade.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.commata.common.gui.Items;
import kr.commata.common.gui.Menu;
import kr.commata.trade.core.ClickRules;
import kr.commata.trade.core.OfferValidation;
import kr.commata.trade.core.TradeSession;
import kr.commata.trade.service.TradeInventoryHelper;
import kr.commata.trade.service.TradeService;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 1:1 안전 거래 GUI (ECONOMY.md 7.4.6, TRADE.md 3·7장).
 *
 * <p>제목은 {@code 1:1 안전 거래} 로 고정하고(카운트다운은 슬롯 22 {@code CLOCK} 으로만 표시), 모든 클릭·드래그는
 * {@code MenuListener} 가 취소한 뒤 {@link ClickRules} 로 해석만 수행합니다. 아이템은 정산 순간까지 플레이어 인벤토리에서
 * 움직이지 않으므로, 좌측 4×4 와 우측 4×4 에는 표시용 복제본만 그려 넣습니다.
 */
public final class TradeMenu extends Menu {

    private final Server server;
    private final TradeService service;
    private final TradeUiManager uiManager;
    private final TradeSession session;
    private final TradeSession.Side mySide;

    public TradeMenu(Server server, TradeService service, TradeUiManager uiManager,
                     TradeSession session, TradeSession.Side mySide) {
        // 제목은 고정 — 매초 retitle 하면 창 재전송으로 클릭이 끊길 수 있음 (ECONOMY.md 7.4.6)
        super(6, Items.text("<#F5C542>1:1 안전 거래"));
        this.server = server;
        this.service = service;
        this.uiManager = uiManager;
        this.session = session;
        this.mySide = mySide;
    }

    public TradeSession session() {
        return session;
    }

    @Override
    protected boolean acceptsBottomClicks() {
        return true;
    }

    @Override
    protected void bottomClick(Player player, int slot, ClickType type) {
        // MenuListener 가 꾸러미 InventoryAction(내용 꺼내기·넣기)과 크리에이티브 클릭을 이미 차단하므로
        // 여기에는 순수 좌·우클릭 제시 조작만 전달됩니다 (TRADE.md 7장, P2-6).
        ClickRules.Action action = ClickRules.interpret(ClickRules.Area.BOTTOM, slot, toButton(type));
        service.handleTradeClick(player, session, action);
    }

    @Override
    protected void closed(Player viewer) {
        uiManager.unregisterMenu(viewer.getUniqueId(), this);
        // 정산 전 창 닫기는 대화창 전환 1회 예외 외에는 즉시 취소 (TRADE.md 7장)
        service.onTradeMenuClosed(viewer, session);
    }

    @Override
    protected void draw(Player viewer) {
        TradeSession.Side otherSide = mySide.other();
        TradeSession.Participant me = session.participant(mySide);
        TradeSession.Participant partner = session.participant(otherSide);
        Player partnerPlayer = server.getPlayer(partner.uuid());

        long myBalance = service.bank().balance(viewer);
        String myBalanceText = myBalance >= 0L ? OfferValidation.formatWon(myBalance) : "확인 불가";

        // 1) 좌측 4×4: 내 제시 칸 (표시 전용, 빈 칸은 null 로 비워 유리판 채움을 막음)
        List<Map.Entry<Integer, Integer>> myOffers = me.offerList();
        Map<Integer, ItemStack> mySnaps = service.snapshotsFor(session.tradeId(), mySide);
        for (int i = 0; i < ClickRules.MY_OFFER_SLOTS.length; i++) {
            int guiSlot = ClickRules.MY_OFFER_SLOTS[i];
            if (i < myOffers.size()) {
                int invSlot = myOffers.get(i).getKey();
                int offeredAmount = myOffers.get(i).getValue();
                ItemStack live = viewer.getInventory().getItem(invSlot);
                ItemStack source = !TradeInventoryHelper.isEmpty(live) ? live : mySnaps.get(invSlot);
                int heldAmount = !TradeInventoryHelper.isEmpty(live) ? live.getAmount() : offeredAmount;
                if (source != null) {
                    ItemStack display = buildMyOfferDisplay(source, invSlot, offeredAmount, heldAmount);
                    set(guiSlot, display, (p, clickType) -> handleTopClick(p, guiSlot, clickType));
                } else {
                    set(guiSlot, null);
                }
            } else {
                set(guiSlot, null);
            }
        }

        // 2) 우측 4×4: 상대 제시 칸 (읽기 전용, 실시간 표시)
        List<Map.Entry<Integer, Integer>> partnerOffers = partner.offerList();
        Map<Integer, ItemStack> partnerSnaps = service.snapshotsFor(session.tradeId(), otherSide);
        for (int i = 0; i < ClickRules.PARTNER_OFFER_SLOTS.length; i++) {
            int guiSlot = ClickRules.PARTNER_OFFER_SLOTS[i];
            if (i < partnerOffers.size()) {
                int invSlot = partnerOffers.get(i).getKey();
                int offeredAmount = partnerOffers.get(i).getValue();
                ItemStack live = (partnerPlayer != null && partnerPlayer.isOnline())
                        ? partnerPlayer.getInventory().getItem(invSlot)
                        : null;
                ItemStack source = !TradeInventoryHelper.isEmpty(live) ? live : partnerSnaps.get(invSlot);
                if (source != null) {
                    ItemStack display = buildPartnerOfferDisplay(source, offeredAmount);
                    set(guiSlot, display);
                } else {
                    set(guiSlot, null);
                }
            } else {
                set(guiSlot, null);
            }
        }

        // 3) 중앙 열: 4 내 금액, 13 상대 금액, 22 카운트다운, 31 구분선(자동 채움), 40 준비 토글, 49 취소
        if (me.inDialog()) {
            set(ClickRules.SLOT_MY_MONEY, Items.of(Material.OAK_SIGN)
                    .name("<#FFDF6B>내 제시 금액: 입력 중…")
                    .lore("<#AAAAAA>내 잔액: <#FFDF6B>" + myBalanceText,
                            "<#AAAAAA>금액 입력 대화창이 열려 있습니다.")
                    .build());
        } else {
            set(ClickRules.SLOT_MY_MONEY, Items.of(Material.OAK_SIGN)
                            .name("<#FFDF6B>내 제시 금액: " + OfferValidation.formatWon(me.money()))
                            .lore("<#AAAAAA>내 잔액: <#FFDF6B>" + myBalanceText,
                                    "<#55FF55>▶ 클릭하여 금액 입력")
                            .build(),
                    (p, clickType) -> handleTopClick(p, ClickRules.SLOT_MY_MONEY, clickType));
        }

        if (partner.inDialog()) {
            set(ClickRules.SLOT_PARTNER_MONEY, Items.of(Material.GOLD_INGOT)
                    .name("<#FFDF6B>상대 제시 금액: 입력 중…")
                    .lore("<#AAAAAA>상대가 금액을 입력하는 중입니다.")
                    .build());
        } else {
            set(ClickRules.SLOT_PARTNER_MONEY, Items.of(Material.GOLD_INGOT)
                    .name("<#FFDF6B>상대 제시 금액: " + OfferValidation.formatWon(partner.money()))
                    .lore("<#AAAAAA>상대가 제시한 금액입니다.")
                    .build());
        }

        if (session.state() == TradeSession.State.COUNTDOWN && session.countdownRemaining() > 0) {
            int sec = session.countdownRemaining();
            set(ClickRules.SLOT_COUNTDOWN, Items.of(Material.CLOCK)
                    .amount(sec)
                    .name("<#FFDF6B>거래 확정까지 " + sec + "초")
                    .lore("<#AAAAAA>제시 내용이나 준비가 바뀌면",
                            "<#AAAAAA>카운트다운이 즉시 취소됩니다.")
                    .build());
        }

        if (me.ready()) {
            set(ClickRules.SLOT_READY, Items.of(Material.LIME_CONCRETE)
                            .name("<#55FF55>준비 완료")
                            .lore("<#AAAAAA>상대도 준비하면 카운트다운이 시작됩니다.",
                                    "<#55FF55>▶ 클릭하여 준비 해제")
                            .build(),
                    (p, clickType) -> handleTopClick(p, ClickRules.SLOT_READY, clickType));
        } else {
            List<String> readyLore = me.inDialog()
                    ? List.of("<#FF5555>금액 입력 중에는 준비할 수 없습니다.")
                    : List.of("<#AAAAAA>양쪽 모두 준비하면 <#FFDF6B>" + service.config().countdownSeconds()
                                    + "초<#AAAAAA> 뒤 거래가 확정됩니다.",
                            "<#55FF55>▶ 클릭하여 확인");
            set(ClickRules.SLOT_READY, Items.of(Material.RED_CONCRETE)
                            .name("<#FF5555>준비 대기 (클릭하여 확인)")
                            .lore(readyLore)
                            .build(),
                    (p, clickType) -> handleTopClick(p, ClickRules.SLOT_READY, clickType));
        }

        set(ClickRules.SLOT_CANCEL, Items.of(Material.BARRIER)
                        .name("<#FF5555>거래 취소")
                        .lore("<#AAAAAA>거래를 즉시 취소합니다.",
                                "<#FF5555>▶ 클릭하여 취소")
                        .build(),
                (p, clickType) -> handleTopClick(p, ClickRules.SLOT_CANCEL, clickType));

        // 4) 5줄 상태 바: 36–39 내 준비 상태, 41–44 상대 준비 상태
        ItemStack myBar = me.ready()
                ? Items.of(Material.LIME_STAINED_GLASS_PANE).name("<#55FF55>내 상태: 준비 완료").build()
                : Items.of(Material.RED_STAINED_GLASS_PANE).name("<#FF5555>내 상태: 준비 대기").build();
        for (int slot : ClickRules.MY_STATUS_BAR) {
            set(slot, myBar);
        }

        ItemStack partnerBar = partner.ready()
                ? Items.of(Material.LIME_STAINED_GLASS_PANE).name("<#55FF55>상대 상태: 준비 완료").build()
                : Items.of(Material.RED_STAINED_GLASS_PANE).name("<#FF5555>상대 상태: 준비 대기").build();
        for (int slot : ClickRules.PARTNER_STATUS_BAR) {
            set(slot, partnerBar);
        }

        // 5) 6줄 정보: 45 내 정보, 53 상대 정보
        set(ClickRules.SLOT_MY_INFO, Items.of(Material.PLAYER_HEAD)
                .head(viewer)
                .name("<#F5C542>" + viewer.getName() + " (나)")
                .lore("<#AAAAAA>보유 잔액: <#FFDF6B>" + myBalanceText,
                        "<#AAAAAA>아래 인벤토리 좌클릭: 전부 제시",
                        "<#AAAAAA>아래 인벤토리 우클릭: 1개 추가")
                .build());

        Items.Builder partnerHead = Items.of(Material.PLAYER_HEAD)
                .name("<#55FFFF>" + partner.name() + " (상대)")
                .lore("<#AAAAAA>상태: " + (partner.inDialog()
                        ? "<#FFDF6B>금액 입력 중…"
                        : (partner.ready() ? "<#55FF55>준비 완료" : "<#FF5555>준비 대기")));
        if (partnerPlayer != null && partnerPlayer.isOnline()) {
            partnerHead.head(partnerPlayer);
        }
        set(ClickRules.SLOT_PARTNER_INFO, partnerHead.build());
    }

    private void handleTopClick(Player player, int slot, ClickType type) {
        ClickRules.Action action = ClickRules.interpret(ClickRules.Area.TOP, slot, toButton(type));
        service.handleTradeClick(player, session, action);
    }

    private static ClickRules.Button toButton(ClickType type) {
        if (type == ClickType.LEFT) {
            return ClickRules.Button.LEFT;
        }
        if (type == ClickType.RIGHT) {
            return ClickRules.Button.RIGHT;
        }
        return ClickRules.Button.OTHER;
    }

    /** 원본 아이템의 이름·인챈트·셜커/꾸러미 툴팁을 보존하면서 제시 안내 로어만 덧붙입니다. */
    private static ItemStack buildMyOfferDisplay(ItemStack source, int invSlot, int offeredAmount, int heldAmount) {
        ItemStack copy = source.clone();
        copy.setAmount(Math.clamp(offeredAmount, 1, Math.max(1, copy.getMaxStackSize())));
        ItemMeta meta = copy.getItemMeta();
        if (meta != null) {
            List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            if (!lore.isEmpty()) {
                lore.add(Component.empty());
            }
            lore.add(Items.text("<#AAAAAA>내 인벤토리 " + (invSlot + 1) + "번 칸 · 제시 " + offeredAmount + "/" + heldAmount));
            lore.add(Items.text("<#55FF55>▶ 좌클릭: 빼기 / 우클릭: 1개 빼기"));
            meta.lore(lore);
            copy.setItemMeta(meta);
        }
        return copy;
    }

    private static ItemStack buildPartnerOfferDisplay(ItemStack source, int offeredAmount) {
        ItemStack copy = source.clone();
        copy.setAmount(Math.clamp(offeredAmount, 1, Math.max(1, copy.getMaxStackSize())));
        ItemMeta meta = copy.getItemMeta();
        if (meta != null) {
            List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            if (!lore.isEmpty()) {
                lore.add(Component.empty());
            }
            lore.add(Items.text("<#AAAAAA>상대 제시 · " + offeredAmount + "개"));
            meta.lore(lore);
            copy.setItemMeta(meta);
        }
        return copy;
    }
}
