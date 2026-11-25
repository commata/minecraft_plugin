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
import kr.commata.economyhub.menu.MenuSettings;
import kr.commata.economyhub.menu.Menus;
import kr.commata.economyhub.skills.CaveVision;
import kr.commata.economyhub.skills.SkillListener;
import kr.commata.economyhub.skills.SkillSettings;
import kr.commata.economyhub.skills.Skills;
import kr.commata.common.gui.MenuListener;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventPriority;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 경제 보조 플러그인 (ECONOMY.md 7.2). 1b 의 직업 보호 기능(VJobs 보완)과 1.5단계 메뉴(/economy, /직업, 7.4)가 들어 있습니다.
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
    private VJobsHook vjobsHook;
    private Menus menus;
    private Skills skills;
    private CaveVision caveVision;

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

        // /직업 은 가입·탈퇴 이벤트를 받을 수 있을 때만 (탈퇴 시각·XP 기록 없이 탈퇴시키지 않음)
        menus = new Menus(this, MenuSettings.read(getConfig(), getLogger()),
                xpHooked && leaveHooked ? vjobsHook : null, leaveHooked ? cooldownGuard : null);
        getServer().getPluginManager().registerEvents(new MenuListener(), this);
        if (vjobsHook != null) {
            enableSkills(vjobsHook);
        }

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register("economyhub", "EconomyHub 상태 확인·설정 다시 읽기",
                    List.of("ehub"), new HubCommand(this));
            event.registrar().register("economy", "서버 경제 메뉴 열기", List.of("경제"),
                    playerOnly(player -> menus.openEconomy(player)));
            event.registrar().register("직업", "직업 가입·탈퇴·쿨타임 메뉴 열기", playerOnly(player -> {
                if (!menus.openJobs(player)) {
                    player.sendMessage(Component.text("직업 메뉴를 지금 쓸 수 없습니다. 관리자에게 문의해 주세요.", NamedTextColor.RED));
                }
            }));
        });
        getLogger().info("EconomyHub " + getPluginMeta().getVersion() + " 활성화 (탈퇴 후 쿨타임 "
                + cooldownState() + ", 보상 제외 " + payoutState() + ", 탈퇴 후 XP 유지 "
                + xpState() + ")");
    }

    /** VJobs config.yml 의 job-switch-cooldown.default-seconds. 탈퇴 후 대기 시간은 이 값 하나만 씁니다. */
    static LongSupplier vjobsCooldownMs(Plugin vjobs) {
        return () -> Math.max(0L, vjobs.getConfig().getLong("job-switch-cooldown.default-seconds", 3600L)) * 1000L;
    }

    /** 직업 레벨 편의 스킬 (ECONOMY.md 15장). 가입·탈퇴·레벨업 때 직업 캐시를 바로 버림 */
    private void enableSkills(VJobsHook hook) {
        skills = new Skills(this, hook, SkillSettings.read(getConfig(), getLogger()));
        caveVision = new CaveVision(skills);
        getServer().getPluginManager().registerEvents(new SkillListener(skills), this);
        getServer().getPluginManager().registerEvents(caveVision, this);
        getServer().getScheduler().runTaskTimer(this, caveVision::tickAll, CaveVision.PERIOD_TICKS, CaveVision.PERIOD_TICKS);
        for (String event : List.of(VJobsHook.JOIN_EVENT, VJobsHook.LEAVE_EVENT, VJobsHook.LEVEL_UP_EVENT)) {
            String error = hook.listen(event, EventPriority.MONITOR, e -> {
                Player player = VJobsHook.player(e);
                if (player != null) {
                    skills.invalidate(player.getUniqueId());
                    // 탈퇴 직후 동굴 시야도 바로 끔 (VJobs 가 이벤트 뒤에 직업을 지우므로 다음 틱)
                    getServer().getScheduler().runTask(this, () -> caveVision.update(player));
                }
            });
            if (error != null) {
                getLogger().warning("편의 스킬: " + error + " (직업 변화가 최대 1초 늦게 반영됨)");
            }
        }
        menus.enableSkills(skills, caveVision);
    }

    /** 플레이어만 쓰는 명령 */
    private static BasicCommand playerOnly(java.util.function.Consumer<Player> action) {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack source, String[] args) {
                if (source.getExecutor() instanceof Player player) {
                    action.accept(player);
                } else {
                    source.getSender().sendMessage(Component.text("게임 안에서만 쓸 수 있습니다.", NamedTextColor.RED));
                }
            }
        };
    }

    private void hookVJobs(Plugin vjobs) {
        VJobsHook hook = new VJobsHook(this, vjobs);
        vjobsHook = hook;
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
        // 리스너가 풀린 뒤 메뉴에서 아이템을 꺼내지 못하게 먼저 닫음
        MenuListener.closeAll(getServer());
        if (caveVision != null) {
            caveVision.shutdown();
        }
        if (cooldownGuard != null) {
            cooldownGuard.shutdown();
        }
    }

    /** /economyhub reload */
    void reload() {
        reloadConfig();
        Settings settings = Settings.read(getConfig(), this);
        payoutGuard.configure(settings.guardEnabled(), settings.formedBlocks());
        menus.reload(MenuSettings.read(getConfig(), getLogger()));
        if (skills != null) {
            skills.reload(SkillSettings.read(getConfig(), getLogger()));
        }
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

    /** 탈퇴 후 쿨타임의 실제 상태. */
    String cooldownState() {
        if (!leaveHooked) {
            return "꺼짐";
        }
        String problem = cooldownGuard.problem();
        return cooldownGuard.cooldownMs() / 60_000L + "분" + (problem == null ? "" : " (" + problem + ")");
    }

    /** 보상 제외의 실제 상태: 이벤트 연결과 config.yml payout-guard.enabled 둘 다. */
    String payoutState() {
        if (!payoutHooked) {
            return "꺼짐 (VJobs 이벤트 연결 안 됨)";
        }
        return payoutGuard.enabled() ? "켜짐" : "꺼짐 (payout-guard.enabled: false)";
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
