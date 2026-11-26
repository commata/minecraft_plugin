package kr.commata.economyhub.menu;

import kr.commata.common.gui.Items;
import kr.commata.common.gui.Menu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** /economy 메인 허브 9×3 (ECONOMY.md 7.4.1). 다른 플러그인의 기능은 그 명령을 실행해서 엽니다. */
final class EconomyMenu extends Menu {

    static final String TITLE = "<#F5C542>서버 경제 메뉴";

    private final Menus menus;

    EconomyMenu(Menus menus) {
        super(3, Items.text(TITLE));
        this.menus = menus;
    }

    @Override
    protected void draw(Player viewer) {
        MenuSettings settings = menus.settings();

        set(10, menus.balanceHead(viewer));

        if (menus.jobsAvailable()) {
            set(11, Items.of(Material.IRON_PICKAXE).name("<#F5C542>직업 관리")
                    .lore("<#AAAAAA>직업 가입·탈퇴, 레벨, 쿨타임 확인", "", "<#55FF55>▶ 클릭하여 열기 (/직업)").build(),
                    (player, click) -> menus.later(() -> menus.openJobs(player)));
        } else {
            set(11, notReady("직업 관리"), EconomyMenu::sayNotReady);
        }

        command(12, "shop", Material.CHEST, "서버 상점", "<#AAAAAA>건축·장식·농사 재료를 삽니다.", settings);
        command(13, "rare-shop", Material.DIAMOND, "희귀 상점", "<#AAAAAA>다이아·인첸트 책 (주간 한도)", settings);
        command(14, "gacha", Material.ENCHANTED_BOOK, "인첸트 뽑기", "<#AAAAAA>인첸트 책 1권 뽑기 (확률 공개)", settings);
        command(15, "sell", Material.GOLD_INGOT, "아이템 판매", "<#AAAAAA>직접 얻은 아이템을 서버에 팝니다.", settings);
        command(16, "baltop", Material.NETHER_STAR, "부자 순위", "<#AAAAAA>잔액 순위를 채팅에 보여 줍니다.", settings);

        set(18, Items.of(Material.PAPER).name("<#F5C542>송금 안내")
                .lore("<#AAAAAA>다른 플레이어에게 돈을 보냅니다.", "", "<#55FF55>▶ 클릭하면 채팅에 사용법 안내").build(),
                (player, click) -> menus.closeAndSay(player, settings.payGuide()));

        command(20, "trade", Material.EMERALD, "유저 거래", "<#AAAAAA>1:1 안전 거래 (아이템·돈)", settings);

        set(22, Items.of(Material.BOOK).name("<#F5C542>이용 가이드")
                .lore("<#AAAAAA>돈 버는 법과 쓰는 곳", "", "<#55FF55>▶ 클릭하면 채팅에 안내").build(),
                (player, click) -> menus.closeAndSay(player, settings.guide()));

        set(26, Items.close(), (player, click) -> menus.later(player::closeInventory));
    }

    /** 명령을 실행하는 버튼. 명령을 제공하는 플러그인이 꺼져 있으면 GRAY_DYE + [준비 중] */
    private void command(int slot, String key, Material icon, String name, String description, MenuSettings settings) {
        MenuSettings.Button button = settings.button(key);
        boolean ready = button != null && !button.command().isEmpty()
                && (button.plugin().isEmpty() || menus.plugin().getServer().getPluginManager().isPluginEnabled(button.plugin()));
        if (!ready) {
            set(slot, notReady(name), EconomyMenu::sayNotReady);
            return;
        }
        set(slot, Items.of(icon).name("<#F5C542>" + name)
                .lore(description, "", "<#55FF55>▶ 클릭하여 열기 (/" + button.command() + ")").build(),
                (player, click) -> menus.later(() -> {
                    player.closeInventory();
                    player.performCommand(button.command());
                }));
    }

    private static void sayNotReady(Player player, org.bukkit.event.inventory.ClickType click) {
        player.sendMessage(Items.text("<#AAAAAA>아직 준비 중인 기능입니다."));
    }

    private static ItemStack notReady(String name) {
        return Items.of(Material.GRAY_DYE).name("<#AAAAAA>" + name).lore("<#AAAAAA>[준비 중]").build();
    }
}
