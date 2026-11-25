package kr.commata.economyhub.jobs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 직업 탈퇴 후 재가입해도 현재 레벨에서 쌓던 XP 를 유지 (ECONOMY.md 5.1, 7.2).
 *
 * <p>VJobs 2.2.0 의 탈퇴는 {@code JobProgress(새 레벨, 0.0, 프레스티지)} 로 보관해서 XP 가 사라집니다(JAR 확인).
 * <ol>
 *   <li>JobLeaveEvent(HIGHEST, VJobs 가 데이터를 바꾸기 전): 레벨·XP 를 {@link JobXpStore} 파일에 쓰고 fsync.
 *       쓰기에 실패하면 탈퇴를 취소.</li>
 *   <li>복구는 두 순간에만, 그 순간의 VJobs 값을 기준으로 한 번: 재가입 다음 틱, 그리고 접속 후 VJobs 가 데이터를 다 읽은
 *       첫 틱. 기록의 레벨·XP 까지 모자란 만큼(레벨이 낮으면 그 사이 레벨업분까지) {@code giveXp}. 그 뒤에 새로 얻은 XP 는
 *       계산에 들어가지 않으므로 흡수되지 않음. 기록은 지우지 않고 "복구함(:r)" 으로만 바꿈.</li>
 *   <li>1분마다 비동기로 VJobs DB 를 읽어, 같은 레벨에 그 XP 이상이 저장된 "복구함" 기록만 지움.</li>
 * </ol>
 * 복구 직후 VJobs 가 DB 에 저장하기 전에 서버가 꺼지면 기록이 남아 있어 재접속 때 DB 값 기준으로 모자란 만큼 다시 줍니다.
 * DB 에 이미 반영돼 있으면 모자란 게 없어 다시 주지 않습니다. 레벨이 기록보다 낮아도 기록을 지우지 않습니다.
 * VJobs 탈퇴 벌칙(level-loss-percent)이 0 이 아니거나 keep-progress 가 꺼져 있으면 저장·복구 모두 하지 않고,
 * 그 상태로 재가입하면 남아 있던 기록을 버립니다.
 *
 * <p>꺼지는 이유는 세 가지로 나눔:
 * <ul>
 *   <li>벌칙: VJobs 규칙대로 탈퇴 허용, 기록 버림</li>
 *   <li>VJobs 설정을 읽지 못함: 기록은 저장하되 복구·삭제는 하지 않음</li>
 *   <li>기록 파일 장애(job-xp.tsv 를 읽지 못함): XP 유지가 정책상 꺼진 경우를 제외하고 탈퇴를 취소</li>
 * </ul>
 */
public final class JobXpKeeper implements Listener {

    /** 접속 후 VJobs 가 데이터를 비동기로 읽는 동안 기다리는 최대 틱 (30초). */
    private static final int LOAD_WAIT_TICKS = 20 * 30;
    private static final long CONFIRM_TICKS = 20L * 60;

    private final JavaPlugin plugin;
    private final VJobsHook hook;
    private final JobXpStore store;
    private final String storeProblem;
    private final String confirmProblem;
    private boolean confirming;

    /** 재가입 때 계산 기준을 잡아 두고 아직 지급하지 않은 복구. 다음 틱·같은 틱 탈퇴·퇴장 중 먼저 오는 쪽에서 한 번 실행. */
    private record Pending(VJobsHook.Progress base, SavedJobXp saved) { }

    private final Map<UUID, Map<String, Pending>> pending = new HashMap<>();

    private JobXpKeeper(JavaPlugin plugin, VJobsHook hook, JobXpStore store, String storeProblem, String confirmProblem) {
        this.plugin = plugin;
        this.hook = hook;
        this.store = store;
        this.storeProblem = storeProblem;
        this.confirmProblem = confirmProblem;
    }

