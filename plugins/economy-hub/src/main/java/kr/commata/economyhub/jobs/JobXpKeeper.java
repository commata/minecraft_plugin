package kr.commata.economyhub.jobs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 직업 탈퇴 후 재가입해도 현재 레벨에서 쌓던 XP 를 유지 (ECONOMY.md 5.1, 7.2).
 *
 * <p>VJobs 2.2.0 의 탈퇴는 {@code JobProgress(새 레벨, 0.0, 프레스티지)} 로 보관해서 XP 가 사라집니다(JAR 확인).
 * <ol>
 *   <li>JobLeaveEvent(HIGHEST, VJobs 가 데이터를 바꾸기 전): 레벨·XP 를 {@link JobXpStore} 파일에 쓰고 fsync.
 *       쓰기에 실패하면 탈퇴를 취소.</li>
 *   <li>JobJoinEvent 다음 틱, 그리고 접속 중인 플레이어를 5초마다: 같은 레벨이면 모자란 XP 만 {@code giveXp}.
 *       기록은 지우지 않고 "복구함(:r)" 으로만 바꿈.</li>
 *   <li>1분마다 비동기로 VJobs DB 를 읽어, 같은 레벨에 그 XP 이상이 저장된 "복구함" 기록만 지움.</li>
 * </ol>
 * 복구 직후 VJobs 가 DB 에 저장하기 전에 서버가 꺼지면 기록이 남아 있어 재접속 때 모자란 만큼 다시 줍니다.
 * 항상 "모자란 만큼"만 주므로 같은 XP 가 두 번 들어가지 않습니다.
 * VJobs 탈퇴 벌칙(level-loss-percent)이 0 이 아니거나 keep-progress 가 꺼져 있으면 저장·복구 모두 하지 않고,
 * 그 상태로 재가입하면 남아 있던 기록을 버립니다.
 */
public final class JobXpKeeper {

    private static final long RESTORE_TICKS = 20L * 5;
    private static final long CONFIRM_TICKS = 20L * 60;
    private static final String LEAVE_SETTINGS_UNAVAILABLE = "VJobs 탈퇴 설정을 읽지 못함";

    private final JavaPlugin plugin;
    private final VJobsHook hook;
    private final JobXpStore store;
    private final String storeProblem;
    private boolean confirming;

    private JobXpKeeper(JavaPlugin plugin, VJobsHook hook, JobXpStore store, String storeProblem) {
        this.plugin = plugin;
        this.hook = hook;
        this.store = store;
        this.storeProblem = storeProblem;
    }

    /** 기록 파일을 읽음. 읽지 못하면 파일을 덮어쓰지 않도록 기능을 끈 채로 만듦. */
    public static JobXpKeeper create(JavaPlugin plugin, VJobsHook hook) {
        String problem = hook.storedProgressProblem();
        JobXpStore store = null;
        if (problem == null) {
            try {
                store = JobXpStore.open(plugin.getDataFolder().toPath().resolve("job-xp.tsv"));
            } catch (IOException e) {
                problem = "job-xp.tsv 를 읽지 못함 (" + e.getMessage() + ")";
                plugin.getLogger().log(Level.SEVERE, "직업 XP 복구 기록을 읽지 못해 탈퇴 후 XP 유지를 끕니다. 파일은 그대로 둡니다.", e);
            }
        }
        return new JobXpKeeper(plugin, hook, store, problem);
    }

