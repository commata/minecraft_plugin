package kr.commata.economyhub.skills;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.commata.common.cooldown.Cooldowns;
import kr.commata.common.gui.Items;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Lv.10·20 편의 스킬의 이벤트 처리 (ECONOMY.md 15.3, 15.5).
 *
 * <p>자석은 원래 이벤트를 취소하지 않고 떨어질 아이템만 인벤토리로 옮깁니다. 블록 드롭은 BlockDropItemEvent HIGHEST(Market 이
 * NORMAL–HIGH 에서 직접 획득 수량을 먼저 기록), 몹 드롭은 EntityDeathEvent HIGHEST, 낚시는 다음 틱(VJobs·Market 이
 * PlayerFishEvent 를 다 읽은 뒤)에 옮깁니다. 인벤토리가 가득 차면 남은 만큼은 원래대로 바닥에 떨어집니다.
 * 재파종·재식재는 BlockBreakEvent 를 그대로 통과시키고 1틱 뒤에 인벤토리에서 1개를 써서 심습니다(보상·기록 그대로, 복사 없음).
 */
public final class SkillListener implements Listener {

    private static final Set<Material> STONES = EnumSet.of(Material.STONE, Material.DEEPSLATE, Material.TUFF,
            Material.GRANITE, Material.DIORITE, Material.ANDESITE, Material.CALCITE, Material.ANCIENT_DEBRIS);

    /** 다 자란 뒤 수확·재파종하는 작물 → 심을 때 쓰는 아이템 */
    private static final Map<Material, Material> CROP_SEEDS = new EnumMap<>(Map.of(
            Material.WHEAT, Material.WHEAT_SEEDS,
            Material.CARROTS, Material.CARROT,
            Material.POTATOES, Material.POTATO,
            Material.BEETROOTS, Material.BEETROOT_SEEDS,
            Material.NETHER_WART, Material.NETHER_WART,
            Material.COCOA, Material.COCOA_BEANS));

    private static final Set<Material> FARMER_DROPS = EnumSet.of(Material.WHEAT, Material.CARROTS, Material.POTATOES,
            Material.BEETROOTS, Material.NETHER_WART, Material.COCOA, Material.PUMPKIN, Material.MELON);

    /** 원목 → 묘목(균·번식체)과 심을 수 있는 바닥 */
    private record Sapling(Material item, Tag<Material> ground) {
    }

    private static final Map<Material, Sapling> SAPLINGS = new EnumMap<>(Material.class);

    static {
        sapling(Material.OAK_LOG, Material.OAK_SAPLING);
        sapling(Material.SPRUCE_LOG, Material.SPRUCE_SAPLING);
        sapling(Material.BIRCH_LOG, Material.BIRCH_SAPLING);
        sapling(Material.JUNGLE_LOG, Material.JUNGLE_SAPLING);
        sapling(Material.ACACIA_LOG, Material.ACACIA_SAPLING);
        sapling(Material.DARK_OAK_LOG, Material.DARK_OAK_SAPLING);
        sapling(Material.CHERRY_LOG, Material.CHERRY_SAPLING);
        sapling(Material.PALE_OAK_LOG, Material.PALE_OAK_SAPLING);
        sapling(Material.MANGROVE_LOG, Material.MANGROVE_PROPAGULE);
        SAPLINGS.put(Material.CRIMSON_STEM, new Sapling(Material.CRIMSON_FUNGUS, Tag.NYLIUM));
        SAPLINGS.put(Material.WARPED_STEM, new Sapling(Material.WARPED_FUNGUS, Tag.NYLIUM));
    }

    private static void sapling(Material log, Material sapling) {
        SAPLINGS.put(log, new Sapling(sapling, Tag.DIRT));
    }

    /** 나뭇잎 급속 소멸: 한 번에 확인할 최대 수, 틱당 처리 수 */
    private static final int LEAF_SCAN_LIMIT = 400;
    private static final int LEAVES_PER_TICK = 16;
    /** 바닐라 나뭇잎 distance 최댓값: 7이면 원목과 이어지지 않아 원래 사라질 잎 */
    private static final int LEAF_MAX_DISTANCE = 7;

