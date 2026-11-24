package kr.commata.travel.gui;

import kr.commata.travel.Chat;
import kr.commata.travel.TravelPlugin;
import kr.commata.travel.service.TeleportRequestService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** /tpa 메뉴 클릭 처리. 메뉴 안의 아이템은 꺼내거나 옮길 수 없습니다. */
public final class TpaMenuListener implements Listener {

    private final TravelPlugin plugin;
    private final TeleportRequestService requests;

    public TpaMenuListener(TravelPlugin plugin, TeleportRequestService requests) {
        this.plugin = plugin;
        this.requests = requests;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof TpaMenu menu)) {
            return;
        }
        event.setCancelled(true); // 아이템 이동은 모두 막음 (아래 인벤토리에서 Shift 클릭 포함)
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        menu.actionAt(event.getRawSlot()).ifPresent(action -> {
            switch (action) {
                case TpaMenu.Action.Page p -> {
                    menu.setPage(p.page());
                    menu.render(player);
                }
                case TpaMenu.Action.Toggle t -> {
                    requests.toggle(player);
                    menu.render(player);
                }
                case TpaMenu.Action.CancelOutgoing c -> {
                    requests.cancel(player);
                    menu.render(player);
                }
                case TpaMenu.Action.Request r -> {
                    // 인벤토리 이벤트 안에서 바로 닫지 않고 다음 틱에 처리
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        player.closeInventory();
                        Player target = plugin.getServer().getPlayer(r.target());
                        if (target == null || !player.canSee(target)) {
                            Chat.TPA.error(player, "그 플레이어는 접속 중이 아닙니다.");
                            return;
                        }
                        requests.send(player, target);
                    });
                }
            }
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof TpaMenu) {
            event.setCancelled(true);
        }
    }
}
