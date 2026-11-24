package kr.commata.pets.track;

import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import kr.commata.pets.logic.PetIndex;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 길들인 펫의 마지막 위치(청크)를 기억합니다. plugins/Pets/pets.yml 에 저장되어 재시작 후에도 유지됩니다.
 * <ul>
 *   <li>청크의 엔티티가 로딩될 때: 그 안의 길들인 펫을 자동 등록 (플러그인 설치 전에 길들인 펫 포함)</li>
 *   <li>청크가 언로드될 때: 그 순간 위치를 기록 → 나중에 그 청크만 불러오면 됨</li>
 *   <li>길들였을 때 등록, 죽었을 때 삭제</li>
 * </ul>
 */
public final class PetTracker implements Listener {

    public static final Set<EntityType> PET_TYPES = Set.of(
            EntityType.WOLF, EntityType.CAT, EntityType.PARROT,
            EntityType.HORSE, EntityType.DONKEY, EntityType.MULE, EntityType.LLAMA);

    private final JavaPlugin plugin;
    private final File file;
    private final PetIndex index = new PetIndex();
    private boolean dirty;

    public PetTracker(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "pets.yml");
    }

    public PetIndex index() {
        return index;
    }

    /** 소환 대상 펫인지 (종류 + 길들여짐 + 주인 있음). */
    public static boolean isPet(Entity entity) {
        return entity instanceof Tameable t
                && PET_TYPES.contains(entity.getType())
                && t.isTamed()
                && t.getOwnerUniqueId() != null;
    }

    /** 펫의 지금 위치를 기록합니다. 펫이 아니게 됐으면 목록에서 뺍니다. */
    public void track(Entity entity) {
        if (!isPet(entity)) {
            if (index.remove(entity.getUniqueId())) {
                dirty = true;
            }
            return;
        }
        if (entity.isDead()) {
            forget(entity.getUniqueId());
            return;
        }
        Location loc = entity.getLocation();
        var entry = new PetIndex.Entry(((Tameable) entity).getOwnerUniqueId(), loc.getWorld().getUID(),
                loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
        if (index.put(entity.getUniqueId(), entry)) {
            dirty = true;
        }
    }

    public void forget(UUID pet) {
        if (index.remove(pet)) {
            dirty = true;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity e : event.getEntities()) {
            if (isPet(e)) {
                track(e);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (Entity e : event.getEntities()) {
            if (isPet(e)) {
                track(e);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTame(EntityTameEvent event) {
        // 이벤트 시점에는 아직 주인이 설정되기 전일 수 있어서 다음 틱에 기록
        Entity pet = event.getEntity();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (pet.isValid()) {
                track(pet);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        forget(event.getEntity().getUniqueId());
    }

    /** 지금 로딩된 모든 펫의 위치를 기록 (켜질 때, 꺼질 때). */
    public void scanLoaded() {
        for (World world : plugin.getServer().getWorlds()) {
            for (Tameable t : world.getEntitiesByClass(Tameable.class)) {
                if (isPet(t)) {
                    track(t);
                }
            }
        }
    }

    public void load() {
        if (!file.isFile()) {
            return;
        }
        ConfigurationSection pets = YamlConfiguration.loadConfiguration(file).getConfigurationSection("pets");
        if (pets == null) {
            return;
        }
        for (String key : pets.getKeys(false)) {
            ConfigurationSection s = pets.getConfigurationSection(key);
            try {
                index.put(UUID.fromString(key), new PetIndex.Entry(
                        UUID.fromString(s.getString("owner")),
                        UUID.fromString(s.getString("world")),
                        s.getInt("x"), s.getInt("z")));
            } catch (RuntimeException e) {
                plugin.getLogger().warning("pets.yml 의 잘못된 항목을 건너뜁니다: " + key);
            }
        }
    }

    public void saveIfDirty() {
        if (dirty) {
            save();
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        index.all().forEach((pet, e) -> {
            String p = "pets." + pet + ".";
            yml.set(p + "owner", e.owner().toString());
            yml.set(p + "world", e.world().toString());
            yml.set(p + "x", e.chunkX());
            yml.set(p + "z", e.chunkZ());
        });
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().warning("pets.yml 저장 실패: " + e.getMessage());
        }
    }
}
