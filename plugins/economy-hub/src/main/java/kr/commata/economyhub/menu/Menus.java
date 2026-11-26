package kr.commata.economyhub.menu;

import java.util.List;
import kr.commata.common.gui.Items;
import kr.commata.economyhub.jobs.JobCooldownGuard;
import kr.commata.economyhub.jobs.VJobsHook;
import kr.commata.economyhub.skills.CaveVision;
import kr.commata.economyhub.skills.Skills;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** /economy·/직업 창을 여는 곳. 창끼리 이동(메인 허브 ↔ 직업 관리)할 때도 여기를 거칩니다. */
public final class Menus {

    private final Plugin plugin;
    private final Balances balances;
    private volatile MenuSettings settings;
    private final VJobsHook vjobs;
    private final JobCooldownGuard cooldowns;
    private Skills skills;
    private CaveVision caveVision;

    /**
     * @param vjobs     VJobs 가 없으면 null (/직업 은 [준비 중])
     * @param cooldowns 탈퇴 시각을 기록하지 못하는 상태면 null (남은 쿨타임 표시 없음, VJobs 가 가입 때 판단)
     */
    public Menus(Plugin plugin, MenuSettings settings, VJobsHook vjobs, JobCooldownGuard cooldowns) {
        this.plugin = plugin;
        this.balances = new Balances(plugin.getServer());
        this.settings = settings;
        this.vjobs = vjobs;
        this.cooldowns = cooldowns;
    }

    /** 편의 스킬이 켜져 있으면 /직업 에 스킬 설정 버튼이 생김 */
    public void enableSkills(Skills skills, CaveVision caveVision) {
        this.skills = skills;
        this.caveVision = caveVision;
    }

    /** 스킬이 없으면 false */
    public boolean openSkills(Player player) {
        if (skills == null) {
            return false;
        }
        new SkillMenu(this, skills, caveVision).open(player);
        return true;
    }

    boolean skillsAvailable() {
        return skills != null;
    }

    public void reload(MenuSettings settings) {
        this.settings = settings;
    }

    public void openEconomy(Player player) {
        new EconomyMenu(this).open(player);
    }

    /** VJobs 가 없으면 false */
    public boolean openJobs(Player player) {
        if (vjobs == null) {
            return false;
        }
        new JobsMenu(this, player).open(player);
        return true;
    }

    boolean jobsAvailable() {
        return vjobs != null;
    }

    Plugin plugin() {
        return plugin;
    }

    MenuSettings settings() {
        return settings;
    }

    VJobsHook vjobs() {
        return vjobs;
    }

    JobCooldownGuard cooldowns() {
        return cooldowns;
    }

    /** 내 잔액 머리 (/economy 10번 등) */
    ItemStack balanceHead(Player player) {
        String balance = balances.formatted(player);
        return Items.of(Material.PLAYER_HEAD).head(player)
                .name("<#F5C542>내 잔액")
                .lore(balance == null
                        ? List.of("<#AAAAAA>잔액을 불러올 수 없습니다.")
                        : List.of("<white>" + balance, "", "<#AAAAAA>/money 로도 확인할 수 있습니다."))
                .build();
    }

    /** 인벤토리 이벤트 안에서는 창을 닫거나 명령을 실행하지 않고 다음 틱에 처리 */
    void later(Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    /** 창을 닫고 채팅에 안내 문구(MiniMessage)를 보냄 */
    void closeAndSay(Player player, List<String> lines) {
        later(() -> {
            player.closeInventory();
            for (String line : lines) {
                player.sendMessage(Items.text(line));
            }
        });
    }
}