    /** 기록 파일을 읽음. 읽지 못하면 파일을 보존하고 XP 유지가 적용되는 탈퇴를 막음. */
    public static JobXpKeeper create(JavaPlugin plugin, VJobsHook hook) {
        JobXpStore store = null;
        String storeProblem = null;
        try {
            store = JobXpStore.open(plugin.getDataFolder().toPath().resolve("job-xp.tsv"));
        } catch (IOException e) {
            storeProblem = "job-xp.tsv 를 읽지 못함";
            plugin.getLogger().log(Level.SEVERE, "직업 XP 복구 기록 파일을 읽지 못했습니다. 파일은 그대로 두고, 고칠 때까지"
                    + " XP 유지가 적용되는 직업의 탈퇴를 막습니다.", e);
        }
        // VJobs DB 를 못 읽으면 기록을 지우지 못할 뿐 복구는 "모자란 만큼"이라 그대로 안전. 기록만 쌓임
        String confirmProblem = hook.storedProgressProblem();
        if (confirmProblem != null) {
            plugin.getLogger().warning("XP 기록 정리 꺼짐 (복구는 동작): " + confirmProblem);
        }
        return new JobXpKeeper(plugin, hook, store, storeProblem, confirmProblem);
    }

    /** 복구 확인 작업 시작. 이벤트 연결이 끝난 뒤 부름. */
    public void start() {
        if (store == null) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        // /reload 등으로 이미 접속해 있는 플레이어: VJobs 데이터가 있으면 지금 값 기준으로 확인
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            afterVJobsLoad(player);
        }
        if (confirmProblem == null) {
            plugin.getServer().getScheduler().runTaskTimer(plugin, this::confirmStored, CONFIRM_TICKS, CONFIRM_TICKS);
        }
    }

    /** VJobs 탈퇴 설정 기준 상태. */
    private enum Rule { KEEP, PENALTY, UNKNOWN }

    private Rule rule() {
        VJobsHook.LeaveSettings settings = hook.leaveSettings();
        if (settings == null) {
            return Rule.UNKNOWN;
        }
        return settings.levelLossPercent() == 0.0 && settings.keepProgress() ? Rule.KEEP : Rule.PENALTY;
    }

    private String penaltyReason() {
        VJobsHook.LeaveSettings settings = hook.leaveSettings();
        if (settings == null) {
            return "VJobs 탈퇴 설정을 읽지 못함";
        }
        if (settings.levelLossPercent() != 0.0) {
            return "VJobs 탈퇴 벌칙 " + String.format(Locale.ROOT, "%.1f", settings.levelLossPercent()) + "%";
        }
        return "VJobs keep-progress 꺼짐";
    }

    /** 복구해도 되는지. 기록 파일이 정상이고 VJobs 설정이 "벌칙 0% + 진행도 보관"일 때만. */
    private boolean canRestore() {
        return store != null && rule() == Rule.KEEP;
    }

    /** 시작 로그·status 용: "켜짐 (기록 n건)" 또는 "꺼짐 (이유)". 매번 VJobs 가 실제로 쓰는 설정을 읽음. */
    public String state() {
        if (store == null) {
            return "꺼짐 (" + storeProblem + ")";
        }
        Rule rule = rule();
        if (rule == Rule.PENALTY) {
            return "꺼짐 (" + penaltyReason() + ")";
        }
        if (rule == Rule.UNKNOWN) {
            return "꺼짐 (VJobs 탈퇴 설정을 읽지 못함, 기록만 저장)";
        }
        return "켜짐 (기록 " + store.size() + "건" + (confirmProblem == null ? "" : ", 기록 정리 꺼짐") + ")";
    }

    /** JobLeaveEvent(HIGHEST, 취소되지 않은 것). 아직 VJobs 데이터가 바뀌기 전. */
    public void onLeave(Player player, String jobId, Event event) {
        UUID uuid = player.getUniqueId();
        if (rule() == Rule.PENALTY) {
            // 벌칙이 켜진 상태의 탈퇴는 VJobs 규칙대로. 예전 기록이 남아 나중에 복구되지 않도록 지움
            if (store != null && jobId != null) {
                dropQuietly(uuid, jobId, penaltyReason());
            }
            return;
        }
        if (store == null) {
            // 현재 XP가 0이어도 손상된 파일에 복구 대기 XP가 있는지 확인할 수 없음
            cancelLeave(player, jobId, event, storeProblem, null);
            return;
        }
        if (jobId != null) {
            // 재가입한 틱에 바로 탈퇴: 예정된 복구를 먼저 VJobs 에 반영해야 그 틱에 얻은 XP 와 함께 기록됨
            runPending(player, JobXpStore.normalize(jobId));
        }
        VJobsHook.Progress now = jobId == null ? null : hook.progress(uuid, jobId);
        if (now == null) {
            cancelLeave(player, jobId, event, "VJobs 직업 레벨과 XP를 읽지 못함", null);
            return;
        }
        // 같은 레벨의 기존 기록이 더 크면 유지 (재접속 직후 복구 전에 다시 탈퇴하는 경우)
        SavedJobXp next = SavedJobXp.onLeave(store.get(uuid, jobId), now.level(), now.xp());
        try {
            if (next != null) {
                store.put(uuid, jobId, next);
            } else {
                store.remove(uuid, jobId);
            }
        } catch (IOException e) {
            cancelLeave(player, jobId, event, "XP 기록 저장 실패", e);
        }
    }

    private void cancelLeave(Player player, String jobId, Event event, String why, Exception cause) {
        String job = jobId == null ? "직업" : jobId;
        plugin.getLogger().log(Level.SEVERE, player.getName() + " " + job + " 탈퇴 취소: " + why, cause);
        if (event instanceof Cancellable cancellable) {
            cancellable.setCancelled(true);
            player.sendMessage(Component.text("직업 경험치를 저장할 수 없어 탈퇴를 취소했습니다. 관리자에게 알려 주세요.",
                    NamedTextColor.RED));
        }
    }

    /**
     * JobJoinEvent(MONITOR, 취소되지 않은 것). VJobs 는 이 이벤트 직후 같은 호출 안에서 보관 진행도(없으면 Lv1·XP 0)로
     * 가입시킵니다(JobManager.join, JAR 확인). 그래서 복구량을 지금 그 보관 값 기준으로 계산해 두고, 다음 틱에는 계산해 둔
     * 양만 더합니다. 가입한 틱에 새로 얻은 XP 는 계산에 들어가지 않아 흡수되지 않음.
     */
    public void onJoin(Player player, String jobId) {
        if (jobId == null || store == null) {
            return;
        }
        Rule rule = rule();
        if (rule == Rule.PENALTY) {
            // 복구 직전 설정 확인: 벌칙이 켜진 상태로 재가입했으면 저장해 둔 XP 를 돌려주지 않음
            dropQuietly(player.getUniqueId(), jobId, penaltyReason());
            return;
        }
        if (rule == Rule.UNKNOWN) {
            return; // 설정을 알 수 없으면 복구도 삭제도 하지 않고 기록 유지
        }
        String job = JobXpStore.normalize(jobId);
        SavedJobXp saved = store.get(player.getUniqueId(), job);
        if (saved == null) {
            return;
        }
        VJobsHook.Progress base = hook.joinBase(player.getUniqueId(), job); // 읽지 못하면 null → 다음 틱 값 기준
        pending.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>()).put(job, new Pending(base, saved));
        plugin.getServer().getScheduler().runTask(plugin, () -> runPending(player, job));
    }

    /** 예정된 재가입 복구가 남아 있으면 지금 실행. 다음 틱 작업, 같은 틱 탈퇴, 퇴장 중 먼저 부른 쪽에서 한 번만. */
    private void runPending(Player player, String job) {
        Map<String, Pending> jobs = pending.get(player.getUniqueId());
        Pending p = jobs == null ? null : jobs.remove(job);
        if (jobs != null && jobs.isEmpty()) {
            pending.remove(player.getUniqueId());
        }
        if (p != null) {
            restore(player, job, p.base(), p.saved());
        }
    }

    /** 재가입한 틱에 나가면 다음 틱 작업이 못 도니, VJobs 가 퇴장 저장을 하기 전에(LOWEST) 예정된 복구를 반영. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Map<String, Pending> jobs = pending.get(event.getPlayer().getUniqueId());
        if (jobs != null) {
            for (String job : List.copyOf(jobs.keySet())) {
                runPending(event.getPlayer(), job);
            }
        }
    }

    /** 접속: VJobs 가 DB 에서 데이터를 다 읽은 첫 틱에 기록을 확인. 강제 종료로 VJobs DB 가 예전 값이면 여기서 다시 채움. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        afterVJobsLoad(event.getPlayer());
    }

    private void afterVJobsLoad(Player player) {
        if (store == null || store.jobs(player.getUniqueId()).isEmpty()) {
            return;
        }
        int[] waited = {0};
        plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
            if (!player.isOnline() || ++waited[0] > LOAD_WAIT_TICKS) {
                task.cancel();
                return;
            }
            if (!hook.loaded(player.getUniqueId())) {
                return;
            }
            task.cancel();
            for (String job : store.jobs(player.getUniqueId()).keySet()) {
                restore(player, job, null, null);
            }
        }, 0L, 1L);
    }

    /**
     * @param base     복구량 계산 기준 (재가입 때 VJobs 보관 값). null 이면 지금 VJobs 값 (접속 후 데이터를 다 읽은 첫 틱)
     * @param expected 재가입 때 본 기록. 그 사이 기록이 바뀌었으면(같은 틱에 다시 탈퇴 등) 이번 복구는 하지 않음
     */
    private void restore(Player player, String job, VJobsHook.Progress base, SavedJobXp expected) {
        if (!player.isOnline() || !canRestore()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        SavedJobXp saved = store.get(uuid, job);
        if (saved == null || (expected != null && !saved.equals(expected))) {
            return;
        }
        VJobsHook.Progress current = hook.progress(uuid, job);
        if (current == null) {
            return; // 가입하지 않았거나 VJobs 가 아직 데이터를 읽는 중. 기록 유지, 다음 재가입·접속 때 다시
        }
        // 기준 순간의 값으로 한 번만 계산 (그 뒤 얻은 XP 는 계산에 넣지 않음)
        VJobsHook.Progress now = base != null ? base : current;
        double give = saved.missingXp(now.level(), now.xp(), hook::xpForLevel);
        if (Double.isNaN(give)) {
            plugin.getLogger().warning(player.getName() + " " + job + " 레벨별 필요 XP 를 읽지 못해 복구 보류 (기록 유지)");
            return;
        }
        if (give > SavedJobXp.EPSILON) {
            if (!hook.giveXp(player, job, give)) {
                return; // 기록을 그대로 두고 다음 재가입·접속 때 다시
            }
            plugin.getLogger().info(player.getName() + " " + job + " Lv" + saved.level() + " XP "
                    + String.format(Locale.ROOT, "%.1f", give) + " 복구"
                    + (now.level() < saved.level() ? " (Lv" + now.level() + " 에서)" : "")
                    + (saved.restored() ? " (VJobs 저장 전 종료 후 다시)" : ""));
        }
        if (!saved.restored()) {
            try {
                store.put(uuid, job, saved.asRestored());
            } catch (IOException e) {
                // "복구 대기" 로 남아도 다음 확인 때 VJobs 값이 이미 기록 이상이라 다시 주지 않음
                plugin.getLogger().log(Level.WARNING, player.getName() + " " + job + " XP 기록 상태 저장 실패", e);
            }
        }
    }

    /** "복구함" 기록 중 VJobs DB 에 저장된 것만 지움. DB 는 비동기로 읽고 지우기는 메인 스레드에서. */
    private void confirmStored() {
        if (confirming || store == null || store.isEmpty()) {
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
