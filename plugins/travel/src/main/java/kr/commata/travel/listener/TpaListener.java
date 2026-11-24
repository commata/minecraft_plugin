package kr.commata.travel.listener;

import kr.commata.travel.service.TeleportRequestService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/** 요청자나 수락자가 나가면 요청과 진행 중인 이동을 취소합니다. */
public final class TpaListener implements Listener {

    private final TeleportRequestService requests;

    public TpaListener(TeleportRequestService requests) {
        this.requests = requests;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        requests.onQuit(event.getPlayer());
    }
}
