package kr.commata.economyhub;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import kr.commata.economyhub.guard.PayoutGuard;
import kr.commata.economyhub.guard.UntrustedBlocks;
import kr.commata.economyhub.jobs.JobCooldownGuard;
import kr.commata.economyhub.jobs.JobXpKeeper;
import kr.commata.economyhub.jobs.VJobsHook;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventPriority;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 경제 보조 플러그인 (ECONOMY.md 7.2). 1b 에는 VJobs 를 보완하는 직업 보호 기능만 들어 있고,
 * /economy GUI 는 1.5단계에 추가합니다.
 */
public final class EconomyHubPlugin extends JavaPlugin {

    /** 탈퇴 후 쿨타임이 끝난 플레이어에게 면제 권한을 다시 붙이는 주기. 30초. */
    private static final long REFRESH_TICKS = 20L * 30;

    private UntrustedBlocks marks;
    private PayoutGuard payoutGuard;
    private JobCooldownGuard cooldownGuard;
    private final AtomicLong cancelledPayouts = new AtomicLong();
    private boolean leaveHooked;
    private boolean payoutHooked;
    private boolean xpHooked;
    private JobXpKeeper xpKeeper;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Settings settings = Settings.read(getConfig(), this);

        marks = new UntrustedBlocks(this);
        payoutGuard = new PayoutGuard(marks, settings.guardEnabled(), settings.formedBlocks());
        getServer().getPluginManager().registerEvents(payoutGuard, this);

