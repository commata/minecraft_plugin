package kr.commata.trade.listener;

import java.util.UUID;
import kr.commata.trade.bridge.TravelBridge;
import kr.commata.trade.service.TradeService;
import org.bukkit.GameMode;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;

/**
 * 거래 라이프사이클 관련 Bukkit 이벤트 리스너 (TRADE.md 2·6·7·14장).
 *
 * <p>Paper 26.3-258 이벤트 순서(코드·API 확인):
 * <ol>
 *   <li>접속({@link PlayerJoinEvent}, {@code LOWEST}): 플레이어 `.dat` 로드 직후 실행되므로 새 {@code connGen} 을
 *       부여하고, 이전 기동/접속에서 남은 {@code TAKING/COMMITTED/RETURNING} 저널을 다른 플러그인 처리 전에 복구·지급합니다.</li>
 *   <li>강퇴·퇴장({@link PlayerKickEvent} → {@link PlayerQuitEvent}, 모두 {@code MONITOR}):
 *       Paper {@code PlayerList#remove} 는 {@code PlayerQuitEvent} 를 호출한 <b>뒤</b>에 플레이어 `.dat` 를 디스크에 저장합니다.
 *       따라서 퇴장 이벤트에서는 {@code connGen} 제거와 정산 전 세션 즉시 취소만 수행하고, {@code TAKING} 저널의 디스크 재판정은
 *       다음 틱({@code runTask})으로 미뤄 퇴장 저장 완료 후 `.dat` 를 읽습니다.</li>
 *   <li>사망({@link PlayerDeathEvent}, {@code MONITOR}): 제시 방식은 아이템이 움직이지 않으므로 keepInventory 여부와 무관하게
 *       정산 전 세션만 즉시 취소합니다. 사망 중인 수령자에게는 지급·반환을 보류하고
 *       {@link PlayerRespawnEvent}(MONITOR, 다음 틱) 뒤 생존 상태에서 재개합니다.</li>
 *   <li>전투({@link EntityDamageByEntityEvent}, {@code MONITOR}): Travel 의 전투 태그 PDC({@code home:combat_until}) 기록이
 *       끝난 다음 틱에 양쪽 플레이어의 전투 상태를 재확인해 거래를 취소합니다.</li>
 *   <li>창 닫기({@code InventoryCloseEvent}): {@code common.gui.MenuListener}(MONITOR) 가 {@code TradeMenu.closed} 를
 *       호출해 {@link TradeService#onTradeMenuClosed} 로 전달합니다. 대화창 전환 1회 예외는 {@code player.closeInventory()}
 *       호출 시 동기 발생하는 {@code Reason.PLUGIN} 닫기에서 소비됩니다.</li>
 * </ol>
 */
public final class TradeListener implements Listener {

    private final Plugin plugin;
    private final Server server;
    private final TradeService service;
    private final TravelBridge travel;

    public TradeListener(Plugin plugin, TradeService service, TravelBridge travel) {
        this.plugin = plugin;
        this.server = plugin.getServer();
        this.service = service;
        this.travel = travel;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        service.onPlayerJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        service.onPlayerQuit(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        service.cancelIfActive(event.getPlayer().getUniqueId(), "강제 퇴장");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        service.cancelIfActive(event.getEntity().getUniqueId(), "사망");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        // 리스폰 완료 후 다음 틱(생존 상태)에서 사망으로 보류된 지급·반환 재개 (TRADE.md 5·6·7장)
        server.getScheduler().runTask(plugin, () -> {
            Player online = server.getPlayer(uuid);
            if (online != null && online.isOnline() && !online.isDead()) {
                service.onPlayerRespawn(online);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        GameMode next = event.getNewGameMode();
        if (next == GameMode.CREATIVE || next == GameMode.SPECTATOR) {
            service.cancelIfActive(event.getPlayer().getUniqueId(), "게임 모드 변경");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCombatDamage(EntityDamageByEntityEvent event) {
        if (!travel.available()) {
            return;
        }
        Player victim = event.getEntity() instanceof Player p ? p : null;
        Player attacker = resolveAttacker(event.getDamager());
        if (victim == null && attacker == null) {
            return;
        }
        UUID victimId = victim != null ? victim.getUniqueId() : null;
        UUID attackerId = attacker != null ? attacker.getUniqueId() : null;
        if (victimId != null && !service.sessions().hasSession(victimId)) {
            victimId = null;
        }
        if (attackerId != null && !service.sessions().hasSession(attackerId)) {
            attackerId = null;
        }
        if (victimId == null && attackerId == null) {
            return;
        }
        final UUID checkVictim = victimId;
        final UUID checkAttacker = attackerId;
        // Travel 리스너가 home:combat_until PDC 를 갱신한 뒤 다음 틱에 재확인 (TRADE.md 7장)
        server.getScheduler().runTask(plugin, () -> {
            long nowMs = System.currentTimeMillis();
            if (checkVictim != null) {
                Player p = server.getPlayer(checkVictim);
                if (p != null && p.isOnline() && travel.inCombat(p, nowMs)) {
                    service.cancelIfActive(checkVictim, "전투 상태 진입");
                }
            }
            if (checkAttacker != null) {
                Player p = server.getPlayer(checkAttacker);
                if (p != null && p.isOnline() && travel.inCombat(p, nowMs)) {
                    service.cancelIfActive(checkAttacker, "전투 상태 진입");
                }
            }
        });
    }

    private static Player resolveAttacker(Entity damager) {
        if (damager instanceof Player p) {
            return p;
        }
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) {
            return p;
        }
        return null;
    }
}
