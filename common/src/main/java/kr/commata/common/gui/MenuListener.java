package kr.commata.common.gui;

import org.bukkit.Server;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

/**
 * {@link Menu} 창의 아이템 이동을 모두 막습니다 (ECONOMY.md 7.4): 클릭, Shift 클릭, 숫자키·F 바꾸기, 더블클릭 모으기,
 * 드래그, 크리에이티브 복제, 아래(플레이어) 인벤토리 클릭까지. 위쪽 창 버튼의 좌·우클릭(메뉴가 허용한 칸은 Shift+좌클릭도)만
 * {@link ClickLock} 을 거쳐 메뉴로 넘깁니다.
 *
 * <p>플러그인마다 하나 등록합니다. {@code common} 은 플러그인마다 다른 패키지로 옮겨지므로 다른 플러그인의 메뉴와 섞이지 않습니다.
 * 플러그인을 끌 때 {@link #closeAll(Server)} 를 불러 열린 메뉴를 닫으세요(리스너가 사라진 뒤 아이템을 꺼내지 못하게).
 */
public final class MenuListener implements Listener {

    /** 버튼 연타 방지 시간 (ECONOMY.md 7.4: 200–300ms) */
    public static final long CLICK_LOCK_MS = 250;

    private final ClickLock lock = new ClickLock(CLICK_LOCK_MS);

    // 다른 플러그인이 취소를 풀어도 다시 막도록 가장 늦게, 취소된 이벤트도 받음
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof Menu menu)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != top) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= top.getSize()) {
            return;
        }
        // 슬롯 교환·버리기·모으기는 버튼 조작으로 전달하지 않는다. Shift+좌클릭은 메뉴가 그 칸에 허용했을 때만.
        switch (event.getClick()) {
            case LEFT, RIGHT -> { }
            case SHIFT_LEFT -> {
                if (!menu.acceptsShiftLeft(slot)) {
                    return;
                }
            }
            default -> { return; }
        }
        if (!lock.tryAcquire(player.getUniqueId(), System.currentTimeMillis())) {
            return;
        }
        menu.click(player, slot, event.getClick());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu menu
                && event.getPlayer() instanceof Player player) {
            menu.close(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lock.forget(event.getPlayer().getUniqueId());
    }

    /** 이 플러그인의 메뉴를 보고 있는 모든 플레이어의 창을 닫습니다. onDisable 에서 호출. */
    public static void closeAll(Server server) {
        for (Player player : server.getOnlinePlayers()) {
            HumanEntity viewer = player;
            if (viewer.getOpenInventory().getTopInventory().getHolder() instanceof Menu) {
                viewer.closeInventory();
            }
        }
    }
}
