package kr.commata.travel.listener;

import kr.commata.travel.cast.CastManager;
import kr.commata.travel.logic.ActionClassifier;
import kr.commata.travel.logic.CancelReason;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 귀환 중 플레이어가 스스로 한 행동으로 귀환을 취소합니다 (쿨타임 없음).
 * 걷기·달리기·점프·시점 회전·아이템 줍기는 감지하지 않으므로 그대로 진행됩니다.
 */
public final class ActivityListener implements Listener {

    private final CastManager casts;

    public ActivityListener(CastManager casts) {
        this.casts = casts;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        casts.cancel(event.getPlayer(), CancelReason.BLOCK_PLACE);
    }

    /** 블록을 캐기 시작한 순간 (다 캐기 전). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMineStart(BlockDamageEvent event) {
        casts.cancel(event.getPlayer(), CancelReason.BLOCK_BREAK);
    }

    /**
     * 우클릭으로 먹기 시작, 활 당기기, 아이템 사용, 도구로 블록 손질.
     * 허공 우클릭은 처음부터 '취소된' 상태로 들어오므로 ignoreCancelled 를 쓰지 않고
     * 아이템 사용이 거부됐는지만 확인합니다.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        if (!event.getAction().isRightClick() || event.useItemInHand() == Event.Result.DENY) {
            return;
        }
        Player player = event.getPlayer();
        if (!casts.isCasting(player)) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || item.getType().isAir()) {
            return;
        }
        boolean clickedBlock = event.getAction() == Action.RIGHT_CLICK_BLOCK;
        ActionClassifier.rightClick(itemId(item), item.getType().isEdible(), clickedBlock)
                .ifPresent(reason -> casts.cancel(player, reason));
    }

    /** 좌클릭 휘두르기. 빈 손으로 허공을 치는 건 허용하고, 무기·도구를 휘두를 때만 취소합니다. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }
        Player player = event.getPlayer();
        if (!casts.isCasting(player)) {
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        ActionClassifier.swing(itemId(hand)).ifPresent(reason -> casts.cancel(player, reason));
    }

    // 아래 두 개는 우클릭 감지를 놓쳤을 때를 위한 보조 장치

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        casts.cancel(event.getPlayer(), CancelReason.EAT);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player player) {
            casts.cancel(player, CancelReason.RANGED);
        }
    }

    /** 엔더 진주, 포털, 다른 플러그인의 순간이동 등. 귀환 자체의 이동은 시전이 끝난 뒤라 해당 없음. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        casts.cancel(event.getPlayer(), CancelReason.MOVED_AWAY);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        casts.cancel(event.getPlayer(), CancelReason.SILENT);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        casts.cancel(event.getPlayer(), CancelReason.SILENT);
    }

    private static String itemId(ItemStack item) {
        return item == null ? "" : item.getType().getKey().getKey();
    }
}
