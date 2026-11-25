package kr.commata.economyhub.guard;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.StructureGrowEvent;

/**
 * 피스톤으로 움직인 블록, 용암·물로 생긴 블록은 직업 보상을 주지 않습니다 (ECONOMY.md 7.2, 8장).
 *
 * <p>VJobs 2.2.0 은 설치한 블록을 좌표로만 기억하고 피스톤 이동을 따라가지 않습니다. 그래서 설치한 원목을 피스톤으로 밀면
 * 자연 블록으로 보고 다시 보상을 줍니다. 여기서 움직인 블록의 도착 좌표를 표시해 두고, 그 블록을 캘 때 나오는
 * VJobs 보상 이벤트(JobPayoutEvent)를 취소합니다.
 *
 * <p>순서: BlockBreakEvent LOWEST(표시 확인) → VJobs NORMAL(보상 계산, 그 안에서 JobPayoutEvent) → MONITOR(정리).
 * 모두 메인 스레드에서 한 블록씩 처리되므로 "지금 캐는 블록이 표시된 블록인가"를 필드 하나로 들고 있으면 됩니다.
 */
public final class PayoutGuard implements Listener {

    private final UntrustedBlocks marks;
    private volatile boolean enabled;
    private volatile Set<Material> formed;

    /** 지금 처리 중인 BlockBreakEvent 의 블록이 표시된 블록이면 그 플레이어. 아니면 null. */
    private UUID breakingMarked;

    public PayoutGuard(UntrustedBlocks marks, boolean enabled, Set<Material> formed) {
        this.marks = marks;
        configure(enabled, formed);
    }

    public void configure(boolean enabled, Set<Material> formed) {
        this.enabled = enabled;
        this.formed = formed.isEmpty() ? EnumSet.noneOf(Material.class) : EnumSet.copyOf(formed);
    }

    /** config.yml payout-guard.enabled 현재 값 (/economyhub reload 반영). */
    public boolean enabled() {
        return enabled;
    }

    /** VJobs JobPayoutEvent 에서 호출. BREAK·HARVEST 보상이 표시된 블록에서 나온 것이면 true(취소). */
    public boolean shouldCancel(Player player, String actionType) {
        if (!enabled || breakingMarked == null || !breakingMarked.equals(player.getUniqueId())) {
            return false;
        }
        return "BREAK".equals(actionType) || "HARVEST".equals(actionType);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreakEarly(BlockBreakEvent event) {
        breakingMarked = enabled && marks.isMarked(event.getBlock()) ? event.getPlayer().getUniqueId() : null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreakLate(BlockBreakEvent event) {
        boolean wasMarked = breakingMarked != null;
        breakingMarked = null;
        if (wasMarked && !event.isCancelled()) {
            marks.unmark(event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExtend(BlockPistonExtendEvent event) {
        move(event.getBlocks(), event.getDirection());
    }

    /** Paper 26.3 은 당길 때도 getDirection() 이 블록이 움직이는 방향이라(PistonBaseBlock), 밀 때와 똑같이 도착 좌표만 표시. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRetract(BlockPistonRetractEvent event) {
        move(event.getBlocks(), event.getDirection());
    }

    private void move(List<Block> blocks, BlockFace direction) {
        if (!enabled || blocks.isEmpty()) {
            return;
        }
        // 출발 좌표의 표시를 먼저 지우고 도착 좌표에 표시 (블록 줄에서 출발·도착이 겹쳐도 최종 상태가 맞음)
        for (Block block : blocks) {
            marks.unmark(block);
        }
        for (Block block : blocks) {
            marks.mark(block.getRelative(direction));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        if (enabled && formed.contains(event.getNewState().getType())) {
            marks.mark(event.getBlock());
        }
    }

    /** 플레이어가 그 자리에 블록을 놓으면 VJobs 의 설치 블록 기록이 맡으므로 표시를 지움. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        marks.unmark(event.getBlockPlaced());
    }

    /** 작물·호박·수박·사탕수수가 새로 자란 자리는 자연 블록. 예전 표시가 남아 있으면 지움. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        marks.unmark(event.getBlock());
    }

    /** 나무가 자란 자리도 자연 블록. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        for (BlockState state : event.getBlocks()) {
            marks.unmark(state.getBlock());
        }
    }
}
