package kr.commata.economyhub.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.commata.common.gui.Items;
import kr.commata.common.gui.Menu;
import kr.commata.economyhub.jobs.VJobsHook.Progress;
import kr.commata.economyhub.skills.CaveVision;
import kr.commata.economyhub.skills.Skill;
import kr.commata.economyhub.skills.Skills;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

/**
 * 직업 스킬 관리 9×3 (ECONOMY.md 15.5). 가입 중인 직업 2개의 스킬을 한 줄씩 보여 주고, 해금된 스킬은 클릭으로 켜고 끕니다.
 * 켜짐 LIME_DYE, 꺼짐 GRAY_DYE, 미해금 IRON_BARS. 상태는 플레이어 PDC 에 저장돼 재접속·재시작 후에도 유지됩니다.
 */
final class SkillMenu extends Menu {

    /** 직업 한 줄: 직업 아이콘 칸과 스킬 시작 칸 */
    private static final int[] ROW_ICON = {1, 10};
    private static final int[] ROW_FIRST_SKILL = {3, 12};
    static final int SLOT_BACK = 18;
    static final int SLOT_INFO = 22;
    static final int SLOT_CLOSE = 26;

    private final Menus menus;
    private final Skills skills;
    private final CaveVision caveVision;

    SkillMenu(Menus menus, Skills skills, CaveVision caveVision) {
        super(3, Items.text("<#F5C542>직업 스킬 관리"));
        this.menus = menus;
        this.skills = skills;
        this.caveVision = caveVision;
    }

    @Override
    protected void draw(Player viewer) {
        Map<String, Progress> joined = skills.jobs(viewer);
        List<MenuSettings.Job> rows = new ArrayList<>();
        if (joined != null) {
            for (MenuSettings.Job job : menus.settings().jobs()) {
                if (joined.containsKey(job.id()) && rows.size() < ROW_ICON.length) {
                    rows.add(job);
                }
            }
        }
        if (joined == null) {
            set(13, Items.of(Material.GRAY_DYE).name("<#AAAAAA>직업 정보를 불러오는 중입니다...").build());
        } else if (rows.isEmpty()) {
            set(13, Items.of(Material.GRAY_DYE).name("<#AAAAAA>가입한 직업이 없습니다")
                    .lore("<#AAAAAA>/직업 에서 직업에 가입하면 스킬이 열립니다.").build());
        }
        for (int row = 0; row < rows.size(); row++) {
            MenuSettings.Job job = rows.get(row);
            int level = joined.get(job.id()).level();
            set(ROW_ICON[row], Items.of(job.joinedIcon()).glow(job.glowWhenJoined())
                    .name("<#55FF55>" + job.name() + " Lv." + level).lore("<#AAAAAA>오른쪽 스킬을 클릭해 켜고 끕니다.").build());
            List<Skill> list = Skill.ofJob(job.id());
            for (int i = 0; i < list.size() && i < 5; i++) {
                drawSkill(viewer, ROW_FIRST_SKILL[row] + i, list.get(i), level);
            }
        }

        set(SLOT_BACK, Items.of(Material.OAK_DOOR).name("<#F5C542>직업 관리로").lore("<#AAAAAA>/직업 으로 돌아갑니다.").build(),
                (player, click) -> menus.later(() -> menus.openJobs(player)));
        set(SLOT_INFO, Items.of(Material.BOOK).name("<#F5C542>편의 스킬 안내").lore(
                "<#AAAAAA>· 지금 가입한 직업의 스킬만 동작합니다.",
                "<#AAAAAA>· 직업 레벨이 오르면 Lv.10·20에 스킬이 열립니다.",
                "<#AAAAAA>· 보상 금액은 바뀌지 않고 반복 작업만 줄여 줍니다.",
                "<#AAAAAA>· 탈퇴하면 바로 꺼지고, 다시 가입하면 레벨대로 열립니다.").build());
        set(SLOT_CLOSE, Items.close(), (player, click) -> menus.later(player::closeInventory));
    }

    private void drawSkill(Player viewer, int slot, Skill skill, int level) {
        int unlock = skills.settings().unlock(skill);
        if (!skills.settings().available(skill)) {
            set(slot, Items.of(Material.BARRIER).name("<#AAAAAA>" + skill.displayName())
                    .lore("<#AAAAAA>" + skill.description(), "", "<#FF5555>서버에서 꺼 둔 스킬입니다.").build());
            return;
        }
        if (level < unlock) {
            set(slot, Items.of(Material.IRON_BARS).name("<#AAAAAA>" + skill.displayName() + " <#FF5555>[Lv." + unlock + "에 해금]")
                    .lore("<#AAAAAA>" + skill.description(), "", "<#AAAAAA>지금 Lv." + level).build());
            return;
        }
        boolean on = skills.isOn(viewer, skill);
        set(slot, Items.of(on ? Material.LIME_DYE : Material.GRAY_DYE)
                .name((on ? "<#55FF55>" : "<#AAAAAA>") + skill.displayName() + (on ? " [켜짐]" : " [꺼짐]"))
                .lore("<#AAAAAA>" + skill.description(), "", on ? "<#FF5555>▶ 클릭하여 끄기" : "<#55FF55>▶ 클릭하여 켜기").build(),
                (player, click) -> {
                    if (click != ClickType.LEFT) {
                        return;
                    }
                    skills.setOn(player, skill, !skills.isOn(player, skill));
                    if (skill == Skill.MINER_CAVE_VISION) {
                        caveVision.update(player); // 끄면 야간 투시를 바로 지움
                    }
                    render(player);
                });
    }
}
