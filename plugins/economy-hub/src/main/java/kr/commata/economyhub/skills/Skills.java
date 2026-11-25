package kr.commata.economyhub.skills;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import kr.commata.economyhub.jobs.VJobsHook;
import kr.commata.economyhub.jobs.VJobsHook.Progress;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * 플레이어가 지금 스킬을 쓸 수 있는지 판단 (ECONOMY.md 15.2): 서버에서 켜 둔 스킬 + 그 직업에 가입 중 + 해금 레벨 이상 + 본인이
 * 켜 둠 + 서바이벌·모험 모드. 가입 직업·레벨은 VJobs API 를 1초 동안만 캐시하고, 가입·탈퇴·레벨업 이벤트 때 바로 버립니다.
 */
public final class Skills {

    private static final long CACHE_MS = 1_000;

    private record Cached(long atMs, Map<String, Progress> jobs) {
    }

    private final Plugin plugin;
    private final VJobsHook vjobs;
    private volatile SkillSettings settings;
    private final Map<UUID, Cached> cache = new HashMap<>();
    private final Map<Skill, NamespacedKey> keys = new HashMap<>();

    public Skills(Plugin plugin, VJobsHook vjobs, SkillSettings settings) {
        this.plugin = plugin;
        this.vjobs = vjobs;
        this.settings = settings;
        for (Skill skill : Skill.values()) {
            keys.put(skill, new NamespacedKey(plugin, "skill_toggle." + skill.id()));
        }
    }

    public SkillSettings settings() {
        return settings;
    }

    public void reload(SkillSettings settings) {
        this.settings = settings;
    }

    public Plugin plugin() {
        return plugin;
    }

    /** 지금 이 스킬이 동작해야 하면 true */
    public boolean active(Player player, Skill skill) {
        GameMode mode = player.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) {
            return false;
        }
        return settings.available(skill) && unlocked(player, skill) && isOn(player, skill);
    }

    /** 그 직업에 가입 중이고 해금 레벨 이상 */
    public boolean unlocked(Player player, Skill skill) {
        Integer level = level(player, skill.job());
        return level != null && level >= settings.unlock(skill);
    }

    /** 가입 중인 직업의 레벨, 가입하지 않았거나 VJobs 가 아직 읽지 않았으면 null */
    public Integer level(Player player, String job) {
        Map<String, Progress> jobs = jobs(player);
        Progress progress = jobs == null ? null : jobs.get(job);
        return progress == null ? null : progress.level();
    }

    /** 가입 중인 직업 (VJobs 가 아직 읽지 않았으면 null) */
    public Map<String, Progress> jobs(Player player) {
        long now = System.currentTimeMillis();
        Cached cached = cache.get(player.getUniqueId());
        if (cached != null && now - cached.atMs() < CACHE_MS && now >= cached.atMs()) {
            return cached.jobs();
        }
        Map<String, Progress> jobs = vjobs.joinedJobs(player.getUniqueId());
        if (jobs != null) {
            cache.put(player.getUniqueId(), new Cached(now, jobs));
        }
        return jobs;
    }

    public boolean isOn(Player player, Skill skill) {
        Byte value = player.getPersistentDataContainer().get(keys.get(skill), PersistentDataType.BYTE);
        return value == null ? skill.defaultOn() : value != 0;
    }

    public void setOn(Player player, Skill skill, boolean on) {
        player.getPersistentDataContainer().set(keys.get(skill), PersistentDataType.BYTE, (byte) (on ? 1 : 0));
    }

    /** 가입·탈퇴·레벨업 직후: 지금과 다음 틱(VJobs 가 이벤트 뒤에 데이터를 바꿈) 모두 캐시를 버림 */
    public void invalidate(UUID uuid) {
        cache.remove(uuid);
        plugin.getServer().getScheduler().runTask(plugin, () -> cache.remove(uuid));
    }

    public void forget(UUID uuid) {
        cache.remove(uuid);
    }
}