    /** 복구 확인 작업 시작. 이벤트 연결이 끝난 뒤 부름. */
    public void start() {
        if (store == null) {
            return;
        }
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::restoreOnline, RESTORE_TICKS, RESTORE_TICKS);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::confirmStored, CONFIRM_TICKS, CONFIRM_TICKS);
    }

    /** 꺼진 이유. 켜져 있으면 null. 매번 VJobs 가 실제로 쓰는 설정을 읽음. */
    public String offReason() {
        if (store == null) {
            return storeProblem;
        }
        VJobsHook.LeaveSettings settings = hook.leaveSettings();
        return settings == null ? LEAVE_SETTINGS_UNAVAILABLE : policyOffReason(settings);
    }

    private static String policyOffReason(VJobsHook.LeaveSettings settings) {
        if (settings.levelLossPercent() != 0.0) {
            return "VJobs 탈퇴 벌칙 " + String.format(Locale.ROOT, "%.1f", settings.levelLossPercent()) + "%";
        }
        if (!settings.keepProgress()) {
            return "VJobs keep-progress 꺼짐";
        }
        return null;
    }

    /** 시작 로그·status 용: "켜짐 (복구 대기 n건)" 또는 "꺼짐 (이유)". */
    public String state() {
        String reason = offReason();
        if (reason != null) {
            return "꺼짐 (" + reason + ")";
        }
        return "켜짐 (기록 " + store.size() + "건)";
    }

    /** JobLeaveEvent(HIGHEST, 취소되지 않은 것). 아직 VJobs 데이터가 바뀌기 전. */
    public void onLeave(Player player, String jobId, Event event) {
        VJobsHook.LeaveSettings settings = hook.leaveSettings();
        if (settings == null) {
            cancelLeave(player, event, LEAVE_SETTINGS_UNAVAILABLE, null);
            return;
        }
        UUID uuid = player.getUniqueId();
        String off = policyOffReason(settings);
        if (off != null) {
            // 벌칙이 켜진 상태의 탈퇴는 VJobs 규칙대로. 예전 기록이 남아 나중에 복구되지 않도록 지움
            if (store != null && jobId != null) {
                dropQuietly(uuid, jobId, off);
            }
            return;
        }
        if (store == null) {
            cancelLeave(player, event, storeProblem, null);
            return;
        }
        VJobsHook.Progress now = jobId == null ? null : hook.progress(uuid, jobId);
        if (now == null) {
            cancelLeave(player, event, "VJobs 직업 레벨과 XP를 읽지 못함", null);
            return;
        }
        SavedJobXp saved = SavedJobXp.forLeave(now.level(), now.xp(), store.get(uuid, jobId));
        try {
            if (saved == null) {
                store.remove(uuid, jobId);
            } else {
                store.put(uuid, jobId, saved);
            }
        } catch (IOException e) {
            cancelLeave(player, event, jobId + " XP 기록 저장 실패", e);
        }
    }

    private void cancelLeave(Player player, Event event, String reason, IOException cause) {
        plugin.getLogger().log(Level.SEVERE, player.getName() + " 직업 탈퇴 취소: " + reason, cause);
        if (event instanceof Cancellable cancellable) {
            cancellable.setCancelled(true);
            player.sendMessage(Component.text("직업 경험치를 저장하지 못해 탈퇴를 취소했습니다. 관리자에게 알려 주세요.",
                    NamedTextColor.RED));
        }
    }

    /** JobJoinEvent(MONITOR, 취소되지 않은 것). 가입 처리가 끝난 다음 틱에 복구. */
    public void onJoin(Player player, String jobId) {
        if (jobId == null || store == null) {
            return;
        }
        VJobsHook.LeaveSettings settings = hook.leaveSettings();
        if (settings == null) {
            return;
        }
        String off = policyOffReason(settings);
        if (off != null) {
            // 복구 직전 설정 확인: 벌칙이 켜진 상태로 재가입했으면 저장해 둔 XP 를 돌려주지 않음
            dropQuietly(player.getUniqueId(), jobId, off);
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> restore(player, JobXpStore.normalize(jobId)));
    }

    /** 접속 중인 플레이어의 기록을 모두 확인. 재기동 뒤 VJobs 가 DB 에서 다시 읽은 값이 모자라면 여기서 다시 채움. */
    private void restoreOnline() {
        if (store.isEmpty() || offReason() != null) {
            return;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            for (String job : store.jobs(player.getUniqueId()).keySet()) {
                restore(player, job);
            }
        }
    }

    private void restore(Player player, String job) {
        if (!player.isOnline() || offReason() != null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        SavedJobXp saved = store.get(uuid, job);
        if (saved == null) {
            return;
        }
        VJobsHook.Progress now = hook.progress(uuid, job);
        if (now == null) {
            return; // 가입하지 않았거나 VJobs 가 아직 데이터를 읽는 중. 기록 유지
        }
        if (now.level() < saved.level()) {
            dropQuietly(uuid, job, "현재 Lv" + now.level() + " 이 저장된 Lv" + saved.level() + " 보다 낮음");
            return;
        }
        double give = saved.toRestore(now.level(), now.xp());
        if (give > 0) {
            if (!hook.giveXp(player, job, give)) {
                return; // 기록을 그대로 두고 다음 확인 때 다시 시도
            }
            plugin.getLogger().info(player.getName() + " " + job + " Lv" + now.level() + " XP "
                    + String.format(Locale.ROOT, "%.1f", give) + " 복구" + (saved.restored() ? " (VJobs 저장 전 종료 후 다시)" : ""));
        }
        if (!saved.restored()) {
            try {
                store.put(uuid, job, saved.asRestored());
            } catch (IOException e) {
                // "복구 대기" 로 남아도 다음 확인 때 모자란 XP 가 없어 다시 주지 않음
                plugin.getLogger().log(Level.WARNING, player.getName() + " " + job + " XP 기록 상태 저장 실패", e);
            }
        }
    }

    /** "복구함" 기록 중 VJobs DB 에 저장된 것만 지움. DB 는 비동기로 읽고 지우기는 메인 스레드에서. */
    private void confirmStored() {
        if (confirming || store.isEmpty()) {
            return;
        }
        List<JobXpStore.Entry> pending = store.snapshot().stream().filter(e -> e.saved().restored()).toList();
        if (pending.isEmpty()) {
            return;
        }
        confirming = true;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<JobXpStore.Entry> done = new ArrayList<>();
            try {
                for (JobXpStore.Entry entry : pending) {
                    VJobsHook.Progress stored = hook.storedProgress(entry.player(), entry.job());
                    if (stored != null && entry.saved().coveredBy(stored.level(), stored.xp())) {
                        done.add(entry);
                    }
                }
            } finally {
                plugin.getServer().getScheduler().runTask(plugin, () -> finishConfirm(done));
            }
        });
    }

    private void finishConfirm(List<JobXpStore.Entry> done) {
        confirming = false;
        for (JobXpStore.Entry entry : done) {
            try {
                // 확인하는 동안 다시 탈퇴해 기록이 바뀌었으면 남김
                store.removeIf(entry.player(), entry.job(), entry.saved());
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "XP 기록 정리 실패 (다음 확인 때 다시): " + entry.player(), e);
                return;
            }
        }
    }

    private void dropQuietly(UUID uuid, String job, String reason) {
        SavedJobXp saved = store.get(uuid, job);
        if (saved == null) {
            return;
        }
        try {
            store.remove(uuid, job);
            plugin.getLogger().info(uuid + " " + job + " 저장된 XP " + String.format(Locale.ROOT, "%.1f", saved.xp())
                    + " 복구 안 함: " + reason);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "XP 기록 삭제 실패: " + uuid + " " + job, e);
        }
    }

    /** 테스트·status 용. */
    public Map<String, SavedJobXp> records(UUID player) {
        return store == null ? Map.of() : store.jobs(player);
    }
}
