package kr.commata.market.service;

import java.sql.SQLException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import kr.commata.market.bridge.UntrustedBlockReader;
import kr.commata.market.core.QuotaBuffer;
import kr.commata.market.core.SellCatalog;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 플레이어가 직접 채집·벌목·수확·사냥·낚시로 얻은 수량을 {@code sell_quota}에 기록합니다 (ECONOMY.md 14.2).
 *
 * <p>이벤트 순서 (ECONOMY.md 15.5, PayoutGuard):
 * <ul>
 *   <li>{@link BlockBreakEvent} NORMAL: EconomyHub {@code PayoutGuard.onBreakLate}(MONITOR)가 청크 PDC 표시를
 *       지우기 전에 자연 블록 여부·실크터치 여부·작물 성숙 여부를 읽어 둡니다.</li>
 *   <li>{@link BlockDropItemEvent} NORMAL: EconomyHub {@code SkillListener}(HIGHEST)의 재파종·자석이 드롭 목록을
 *       수정하기 전에 실제 드롭 수량을 집계합니다.</li>
 *   <li>{@link EntityDeathEvent} NORMAL: {@code SkillListener}(HIGHEST)의 자석보다 먼저 드롭을 집계합니다.</li>
 *   <li>{@link PlayerFishEvent} NORMAL: {@code SkillListener}(다음 틱 자석)보다 먼저 집계하며 VJobs AFK 판정 중에는 제외합니다.</li>
 * </ul>
 */
public final class QuotaTracker implements Listener {

    /** 다 자란 작물 블록 집합 (Ageable 성숙 검사 대상). */
    private static final Set<Material> MATURE_CROPS = EnumSet.of(
            Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS,
            Material.NETHER_WART, Material.COCOA);

    /** 벌목꾼 매입 원목·줄기 11종 (껍질 벗긴 원목·나무 블록 제외, ECONOMY.md 7.4.5). */
    private static final Set<Material> LOG_BLOCKS = EnumSet.of(
            Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG, Material.JUNGLE_LOG,
            Material.ACACIA_LOG, Material.DARK_OAK_LOG, Material.MANGROVE_LOG, Material.CHERRY_LOG,
            Material.PALE_OAK_LOG, Material.CRIMSON_STEM, Material.WARPED_STEM);

    private record BreakContext(
            UUID worldUid,
            int x,
            int y,
            int z,
            UUID playerUid,
            Material blockType,
            boolean untrusted,
            boolean silkTouch,
            boolean matureCrop
    ) {
        boolean matches(Block block, Player player) {
            return block.getWorld().getUID().equals(worldUid)
                    && block.getX() == x && block.getY() == y && block.getZ() == z
                    && player.getUniqueId().equals(playerUid);
        }
    }

    private final MarketService market;
    private final Logger logger;
    private final QuotaBuffer buffer = new QuotaBuffer();
    /** 연속 반영 실패 중인지 (첫 실패와 회복 시에만 로그 출력). */
    private boolean flushFailing;
    /** 메인 스레드에서 BlockBreakEvent 직후 바로 이어지는 BlockDropItemEvent 와 매칭. */
    private BreakContext pendingBreak;

    public QuotaTracker(MarketService market, Logger logger) {
        this.market = market;
        this.logger = logger;
    }

