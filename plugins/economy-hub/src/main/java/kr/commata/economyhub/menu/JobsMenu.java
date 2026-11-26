package kr.commata.economyhub.menu;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.commata.common.gui.Items;
import kr.commata.common.gui.Menu;
import kr.commata.common.time.Durations;
import kr.commata.economyhub.jobs.JobCooldownGuard;
import kr.commata.economyhub.jobs.VJobsHook;
import kr.commata.economyhub.jobs.VJobsHook.Progress;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.scheduler.BukkitTask;

/**
 * /직업 9×5 (ECONOMY.md 7.4.2). VJobs 공개 API 로 가입(좌클릭)·탈퇴(우클릭 두 번)하고, 상태별로 아이콘을 바꿉니다.
 * 쿨타임 남은 시간은 1b 직업 보호 기능의 탈퇴 시각(job-leaves.tsv 와 PDC 중 늦은 값) + VJobs 쿨타임 설정으로 계산합니다.
 */
final class JobsMenu extends Menu {

    static final int SLOT_HUB = 36;
    static final int SLOT_RULES = 40;
    /** 편의 스킬 설정 (ECONOMY.md 15.5. 40번은 확정 레이아웃의 규칙·쿨타임이라 42번) */
    static final int SLOT_SKILLS = 42;
    static final int SLOT_CLOSE = 44;
    /** 탈퇴는 이 시간 안에 한 번 더 우클릭해야 확정 */
    static final long LEAVE_CONFIRM_MS = 3_000;

    private final Menus menus;
    private final VJobsHook vjobs;
    private final JobCooldownGuard cooldowns;

    private int shownJoined;
    private String pendingLeave;
    private long pendingLeaveUntil;
    /** 가입·탈퇴를 다음 틱에 처리하는 동안 다른 클릭 무시 */
    private boolean busy;
    /** 마지막으로 그릴 때 매초 다시 그릴 것(쿨타임·불러오는 중·탈퇴 확인)이 있었는지 */
    private boolean ticking;
    private BukkitTask timer;

    JobsMenu(Menus menus, Player viewer) {
        this(menus, viewer, countJoined(menus.vjobs(), viewer));
    }

    private JobsMenu(Menus menus, Player viewer, int joined) {
        super(5, title(joined, menus.vjobs().jobLimit()));
        this.menus = menus;
        this.vjobs = menus.vjobs();
        this.cooldowns = menus.cooldowns();
        this.shownJoined = joined;
    }

    static Component title(int joined, int limit) {
        return Items.text("<#F5C542>직업 가입 및 관리 (" + joined + "/" + limit + ")");
    }

    private static int countJoined(VJobsHook vjobs, Player viewer) {
        Map<String, Progress> joined = vjobs.joinedJobs(viewer.getUniqueId());
        return joined == null ? 0 : joined.size();
    }

    @Override
    protected void draw(Player viewer) {
        long now = System.currentTimeMillis();
        if (pendingLeave != null && now >= pendingLeaveUntil) {
            pendingLeave = null;
        }
        Map<String, Progress> joined = vjobs.joinedJobs(viewer.getUniqueId());
        int limit = vjobs.jobLimit();
        long remaining = cooldowns == null ? 0 : cooldowns.remainingMs(viewer);
        long cooldownMs = cooldowns == null ? 3_600_000L : cooldowns.cooldownMs();
        String cooldownText = Durations.korean(Duration.ofMillis(cooldownMs));

        boolean needsTick = pendingLeave != null;
        int joinedCount = joined == null ? 0 : joined.size();
        for (MenuSettings.Job job : menus.settings().jobs()) {
            if (joined == null) {
                // VJobs 가 접속 직후 아직 데이터를 읽는 중
                List<String> lore = List.of("<#AAAAAA>직업 정보를 불러오는 중입니다...");
                set(job.slot(), Items.of(Material.GRAY_DYE).name("<#AAAAAA>" + job.name())
                        .lore(lore).build());
                drawStatus(job, JobSlot.StatusKind.LOADING, joinedCount, limit, remaining, lore, null);
                needsTick = true;
                continue;
            }
            Progress progress = joined.get(job.id());
            JobSlot.State state = JobSlot.state(progress != null, joinedCount, limit, remaining);
            switch (state) {
                case JOINED -> drawJoined(job, progress, joinedCount, limit, remaining, cooldownText);
                case FULL -> {
                    List<String> lore = List.of("<#AAAAAA>다른 직업을 탈퇴한 뒤 가입할 수 있습니다.");
                    set(job.slot(), Items.of(Material.GRAY_DYE)
                            .name("<#FF5555>" + job.name() + " [가입 불가 " + joinedCount + "/" + limit + "]")
                            .lore(lore).build());
                    drawStatus(job, JobSlot.StatusKind.FULL, joinedCount, limit, remaining, lore, null);
                }
                case COOLDOWN -> {
                    List<String> lore = List.of("<#FFDF6B>남은 시간: " + JobSlot.clock(remaining), "",
                            "<#AAAAAA>탈퇴 후 " + cooldownText + " 동안은 새 직업에 가입할 수 없습니다.");
                    set(job.slot(), Items.of(Material.CLOCK).name("<#FF5555>" + job.name() + " [쿨타임 대기]")
                            .lore(lore).build());
                    drawStatus(job, JobSlot.StatusKind.COOLDOWN, joinedCount, limit, remaining, lore, null);
                    needsTick = true;
                }
                case AVAILABLE -> {
                    List<String> lore = new ArrayList<>(rewardLines(job));
                    lore.add("");
                    lore.add("<#55FF55>▶ 좌클릭하여 가입");
                    Click joinClick = (player, click) -> {
                        if (click == ClickType.LEFT) {
                            join(player, job);
                        }
                    };
                    set(job.slot(), Items.of(job.icon()).name("<#F5C542>" + job.name() + levelText(viewer, job))
                            .lore(lore).build(), joinClick);
                    drawStatus(job, JobSlot.StatusKind.AVAILABLE, joinedCount, limit, remaining, lore, joinClick);
                }
            }
        }

        set(SLOT_HUB, Items.of(Material.OAK_DOOR).name("<#F5C542>메인 허브").lore("<#AAAAAA>/economy 로 돌아갑니다.").build(),
                (player, click) -> menus.later(() -> menus.openEconomy(player)));
        set(SLOT_RULES, rules(joined == null ? 0 : joined.size(), limit, remaining, cooldownText));
        if (menus.skillsAvailable()) {
            set(SLOT_SKILLS, Items.of(Material.BOOK).name("<#F5C542>스킬 설정")
                    .lore("<#AAAAAA>직업 레벨 편의 스킬 켜기·끄기", "", "<#55FF55>▶ 클릭하여 열기").build(),
                    (player, click) -> menus.later(() -> menus.openSkills(player)));
        }
        set(SLOT_CLOSE, Items.close(), (player, click) -> menus.later(player::closeInventory));

        ticking = needsTick;
        int count = joined == null ? shownJoined : joined.size();
        if (count != shownJoined) {
            shownJoined = count;
            retitle(viewer, title(count, limit));
        }
    }

