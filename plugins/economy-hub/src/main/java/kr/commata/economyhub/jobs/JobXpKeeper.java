package kr.commata.economyhub.jobs;

import java.util.Locale;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * 직업 탈퇴 후 재가입해도 현재 레벨에서 쌓던 XP 를 유지 (ECONOMY.md 5.1, 2026-11-25 결정).
 *
 * <p>VJobs 2.2.0 의 탈퇴는 {@code JobProgress(새 레벨, 0.0, 프레스티지)} 로 보관해서 XP 가 사라집니다(JAR 확인).
 * JobLeaveEvent(MONITOR, 탈퇴 직전)에 레벨·XP 를 플레이어 PDC {@code economyhub:job_xp_<직업>} 에 저장하고,
 * JobJoinEvent 다음 틱에 같은 레벨이면 모자란 XP 를 VJobs API {@code giveXp} 로 돌려줍니다.
 * 탈퇴 벌칙(level-loss-percent)이 0 이 아니면 레벨이 달라지므로 쓰지 않습니다.
 */
public final class JobXpKeeper {

    private final Plugin plugin;
    private final Plugin vjobs;
    private final VJobsHook hook;

    public JobXpKeeper(Plugin plugin, Plugin vjobs, VJobsHook hook) {
        this.plugin = plugin;
        this.vjobs = vjobs;
        this.hook = hook;
    }

    /** VJobs 설정이 "레벨 손실 0% + 진행도 보관"일 때만 동작. 매번 VJobs 설정을 읽음. */
    public boolean enabled() {
        return vjobs.getConfig().getDouble("leave-penalty.level-loss-percent", 0.0) == 0.0
                && vjobs.getConfig().getBoolean("leave-penalty.keep-progress", true);
    }

    /** JobLeaveEvent(MONITOR, 취소되지 않은 것). 아직 VJobs 데이터가 바뀌기 전. */
    public void onLeave(Player player, String jobId) {
        if (jobId == null || !enabled()) {
            return;
        }
        VJobsHook.Progress now = hook.progress(player.getUniqueId(), jobId);
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        if (now == null || now.xp() <= 0) {
            pdc.remove(key(jobId));
            return;
        }
        pdc.set(key(jobId), PersistentDataType.STRING, new SavedJobXp(now.level(), now.xp()).encode());
    }

    /** JobJoinEvent(MONITOR, 취소되지 않은 것). 가입 처리가 끝난 다음 틱에 돌려줌. */
    public void onJoin(Player player, String jobId) {
        if (jobId == null) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> restore(player, jobId));
    }

    private void restore(Player player, String jobId) {
        if (!player.isOnline()) {
            return; // 저장값은 남겨 두고 다음 가입 때 다시 시도
        }
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        NamespacedKey key = key(jobId);
        String raw = pdc.get(key, PersistentDataType.STRING);
        if (raw == null) {
            return;
        }
        VJobsHook.Progress now = hook.progress(player.getUniqueId(), jobId);
        if (now == null) {
            return; // 가입이 실제로는 안 된 경우. 저장값 유지
        }
        SavedJobXp saved = SavedJobXp.decode(raw);
        double give = saved == null ? 0 : saved.toRestore(now.level(), now.xp());
        if (give > 0 && !hook.giveXp(player, jobId, give)) {
            return; // 실패하면 저장값을 남겨 다음 가입 때 다시 시도
        }
        pdc.remove(key);
        if (give > 0) {
            plugin.getLogger().info(player.getName() + " " + jobId + " Lv" + now.level() + " XP " + give + " 복구");
        }
    }

    private NamespacedKey key(String jobId) {
        String safe = jobId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return new NamespacedKey(plugin, "job_xp_" + safe);
    }
}