    private final Skills skills;
    private final Cooldowns cooldowns = new Cooldowns();

    public SkillListener(Skills skills) {
        this.skills = skills;
    }

    // ------------------------------------------------------------------ 자석 (블록)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockDrop(BlockDropItemEvent event) {
        Player player = event.getPlayer();
        Skill magnet = blockMagnet(event.getBlockState().getType());
        if (magnet != null && skills.active(player, magnet)) {
            absorbItems(player, event.getItems());
        }
    }

    private static Skill blockMagnet(Material type) {
        if (type.name().endsWith("_ORE") || STONES.contains(type)) {
            return Skill.MINER_MAGNET;
        }
        if (Tag.LOGS.isTagged(type) || Tag.LEAVES.isTagged(type)) {
            return Skill.LUMBERJACK_MAGNET;
        }
        if (FARMER_DROPS.contains(type)) {
            return Skill.FARMER_MAGNET;
        }
        return null;
    }

    /** 떨어질 아이템 엔티티 목록에서 인벤토리에 들어간 만큼 빼고, 남은 분량만 남김 */
    private static void absorbItems(Player player, List<Item> items) {
        Iterator<Item> it = items.iterator();
        while (it.hasNext()) {
            Item item = it.next();
            ItemStack left = give(player, item.getItemStack());
            if (left == null) {
                it.remove();
            } else {
                item.setItemStack(left);
            }
        }
    }

    /** 인벤토리에 넣고 못 들어간 분량을 돌려줌 (다 들어가면 null) */
    private static ItemStack give(Player player, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return null;
        }
        Map<Integer, ItemStack> left = player.getInventory().addItem(stack.clone());
        return left.isEmpty() ? null : left.values().iterator().next();
    }

    // ------------------------------------------------------------------ 재식재·재파종·나뭇잎

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Material type = block.getType();

        if (Tag.LOGS.isTagged(type)) {
            Sapling sapling = SAPLINGS.get(type);
            Material ground = block.getRelative(BlockFace.DOWN).getType();
            if (sapling != null && sapling.ground().isTagged(ground) && skills.active(player, Skill.LUMBERJACK_REPLANT)) {
                later(1, () -> replantSapling(player, block, sapling.item(), ground));
            }
            if (skills.active(player, Skill.LUMBERJACK_LEAVES)) {
                // 나뭇잎 거리(distance) 갱신은 여러 틱에 걸쳐 퍼지므로 두 번 확인
                Location at = block.getLocation();
                later(10, () -> decayLeaves(player, at));
                later(30, () -> decayLeaves(player, at));
            }
            return;
        }

        Material seed = CROP_SEEDS.get(type);
        if (seed != null && block.getBlockData() instanceof Ageable crop && crop.getAge() >= crop.getMaximumAge()
                && skills.active(player, Skill.FARMER_REPLANT)) {
            Ageable young = (Ageable) crop.clone();
            young.setAge(0);
            later(1, () -> replantCrop(player, block, young, seed));
        }
    }

    private void replantSapling(Player player, Block block, Material sapling, Material ground) {
        if (!player.isOnline() || !block.getType().isAir() || block.getRelative(BlockFace.DOWN).getType() != ground) {
            return;
        }
        if (take(player, sapling)) {
            block.setType(sapling);
        }
    }

    private void replantCrop(Player player, Block block, BlockData young, Material seed) {
        if (!player.isOnline() || !block.getType().isAir() || !canSupport(block, young)) {
            return;
        }
        if (take(player, seed)) {
            block.setBlockData(young);
        }
    }

    /** 심을 자리가 아직 그대로인지 (그 사이 밭이 흙이 되거나 원목이 사라졌으면 심지 않음) */
    private static boolean canSupport(Block block, BlockData crop) {
        Material below = block.getRelative(BlockFace.DOWN).getType();
        return switch (crop.getMaterial()) {
            case NETHER_WART -> below == Material.SOUL_SAND;
            case COCOA -> crop instanceof Directional directional
                    && Tag.JUNGLE_LOGS.isTagged(block.getRelative(directional.getFacing()).getType());
            default -> below == Material.FARMLAND;
        };
    }

    /** 인벤토리에서 이름 없는 기본 아이템 1개를 씀. 없으면 false (복사 없음) */
    private static boolean take(Player player, Material material) {
        ItemStack one = new ItemStack(material);
        if (!player.getInventory().containsAtLeast(one, 1)) {
            return false;
        }
        return player.getInventory().removeItem(one).isEmpty();
    }

    /** 원목에서 떨어져(거리 7) 원래 사라질 자연 나뭇잎만 틱마다 조금씩 정리. 장식용(persistent) 잎과 옆 나무의 잎은 건드리지 않음 */
    private void decayLeaves(Player player, Location center) {
        if (center.getWorld() == null) {
            return;
        }
        int r = skills.settings().leafRadius();
        List<Block> queue = new ArrayList<>();
        Block origin = center.getBlock();
        for (int dx = -r; dx <= r && queue.size() < LEAF_SCAN_LIMIT; dx++) {
            for (int dy = -r; dy <= r && queue.size() < LEAF_SCAN_LIMIT; dy++) {
                for (int dz = -r; dz <= r && queue.size() < LEAF_SCAN_LIMIT; dz++) {
                    Block b = origin.getRelative(dx, dy, dz);
                    if (isDecaying(b)) {
                        queue.add(b);
                    }
                }
            }
        }
        if (queue.isEmpty()) {
            return;
        }
        Iterator<Block> it = queue.iterator();
        skills.plugin().getServer().getScheduler().runTaskTimer(skills.plugin(), task -> {
            for (int i = 0; i < LEAVES_PER_TICK && it.hasNext(); i++) {
                decayOne(player, it.next());
            }
            if (!it.hasNext()) {
                task.cancel();
            }
        }, 1L, 1L);
    }

    private static boolean isDecaying(Block block) {
        return block.getBlockData() instanceof Leaves leaves && !leaves.isPersistent()
                && leaves.getDistance() >= LEAF_MAX_DISTANCE;
    }

    private void decayOne(Player player, Block block) {
        if (!isDecaying(block)) {
            return;
        }
        // 바닐라 자연 소멸과 같은 이벤트 (CoreProtect 기록·다른 플러그인 취소 존중)
        LeavesDecayEvent decay = new LeavesDecayEvent(block);
        if (!decay.callEvent()) {
            return;
        }
        boolean magnet = player.isOnline() && skills.active(player, Skill.LUMBERJACK_MAGNET)
                && player.getWorld().equals(block.getWorld())
                && player.getLocation().distanceSquared(block.getLocation()) <= 32 * 32;
        if (!magnet) {
            block.breakNaturally();
            return;
        }
        Collection<ItemStack> drops = block.getDrops();
        block.setType(Material.AIR);
        for (ItemStack drop : drops) {
            ItemStack left = give(player, drop);
            if (left != null) {
                block.getWorld().dropItemNaturally(block.getLocation(), left);
            }
        }
    }

    // ------------------------------------------------------------------ 농부

    @EventHandler(ignoreCancelled = true)
    public void onTrample(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() == Action.PHYSICAL && block != null && block.getType() == Material.FARMLAND
                && skills.active(event.getPlayer(), Skill.FARMER_TRAMPLE)) {
            event.setCancelled(true);
        }
    }

    /** 괭이 우클릭: 다 자란 작물만 실제 채집처럼 부숨(player.breakBlock → VJobs 보상·재파종·자석 그대로). 웅크리기는 3×3(2차)용으로 비워 둠 */
    @EventHandler(ignoreCancelled = true)
    public void onHoeHarvest(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        Player player = event.getPlayer();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND || block == null
                || player.isSneaking() || !Tag.ITEMS_HOES.isTagged(player.getInventory().getItemInMainHand().getType())
                || !CROP_SEEDS.containsKey(block.getType())
                || !(block.getBlockData() instanceof Ageable crop) || crop.getAge() < crop.getMaximumAge()
                || !skills.active(player, Skill.FARMER_HOE_HARVEST)) {
            return;
        }
        event.setCancelled(true);
        player.breakBlock(block);
    }

    // ------------------------------------------------------------------ 사냥꾼

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        Player killer = entity.getKiller();
        if (killer == null || !(entity instanceof Enemy) || entity instanceof Player || entity.fromMobSpawner()
                || !skills.active(killer, Skill.HUNTER_MAGNET)) {
            return;
        }
        Iterator<ItemStack> it = event.getDrops().iterator();
        List<ItemStack> leftovers = new ArrayList<>();
        while (it.hasNext()) {
            ItemStack left = give(killer, it.next());
            it.remove();
            if (left != null) {
                leftovers.add(left);
            }
        }
        event.getDrops().addAll(leftovers);
        int xp = event.getDroppedExp();
        if (xp > 0) {
            event.setDroppedExp(0);
            killer.giveExp(xp, true); // 수선 인챈트도 구슬을 주운 것처럼 적용
        }
    }

    @EventHandler
    public void onDetect(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Action action = event.getAction();
        if ((action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) || event.getHand() != EquipmentSlot.HAND
                || !player.isSneaking() || !Tag.ITEMS_SWORDS.isTagged(player.getInventory().getItemInMainHand().getType())
                || !skills.active(player, Skill.HUNTER_DETECT)) {
            return;
        }
        SkillSettings settings = skills.settings();
        if (!cooldowns.tryUse(player.getUniqueId(), Skill.HUNTER_DETECT.id(), Duration.ofSeconds(settings.detectCooldownSeconds()))) {
            long left = cooldowns.remaining(player.getUniqueId(), Skill.HUNTER_DETECT.id())
                    .map(d -> (d.toMillis() + 999) / 1000).orElse(0L);
            player.sendActionBar(Items.text("<#AAAAAA>사냥감 탐지 대기 " + left + "초"));
            return;
        }
        int r = settings.detectRadius();
        int count = 0;
        for (Entity nearby : player.getNearbyEntities(r, r, r)) {
            if (nearby instanceof Enemy && nearby instanceof LivingEntity living && !(nearby instanceof Player)
                    && nearby.getLocation().distanceSquared(player.getLocation()) <= (double) r * r) {
                living.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, settings.glowSeconds() * 20, 0, false, false));
                count++;
            }
        }
        player.sendActionBar(Items.text(count == 0
                ? "<#AAAAAA>반경 " + r + "블록 안에 적대 몹이 없습니다."
                : "<#55FF55>적대 몹 " + count + "마리 발견 (" + settings.glowSeconds() + "초 발광)"));
    }

    // ------------------------------------------------------------------ 낚시꾼

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        Player player = event.getPlayer();
        if (event.getState() == PlayerFishEvent.State.BITE && skills.active(player, Skill.FISHERMAN_ALERT)) {
            player.sendActionBar(Items.text("<#55FFFF><bold>입질!</bold> <white>지금 당기세요"));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.6f);
            return;
        }
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item item)) {
            return;
        }
        boolean filter = skills.active(player, Skill.FISHERMAN_TRASH_FILTER);
        boolean magnet = skills.active(player, Skill.FISHERMAN_MAGNET);
        if (!filter && !magnet) {
            return;
        }
        // VJobs 보상·Market 기록이 이 이벤트를 다 읽은 뒤에 처리
        later(1, () -> {
            if (!item.isValid() || !player.isOnline()) {
                return;
            }
            ItemStack stack = item.getItemStack();
            if (filter && skills.settings().trash().contains(stack.getType())) {
                item.remove();
                player.sendActionBar(Items.text("<#AAAAAA>쓰레기 자동 폐기: " + stack.getType().name().toLowerCase(java.util.Locale.ROOT)));
                return;
            }
            if (magnet) {
                ItemStack left = give(player, stack);
                if (left == null) {
                    item.remove();
                } else {
                    item.setItemStack(left);
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        cooldowns.clear(event.getPlayer().getUniqueId());
    }

    private void later(long ticks, Runnable task) {
        skills.plugin().getServer().getScheduler().runTaskLater(skills.plugin(), task, ticks);
    }
}