    private void drawJoined(MenuSettings.Job job, Progress progress, int joinedCount, int limit,
                            long remaining, String cooldownText) {
        boolean confirming = job.id().equals(pendingLeave);
        List<String> lore = new ArrayList<>(rewardLines(job));
        lore.add("");
        if (confirming) {
            lore.add("<#FF5555>▶ 한 번 더 우클릭하면 탈퇴합니다");
            lore.add("<#AAAAAA>레벨·XP는 유지되고, " + cooldownText + " 동안 새 직업에 가입할 수 없습니다.");
        } else {
            lore.add("<#FF5555>▶ 우클릭하여 탈퇴 (" + cooldownText + " 쿨타임)");
        }
        boolean glow = job.glowWhenJoined() || confirming;
        Click leaveClick = (player, click) -> {
            if (click == ClickType.RIGHT) {
                leave(player, job);
            }
        };
        set(job.slot(), Items.of(job.joinedIcon()).glow(glow)
                .name("<#55FF55>" + job.name() + " Lv." + progress.level() + " [가입 중]").lore(lore).build(),
                leaveClick);
        JobSlot.StatusKind statusKind = confirming ? JobSlot.StatusKind.CONFIRM_LEAVE : JobSlot.StatusKind.JOINED;
        drawStatus(job, statusKind, joinedCount, limit, remaining, lore, leaveClick);
    }

    /** 직업 칸 바로 아래 상태 바(색 유리판 1칸, ECONOMY.md 7.4.2). 로어·클릭은 직업 칸과 동일. */
    private void drawStatus(MenuSettings.Job job, JobSlot.StatusKind kind, int joinedCount, int limit,
                            long remaining, List<String> lore, Click click) {
        if (job.statusSlot().isEmpty()) {
            return;
        }
        JobSlot.StatusBar bar = JobSlot.statusBar(kind, joinedCount, limit, remaining);
        Material pane = Material.matchMaterial(bar.materialId());
        if (pane == null) {
            pane = Material.LIGHT_GRAY_STAINED_GLASS_PANE;
        }
        set(job.statusSlot().getAsInt(), Items.of(pane).name(bar.name()).lore(lore).build(), click);
    }

    /** 가입 안 한 직업의 레벨: VJobs 가 보관 중인 진행도(탈퇴한 직업), 없으면 Lv.1. 읽지 못하면 표시 안 함 */
    private String levelText(Player viewer, MenuSettings.Job job) {
        Progress archived = vjobs.joinBase(viewer.getUniqueId(), job.id());
        return archived == null ? "" : " Lv." + archived.level();
    }

    private List<String> rewardLines(MenuSettings.Job job) {
        List<String> parts = new ArrayList<>();
        for (MenuSettings.Reward reward : job.rewards()) {
            Double money = vjobs.rewardMoney(job.id(), reward.action() + "." + reward.key());
            if (money != null) {
                parts.add(reward.label() + " " + JobSlot.won(money));
            }
        }
        return parts.isEmpty() ? List.of() : List.of("<#AAAAAA>주요 보상: <white>" + String.join(", ", parts));
    }

