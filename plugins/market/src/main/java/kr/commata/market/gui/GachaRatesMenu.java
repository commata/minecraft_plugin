package kr.commata.market.gui;

import java.util.ArrayList;
import java.util.List;
import kr.commata.common.gui.Items;
import kr.commata.common.gui.Menu;
import kr.commata.market.core.GachaCatalog;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * /뽑기 확률 9×3 GUI (ECONOMY.md 7.4.4, 13.5).
 *
 * <p>2줄: 일반 60%(10), 희귀 30%(12), 영웅 9%(14), 전설 1%(16). 3줄: 뒤로가기(18), 닫기(26).
 */
final class GachaRatesMenu extends Menu {

    private static final int SLOT_BACK = 18;
    private static final int SLOT_CLOSE = 26;
    private static final int BOOKS_PER_LINE = 3;

    private final MarketMenus menus;

    GachaRatesMenu(MarketMenus menus) {
        super(3, Items.text("<#F5C542>인첸트 뽑기 확률표"));
        this.menus = menus;
    }

    @Override
    protected void draw(Player viewer) {
        GachaCatalog gacha = menus.market().config().gacha();
        for (GachaCatalog.Tier tier : gacha.tiers()) {
            Material icon = Material.matchMaterial(tier.iconMaterial());
            if (icon == null) {
                icon = Material.BOOK;
            }
            List<String> lore = new ArrayList<>();
            lore.add("<#AAAAAA>등급 확률: <" + tier.color() + ">" + tier.percentText()
                    + " <#AAAAAA>(등급 내 균등, 총 " + tier.books().size() + "종)");
            if (tier.pityTarget()) {
                lore.add("<#FFDF6B>전설 천장 대상 (" + gacha.pityThreshold() + "회째 확정)");
            }
            lore.add("");
            StringBuilder line = new StringBuilder();
            int count = 0;
            for (GachaCatalog.Book book : tier.books()) {
                if (count > 0) {
                    line.append(", ");
                }
                line.append(book.displayName());
                count++;
                if (count >= BOOKS_PER_LINE) {
                    lore.add("<white>" + line);
                    line.setLength(0);
                    count = 0;
                }
            }
            if (count > 0) {
                lore.add("<white>" + line);
            }
            set(tier.slot(), Items.of(icon)
                    .name("<" + tier.color() + ">" + tier.displayName() + " (" + tier.percentText() + ")")
                    .lore(lore)
                    .build());
        }

        set(SLOT_BACK, Items.of(Material.OAK_DOOR)
                .name("<#F5C542>뒤로가기")
                .lore("<#AAAAAA>인첸트 책 뽑기 화면으로 돌아갑니다.", "", "<#55FF55>▶ 클릭하여 돌아가기")
                .build(), (player, click) -> menus.later(() -> menus.openGacha(player)));
        set(SLOT_CLOSE, Items.close(), (player, click) -> menus.later(player::closeInventory));
    }
}