        Plugin vjobs = getServer().getPluginManager().getPlugin("VJobs");
        if (vjobs == null || !vjobs.isEnabled()) {
            getLogger().warning("VJobs 가 없거나 꺼져 있어 직업 보호 기능을 쓰지 않습니다.");
        } else {
            // 쿨타임 길이는 VJobs 설정 한 곳에서만 관리 (/jobs reload 로 바꿔도 매번 다시 읽음)
            cooldownGuard = new JobCooldownGuard(this, vjobsCooldownMs(vjobs));
            hookVJobs(vjobs);
        }

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register("economyhub", "EconomyHub 상태 확인·설정 다시 읽기",
                        List.of("ehub"), new HubCommand(this)));
        getLogger().info("EconomyHub " + getPluginMeta().getVersion() + " 활성화 (탈퇴 후 쿨타임 "
                + (leaveHooked ? cooldownGuard.cooldownMs() / 60_000L + "분" : "꺼짐") + ", 보상 제외 "
                + (payoutHooked && settings.guardEnabled() ? "켜짐" : "꺼짐") + ", 탈퇴 후 XP 유지 "
                + xpState() + ")");
    }

    /** VJobs config.yml 의 job-switch-cooldown.default-seconds. 탈퇴 후 대기 시간은 이 값 하나만 씁니다. */
    static LongSupplier vjobsCooldownMs(Plugin vjobs) {
        return () -> Math.max(0L, vjobs.getConfig().getLong("job-switch-cooldown.default-seconds", 3600L)) * 1000L;
    }

    private void hookVJobs(Plugin vjobs) {
        VJobsHook hook = new VJobsHook(this, vjobs);
        JobXpKeeper keeper = JobXpKeeper.create(this, hook);

        // XP 기록은 HIGHEST: 탈퇴 직전(VJobs 가 아직 데이터를 바꾸기 전)이고, 파일 저장에 실패하면 탈퇴를 취소할 수 있음
        String xpLeaveError = hook.listen(VJobsHook.LEAVE_EVENT, EventPriority.HIGHEST, event -> {
            Player player = VJobsHook.player(event);
            if (player != null) {
                keeper.onLeave(player, hook.jobId(event), event);
            }
        });
        String leaveError = hook.listen(VJobsHook.LEAVE_EVENT, EventPriority.MONITOR, event -> {
            Player player = VJobsHook.player(event);
            if (player != null) {
                cooldownGuard.onJobLeave(player);
            }
        });
        if (leaveError == null) {
            // 탈퇴 시각을 기록할 수 있을 때만 면제 권한을 줌. 못 하면 VJobs 기본 쿨타임(가입마다 1시간)이 그대로 걸림
            leaveHooked = true;
            getServer().getPluginManager().registerEvents(cooldownGuard, this);
            cooldownGuard.refreshAll();
            getServer().getScheduler().runTaskTimer(this, cooldownGuard::refreshAll, REFRESH_TICKS, REFRESH_TICKS);
        } else {
            getLogger().warning("직업 탈퇴 이벤트 연결 실패, 탈퇴 후 쿨타임 기능 꺼짐: " + leaveError);
        }

        String joinError = hook.listen(VJobsHook.JOIN_EVENT, EventPriority.MONITOR, event -> {
            Player player = VJobsHook.player(event);
            if (player != null) {
                keeper.onJoin(player, hook.jobId(event));
            }
        });
        if (joinError == null && xpLeaveError == null) {
            xpHooked = true;
            xpKeeper = keeper;
            keeper.start();
        } else {
            getLogger().warning("직업 가입·탈퇴 이벤트 연결 실패, 탈퇴 후 XP 유지 꺼짐: " + (joinError != null ? joinError : xpLeaveError));
        }

        String payoutError = hook.listen(VJobsHook.PAYOUT_EVENT, EventPriority.HIGH, event -> {
            Player player = VJobsHook.player(event);
            if (player != null && event instanceof Cancellable cancellable
                    && payoutGuard.shouldCancel(player, VJobsHook.actionType(event))) {
                cancellable.setCancelled(true);
                cancelledPayouts.incrementAndGet();
            }
        });
        if (payoutError == null) {
            payoutHooked = true;
        } else {
            getLogger().warning("직업 보상 이벤트 연결 실패, 피스톤·유체 블록 보상 제외 꺼짐: " + payoutError);
        }

    }

    @Override
    public void onDisable() {
        if (cooldownGuard != null) {
            cooldownGuard.shutdown();
        }
    }

    /** /economyhub reload */
    void reload() {
        reloadConfig();
        Settings settings = Settings.read(getConfig(), this);
        payoutGuard.configure(settings.guardEnabled(), settings.formedBlocks());
        if (leaveHooked) {
            cooldownGuard.refreshAll();
        }
    }

    JobCooldownGuard cooldownGuard() {
        return cooldownGuard;
    }

    boolean leaveHooked() {
        return leaveHooked;
    }

    /** 탈퇴 후 XP 유지의 실제 상태. VJobs 설정을 매번 다시 읽음. */
    String xpState() {
        if (xpKeeper == null) {
            return xpHooked ? "꺼짐" : "꺼짐 (VJobs 이벤트 연결 안 됨)";
        }
        return xpKeeper.state();
    }

    JobXpKeeper xpKeeper() {
        return xpKeeper;
    }

    boolean payoutHooked() {
        return payoutHooked;
    }

    long cancelledPayouts() {
        return cancelledPayouts.get();
    }

    record Settings(boolean guardEnabled, Set<Material> formedBlocks) {

        static Settings read(FileConfiguration config, JavaPlugin plugin) {
            if (config.contains("job-cooldown") || config.contains("payout-guard.max-marks-per-chunk")) {
                plugin.getLogger().warning("config.yml 의 job-cooldown, payout-guard.max-marks-per-chunk 는 더 이상 쓰지 않습니다."
                        + " 탈퇴 후 쿨타임은 VJobs config.yml 의 job-switch-cooldown.default-seconds 에서 바꾸세요.");
            }
            boolean enabled = config.getBoolean("payout-guard.enabled", true);
            Set<Material> formed = EnumSet.noneOf(Material.class);
            for (String name : config.getStringList("payout-guard.formed-blocks")) {
                Material material = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
                if (material == null) {
                    plugin.getLogger().warning("payout-guard.formed-blocks: 없는 블록 이름 " + name);
                } else {
                    formed.add(material);
                }
            }
            return new Settings(enabled, formed);
        }
    }
}
