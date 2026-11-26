package kr.commata.market.service;

import java.sql.SQLException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;
import kr.commata.market.bridge.UntrustedBlockReader;
import kr.commata.market.core.BlockTrustState;
import kr.commata.market.core.BreakLedger;
import kr.commata.market.core.DropFilter;
import kr.commata.market.core.FarmerCropRules;
import kr.commata.market.core.HubStatus;
import kr.commata.market.core.QuotaBuffer;
import kr.commata.market.core.SeedCrops;
import kr.commata.market.core.SellCatalog;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.BlockState;
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
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.world.StructureGrowEvent;
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
 *   <li>{@link PlayerHarvestBlockEvent} NORMAL: 달콤한 열매 덤불 우클릭 수확 드롭을 {@link BreakLedger}에 저장하고,
 *       MONITOR 에서 취소되지 않았음이 확인되면 집계합니다. EconomyHub 는 본 이벤트를 가로채지 않습니다.</li>
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

    /** 성숙 검사 없이 파괴 시 농부로 집계하는 블록들 (사탕수수·선인장·버섯, ECONOMY.md 14.4 v0.9.3). */
    private static final Set<Material> DIRECT_FARMER_BLOCKS = EnumSet.of(
            Material.SUGAR_CANE, Material.CACTUS,
            Material.BROWN_MUSHROOM, Material.RED_MUSHROOM,
            Material.BROWN_MUSHROOM_BLOCK, Material.RED_MUSHROOM_BLOCK, Material.MUSHROOM_STEM);

    private record BlockKey(UUID worldUid, int x, int y, int z) {
        static BlockKey of(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }

    private record BreakContext(
            UUID playerUid,
            Material blockType,
            BlockTrustState trustState,
            boolean silkTouch,
            boolean matureCrop
    ) {
        boolean untrusted() {
            return trustState != BlockTrustState.NATURAL;
        }
    }

    private record HarvestContext(UUID playerUid, Map<Material, Integer> counts) {
    }

    private final MarketService market;
    private final Logger logger;
    private final java.util.function.Supplier<HubStatus> hubStatusSupplier;
    /** 마지막으로 경고한 Hub 상태 (중복 경고 방지). */
    private HubStatus lastWarnedStatus;
    private final QuotaBuffer buffer = new QuotaBuffer();
    /** 연속 반영 실패 중인지 (첫 실패와 회복 시에만 로그 출력). */
    private boolean flushFailing;
    /** BlockBreakEvent 에서 읽은 상태를 같은 블록의 BlockDropItemEvent 까지 보관 (좌표 키, 연쇄 파괴에도 안전). */
    private final BreakLedger<BlockKey, BreakContext> pendingBreaks = new BreakLedger<>();
    /** PlayerHarvestBlockEvent NORMAL 에서 읽은 상태를 MONITOR(취소 확인)까지 보관. */
    private final BreakLedger<BlockKey, HarvestContext> pendingHarvests = new BreakLedger<>();

    public QuotaTracker(MarketService market, Logger logger) {
        this(market, logger, defaultHubStatusSupplier(), null);
    }

    public QuotaTracker(MarketService market, Logger logger, BooleanSupplier economyHubAvailable) {
        this(market, logger, () -> economyHubAvailable.getAsBoolean() ? HubStatus.AVAILABLE : HubStatus.DISABLED, null);
    }

    public QuotaTracker(MarketService market, Logger logger, BooleanSupplier economyHubAvailable, boolean initialWarned) {
        this(market, logger, () -> economyHubAvailable.getAsBoolean() ? HubStatus.AVAILABLE : HubStatus.DISABLED,
                initialWarned ? HubStatus.DISABLED : null);
    }

    public QuotaTracker(MarketService market, Logger logger, java.util.function.Supplier<HubStatus> hubStatusSupplier, HubStatus initialWarned) {
        this.market = market;
        this.logger = logger;
        this.hubStatusSupplier = hubStatusSupplier != null ? hubStatusSupplier : defaultHubStatusSupplier();
        this.lastWarnedStatus = initialWarned;
    }

    private static java.util.function.Supplier<HubStatus> defaultHubStatusSupplier() {
        return () -> {
            org.bukkit.plugin.Plugin hub = Bukkit.getPluginManager().getPlugin("EconomyHub");
            if (hub == null || !hub.isEnabled()) {
                return HubStatus.DISABLED;
            }
            if (hub.getDescription() == null || hub.getDescription().getProvides() == null
                    || !hub.getDescription().getProvides().contains("economyhub-block-marks-v1")) {
                return HubStatus.UNSUPPORTED_SPEC;
            }
            return HubStatus.AVAILABLE;
        };
    }

    /** 현재 EconomyHub 규격 지원 상태를 반환합니다. */
    public HubStatus hubStatus() {
        return hubStatusSupplier.get();
    }

    /** 블록 판매 가능량 집계가 가능한 상태인지 (EconomyHub 활성화 및 v1 규격 지원 여부). */
    public boolean isBlockTrackingAvailable() {
        return hubStatus().isAvailable();
    }

    private boolean checkTrackingAvailableAndLog() {
        HubStatus status = hubStatus();
        if (status.isAvailable()) {
            if (lastWarnedStatus != null) {
                lastWarnedStatus = null;
                logger.info("EconomyHub 설치 블록 기록 규격(v1)이 확인되어 블록 판매 가능량 집계가 재개되었습니다.");
            }
            return true;
        }

        if (lastWarnedStatus != status) {
            lastWarnedStatus = status;
            if (status == HubStatus.DISABLED) {
                logger.warning("EconomyHub 가 없거나 꺼져 있어 블록(광부·벌목꾼·농부) 판매 가능량 집계를 차단합니다.");
            } else if (status == HubStatus.UNSUPPORTED_SPEC) {
                logger.warning("EconomyHub 버전이 설치 블록 기록 규격(v1)을 지원하지 않아 블록 판매 가능량 집계를 차단합니다.");
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 블록 (광부·벌목꾼·농부)

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        // 드롭 이벤트가 오지 않고 남은 지난 틱 기록 정리
        pendingBreaks.purgeBefore(Bukkit.getCurrentTick());
        pendingHarvests.purgeBefore(Bukkit.getCurrentTick());
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
        if (!checkTrackingAvailableAndLog()) {
            return;
        }
        BlockTrustState trustState = FarmerCropRules.isPlacementCheckIgnored(type.name())
                ? BlockTrustState.NATURAL
                : UntrustedBlockReader.judge(block);
        ItemStack hand = player.getInventory().getItemInMainHand();
        boolean silkTouch = hand.containsEnchantment(Enchantment.SILK_TOUCH);
        boolean matureCrop = false;
        if (MATURE_CROPS.contains(type) && block.getBlockData() instanceof Ageable ageable) {
            matureCrop = ageable.getAge() >= ageable.getMaximumAge();
        } else if (type == Material.SWEET_BERRY_BUSH && block.getBlockData() instanceof Ageable ageable) {
            matureCrop = FarmerCropRules.isMatureBerryBush(ageable.getAge());
        }
        pendingBreaks.put(BlockKey.of(block), Bukkit.getCurrentTick(),
                new BreakContext(player.getUniqueId(), type, trustState, silkTouch, matureCrop));
    }

    /**
     * NORMAL 뒤에 다른 플러그인이 파괴를 취소했거나 드롭을 껐으면 기록을 지웁니다. 결과만 읽으므로 MONITOR,
     * 취소된 이벤트도 받아야 하므로 {@code ignoreCancelled = false}.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockBreakResult(BlockBreakEvent event) {
        if (event.isCancelled() || !event.isDropItems()) {
            pendingBreaks.remove(BlockKey.of(event.getBlock()));
            return;
        }
        // 정상 파괴 시 Market 자체 비트맵 표시 해제
        UntrustedBlockReader.unmarkMarket(event.getBlock());
    }

    /**
     * 플레이어가 블록을 설치했을 때 Market 자체 청크 비트맵에 표시합니다 (MONITOR).
     * EconomyHub 가 꺼져 있거나 재시작 중단 상태에서 설치된 블록도 추후 채광 시 자연산으로 집계되지 않도록 방지합니다.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        if (isTrackedBlock(block.getType())) {
            UntrustedBlockReader.markMarket(block);
        }
    }

    /**
     * 작물이나 호박·수박·선인장·사탕수수 등이 자연 성장했을 때 블록의 비트맵 표시를 정리합니다.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockGrow(BlockGrowEvent event) {
        UntrustedBlockReader.unmarkMarket(event.getBlock());
    }

    /**
     * 버섯 퍼짐 등 자연 확산으로 새로 생긴 블록의 비트맵 표시를 정리합니다.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        UntrustedBlockReader.unmarkMarket(event.getBlock());
    }

    /**
     * 묘목이 나무로 성장했을 때 생성된 원목 블록들의 비트맵 표시를 정리합니다.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        for (BlockState state : event.getBlocks()) {
            UntrustedBlockReader.unmarkMarket(state.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockDrop(BlockDropItemEvent event) {
        if (!isBlockTrackingAvailable()) {
            return;
        }
        BreakContext ctx = pendingBreaks.take(BlockKey.of(event.getBlock()));
        if (ctx == null || !event.getPlayer().getUniqueId().equals(ctx.playerUid())) {
            return;
        }
        if (ctx.trustState() != BlockTrustState.NATURAL) {
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
            if (!FarmerCropRules.isEligibleDrop(ctx.blockType().name(), stack.getType().name())) {
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

    // ------------------------------------------------------------------ 우클릭 수확 (달콤한 열매)

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onHarvest(PlayerHarvestBlockEvent event) {
        pendingHarvests.purgeBefore(Bukkit.getCurrentTick());
        if (market.dbLocked()) {
            return;
        }
        Player player = event.getPlayer();
        if (!isSurvivalOrAdventure(player)) {
            return;
        }
        if (!checkTrackingAvailableAndLog()) {
            return;
        }
        Block block = event.getHarvestedBlock();
        if (block.getType() != Material.SWEET_BERRY_BUSH) {
            return;
        }
        Map<Material, Integer> counts = new EnumMap<>(Material.class);
        for (ItemStack item : event.getItemsHarvested()) {
            if (item != null && item.getType() == Material.SWEET_BERRIES && item.getAmount() > 0) {
                counts.merge(item.getType(), item.getAmount(), Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            return;
        }
        pendingHarvests.put(BlockKey.of(block), Bukkit.getCurrentTick(),
                new HarvestContext(player.getUniqueId(), counts));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHarvestResult(PlayerHarvestBlockEvent event) {
        BlockKey key = BlockKey.of(event.getHarvestedBlock());
        if (event.isCancelled()) {
            pendingHarvests.remove(key);
            return;
        }
        HarvestContext ctx = pendingHarvests.take(key);
        if (ctx == null || !event.getPlayer().getUniqueId().equals(ctx.playerUid())) {
            return;
        }
        recordDrops(event.getPlayer(), "farmer", ctx.counts());
    }

    private static boolean isTrackedBlock(Material type) {
        return type.name().endsWith("_ORE")
                || type == Material.ANCIENT_DEBRIS
                || LOG_BLOCKS.contains(type)
                || MATURE_CROPS.contains(type)
                || type == Material.PUMPKIN
                || type == Material.MELON
                || DIRECT_FARMER_BLOCKS.contains(type)
                || type == Material.SWEET_BERRY_BUSH;
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
        if (DIRECT_FARMER_BLOCKS.contains(type)) {
            return "farmer";
        }
        if (type == Material.SWEET_BERRY_BUSH) {
            return ctx.matureCrop() ? "farmer" : null;
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
