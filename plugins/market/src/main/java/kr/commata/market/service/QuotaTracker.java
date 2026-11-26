package kr.commata.market.service;

import java.sql.SQLException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import kr.commata.market.bridge.UntrustedBlockReader;
import kr.commata.market.core.BreakLedger;
import kr.commata.market.core.DropFilter;
import kr.commata.market.core.QuotaBuffer;
import kr.commata.market.core.SeedCrops;
import kr.commata.market.core.SellCatalog;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
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
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

/**
 * 플레이어가 직접 채집·벌목·수확·사냥·낚시로 얻은 수량을 {@code sell_quota}에 기록합니다 (ECONOMY.md 14.2).
 *
 * <p>이벤트 순서 (ECONOMY.md 15.5, PayoutGuard):
 * <ul>
 *   <li>{@link BlockBreakEvent} NORMAL: EconomyHub {@code PayoutGuard.onBreakLate}(MONITOR)가 청크 PDC 표시를
 *       지우기 전에 자연 블록 여부·실크터치 여부·작물 성숙 여부를 블록 좌표별로 읽어 둡니다({@link BreakLedger}).
 *       뒤에서 다른 플러그인이 취소하면 MONITOR 에서 그 기록을 지웁니다.</li>
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

    private record BlockKey(UUID worldUid, int x, int y, int z) {
        static BlockKey of(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }

    private record BreakContext(
            UUID playerUid,
            Material blockType,
            boolean untrusted,
            boolean silkTouch,
            boolean matureCrop
    ) {
    }

    private final MarketService market;
    private final Logger logger;
    private final QuotaBuffer buffer = new QuotaBuffer();
    /** 연속 반영 실패 중인지 (첫 실패와 회복 시에만 로그 출력). */
    private boolean flushFailing;
    /** BlockBreakEvent 에서 읽은 상태를 같은 블록의 BlockDropItemEvent 까지 보관 (좌표 키, 연쇄 파괴에도 안전). */
    private final BreakLedger<BlockKey, BreakContext> pendingBreaks = new BreakLedger<>();

    public QuotaTracker(MarketService market, Logger logger) {
        this.market = market;
        this.logger = logger;
    }

    // ------------------------------------------------------------------ 블록 (광부·벌목꾼·농부)

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        // 드롭 이벤트가 오지 않고 남은 지난 틱 기록 정리
        pendingBreaks.purgeBefore(Bukkit.getCurrentTick());
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
        pendingBreaks.put(BlockKey.of(block), Bukkit.getCurrentTick(),
                new BreakContext(player.getUniqueId(), type, untrusted, silkTouch, matureCrop));
    }

    /**
     * NORMAL 뒤에 다른 플러그인이 파괴를 취소했거나 드롭을 껐으면 기록을 지웁니다. 결과만 읽으므로 MONITOR,
     * 취소된 이벤트도 받아야 하므로 {@code ignoreCancelled = false}.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockBreakResult(BlockBreakEvent event) {
        if (event.isCancelled() || !event.isDropItems()) {
            pendingBreaks.remove(BlockKey.of(event.getBlock()));
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockDrop(BlockDropItemEvent event) {
        BreakContext ctx = pendingBreaks.take(BlockKey.of(event.getBlock()));
        if (ctx == null || !event.getPlayer().getUniqueId().equals(ctx.playerUid())) {
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
        if (SeedCrops.replantItem(ctx.blockType().name()) != null) {
            // 감자·당근·네더 사마귀·코코아 콩: 다시 심을 1개를 빼고 기록 (ECONOMY.md 14.2)
            Map<String, Integer> byName = new LinkedHashMap<>();
            counts.forEach((mat, n) -> byName.put(mat.name(), n));
            counts.clear();
            SeedCrops.harvestQuota(ctx.blockType().name(), byName).forEach((name, n) -> {
                Material mat = Material.matchMaterial(name);
                if (mat != null) {
                    counts.put(mat, n);
                }
            });
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
        Map<String, Integer> dropCounts = new LinkedHashMap<>();
        for (ItemStack stack : event.getDrops()) {
            addItemCount(dropCounts, stack);
        }
        Map<String, Integer> equipCounts = new LinkedHashMap<>();
        EntityEquipment eq = entity.getEquipment();
        if (eq != null) {
            addItemCount(equipCounts, eq.getItemInMainHand());
            addItemCount(equipCounts, eq.getItemInOffHand());
            for (ItemStack armor : eq.getArmorContents()) {
                addItemCount(equipCounts, armor);
            }
        }
        Map<String, Integer> filtered = DropFilter.subtractEquipment(dropCounts, equipCounts);
        if (filtered.isEmpty()) {
            return;
        }
        Map<Material, Integer> counts = new EnumMap<>(Material.class);
        for (Map.Entry<String, Integer> entry : filtered.entrySet()) {
            Material mat = Material.matchMaterial(entry.getKey());
            if (mat != null) {
                counts.put(mat, entry.getValue());
            }
        }
        recordDrops(killer, "hunter", counts);
    }

    private static void addItemCount(Map<String, Integer> target, ItemStack stack) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
            return;
        }
        target.merge(stack.getType().name(), stack.getAmount(), Integer::sum);
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