    private ItemStack rules(int joined, int limit, long remaining, String cooldownText) {
        List<String> lore = new ArrayList<>();
        lore.add("<#AAAAAA>· 직업은 최대 " + limit + "개까지 (지금 " + joined + "개)");
        lore.add("<#AAAAAA>· 빈 자리를 채우는 가입은 바로 됩니다.");
        lore.add("<#AAAAAA>· 탈퇴하면 " + cooldownText + " 동안 새 직업에 가입할 수 없습니다.");
        lore.add("<#AAAAAA>· 탈퇴해도 그 직업의 레벨·XP는 유지됩니다.");
        lore.add("");
        lore.add(remaining > 0 ? "<#FFDF6B>쿨타임 남은 시간: " + JobSlot.clock(remaining) : "<#55FF55>지금 가입할 수 있습니다.");
        return Items.of(Material.CLOCK).name("<#F5C542>직업 규칙·쿨타임").lore(lore).build();
    }

    private void join(Player player, MenuSettings.Job job) {
        if (busy) {
            return;
        }
        busy = true;
        menus.later(() -> {
            busy = false;
            if (!player.isOnline()) {
                return;
            }
            // 쿨타임이 막 끝났으면 30초 주기를 기다리지 않고 VJobs 쿨타임 면제 권한을 지금 붙임
            if (cooldowns != null) {
                cooldowns.refresh(player);
            }
            String result = vjobs.joinJob(player, job.id());
            String message = switch (result == null ? "" : result) {
                case "SUCCESS" -> "<#55FF55>" + job.name() + " 직업에 가입했습니다.";
                case "ALREADY_JOINED" -> "<#FFDF6B>이미 " + job.name() + " 직업에 가입해 있습니다.";
                case "LIMIT_REACHED" -> "<#FF5555>직업은 최대 " + vjobs.jobLimit() + "개까지 가입할 수 있습니다. 다른 직업을 탈퇴한 뒤 가입해 주세요.";
                case "COOLDOWN" -> "<#FF5555>직업을 탈퇴한 뒤에는 잠시 새 직업에 가입할 수 없습니다.";
                case "CANCELLED" -> "<#FF5555>지금은 " + job.name() + " 직업에 가입할 수 없습니다.";
                default -> "<#FF5555>직업 정보를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
            };
            player.sendMessage(Items.text(message));
            redrawIfViewing(player);
        });
    }

    private void leave(Player player, MenuSettings.Job job) {
        if (busy) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!job.id().equals(pendingLeave) || now >= pendingLeaveUntil) {
            pendingLeave = job.id();
            pendingLeaveUntil = now + LEAVE_CONFIRM_MS;
            render(player);
            return;
        }
        pendingLeave = null;
        busy = true;
        menus.later(() -> {
            busy = false;
            if (!player.isOnline()) {
                return;
            }
            long before = cooldowns == null ? 0 : cooldowns.lastLeaveMs(player);
            long calledAt = System.currentTimeMillis();
            Boolean left = vjobs.leaveJob(player, job.id());
            if (Boolean.TRUE.equals(left) && cooldowns != null && cooldowns.lastLeaveMs(player) == before) {
                // API 탈퇴가 JobLeaveEvent 를 내지 않은 경우(VJobs 버전 변경 등). 쿨타임만이라도 직접 기록하고 크게 경고
                menus.plugin().getLogger().severe(player.getName() + " 의 /직업 탈퇴에서 VJobs JobLeaveEvent 가 오지 않았습니다 ("
                        + job.id() + ", " + calledAt + "). 탈퇴 후 쿨타임은 직접 기록했지만 XP 유지 기록은 남지 않았을 수 있습니다."
                        + " /직업 탈퇴를 쓰지 말고 VJobs 버전을 확인하세요.");
                cooldowns.onJobLeave(player);
            }
            String message;
            if (Boolean.TRUE.equals(left)) {
                message = "<#55FF55>" + job.name() + " 직업에서 탈퇴했습니다. <#AAAAAA>레벨·XP는 유지됩니다.";
            } else if (Boolean.FALSE.equals(left)) {
                // 1b 의 XP 유지 기록을 저장하지 못하면 EconomyHub 가 탈퇴를 취소함 (ECONOMY.md 7.2)
                message = "<#FF5555>지금은 탈퇴할 수 없습니다. 잠시 후 다시 시도하거나 관리자에게 문의해 주세요.";
            } else {
                message = "<#FF5555>직업 정보를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
            }
            player.sendMessage(Items.text(message));
            redrawIfViewing(player);
        });
    }

    private void redrawIfViewing(Player player) {
        if (isViewing(player)) {
            render(player);
        }
    }

    @Override
    protected void opened(Player viewer) {
        timer = menus.plugin().getServer().getScheduler().runTaskTimer(menus.plugin(), () -> {
            if (!isViewing(viewer)) {
                stopTimer();
                return;
            }
            if (ticking && !busy) {
                render(viewer);
            }
        }, 20L, 20L);
    }

    @Override
    protected void closed(Player viewer) {
        stopTimer();
    }

    private void stopTimer() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }
}