    // ------------------------------------------------------------------ 블록 (광부·벌목꾼·농부)

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        pendingBreak = null;
        if (market.dbLocked()) {
            return;
        }
        Player player = event.getPlayer();
        if (!isSurvivalOrAdventure(player)) {
            return;
        }
        Block block = event.getBlock();
        Material type = block.getType();
        if (!isTrackedBlock(type)) {
            return;
        }
        boolean untrusted = UntrustedBlockReader.isMarked(block);
        ItemStack hand = player.getInventory().getItemInMainHand();
        boolean silkTouch = hand.containsEnchantment(Enchantment.SILK_TOUCH);
        boolean matureCrop = false;
        if (MATURE_CROPS.contains(type) && block.getBlockData() instanceof Ageable ageable) {
            matureCrop = ageable.getAge() >= ageable.getMaximumAge();
        }
        Location loc = block.getLocation();
        pendingBreak = new BreakContext(
                loc.getWorld().getUID(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                player.getUniqueId(), type, untrusted, silkTouch, matureCrop);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockDrop(BlockDropItemEvent event) {
        BreakContext ctx = pendingBreak;
        pendingBreak = null;
        if (ctx == null || !ctx.matches(event.getBlock(), event.getPlayer())) {
            return;
        }
        if (ctx.untrusted()) {
            return;
        }
        String categoryJob = resolveBlockCategory(ctx);
        if (categoryJob == null) {
            return;
        }
        Map<Material, Integer> counts = new EnumMap<>(Material.class);
        for (Item dropped : event.getItems()) {
            ItemStack stack = dropped.getItemStack();
            if (stack.getAmount() <= 0 || stack.getType().isAir()) {
                continue;
            }
            counts.merge(stack.getType(), stack.getAmount(), Integer::sum);
        }
        recordDrops(event.getPlayer(), categoryJob, counts);
    }

    private static boolean isTrackedBlock(Material type) {
        return type.name().endsWith("_ORE")
                || type == Material.ANCIENT_DEBRIS
                || LOG_BLOCKS.contains(type)
                || MATURE_CROPS.contains(type)
                || type == Material.PUMPKIN
                || type == Material.MELON;
    }

    private static String resolveBlockCategory(BreakContext ctx) {
        Material type = ctx.blockType();
        if (type.name().endsWith("_ORE") || type == Material.ANCIENT_DEBRIS) {
            // 실크터치로 캔 광석 블록은 기록 안 함 (ECONOMY.md 14.2)
            return ctx.silkTouch() ? null : "miner";
        }
        if (LOG_BLOCKS.contains(type)) {
            return "lumberjack";
        }
        if (MATURE_CROPS.contains(type)) {
            return ctx.matureCrop() ? "farmer" : null;
        }
        if (type == Material.PUMPKIN || type == Material.MELON) {
            // 실크터치 수박은 MELON 블록이 떨어져 매입 품목(MELON_SLICE)에 매칭되지 않으므로 자동 제외
            return "farmer";
        }
        return null;
    }

    // ------------------------------------------------------------------ 사냥꾼

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (market.dbLocked()) {
            return;
        }
        LivingEntity entity = event.getEntity();
        Player killer = entity.getKiller();
        if (killer == null || !isSurvivalOrAdventure(killer)
                || !(entity instanceof Enemy) || entity instanceof Player || entity.fromMobSpawner()) {
            return;
        }
        Map<Material, Integer> counts = new EnumMap<>(Material.class);
        for (ItemStack stack : event.getDrops()) {
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
                continue;
            }
            counts.merge(stack.getType(), stack.getAmount(), Integer::sum);
        }
        recordDrops(killer, "hunter", counts);
    }

    // ------------------------------------------------------------------ 낚시꾼

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (market.dbLocked()) {
            return;
        }
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item item)) {
            return;
        }
        Player player = event.getPlayer();
        if (!isSurvivalOrAdventure(player)) {
            return;
        }
        if (market.vjobs().isAfk(player.getUniqueId())) {
            return;
        }
        ItemStack stack = item.getItemStack();
        if (stack.getType().isAir() || stack.getAmount() <= 0) {
            return;
        }
        recordDrops(player, "fisherman", Map.of(stack.getType(), stack.getAmount()));
    }

    // ------------------------------------------------------------------ 공통 기록·일괄 반영

    /** 메모리 버퍼에 합산합니다(20틱마다 한 트랜잭션으로 일괄 반영, ECONOMY.md 14.2). */
    private void recordDrops(Player player, String expectedJob, Map<Material, Integer> drops) {
        if (drops.isEmpty()) {
            return;
        }
        SellCatalog catalog = market.config().sell();
        Set<String> joinedJobs = market.vjobs().joinedJobIds(player.getUniqueId());
        boolean hasJob = joinedJobs != null && joinedJobs.contains(expectedJob);
        for (Map.Entry<Material, Integer> entry : drops.entrySet()) {
            String itemId = entry.getKey().name();
            SellCatalog.Item item = catalog.item(itemId);
            if (item == null || !expectedJob.equals(item.jobId())) {
                continue;
            }
            buffer.add(player.getUniqueId(), item.id(), entry.getValue(), hasJob);
        }
    }

    /** 20틱 주기 타이머·서버 종료 시 버퍼 전체를 한 트랜잭션으로 DB 에 반영합니다. */
    public boolean flushAll() {
        return flush(buffer.drainAll());
    }

    /** 판매 시작·메뉴 열기·탈퇴 보정·퇴장 시 해당 플레이어의 버퍼만 먼저 DB 에 반영합니다. */
    public boolean flushPlayer(UUID uuid) {
        return flush(buffer.drainPlayer(uuid));
    }

    private boolean flush(Map<QuotaBuffer.Key, Long> drained) {
        if (drained.isEmpty()) {
            return true;
        }
        if (market.dbLocked()) {
            buffer.restore(drained);
            return false;
        }
        try {
            market.store().addQuotaBatch(drained);
            if (flushFailing) {
                flushFailing = false;
                logger.info("판매 가능량 일괄 반영이 정상 회복되었습니다.");
            }
            return true;
        } catch (SQLException e) {
            buffer.restore(drained);
            if (!flushFailing) {
                flushFailing = true;
                logger.warning("판매 가능량 일괄 반영 실패 (버퍼 보존, 다음 주기에 재시도): " + e.getMessage());
            }
            return false;
        }
    }

    private static boolean isSurvivalOrAdventure(Player player) {
        GameMode mode = player.getGameMode();
        return mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE;
    }
}
