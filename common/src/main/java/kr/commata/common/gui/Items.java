package kr.commata.common.gui;

import com.google.common.collect.ImmutableMultimap;
import java.util.ArrayList;
import java.util.List;
import kr.commata.common.text.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * 메뉴 아이콘 만들기. 이름·로어는 MiniMessage(예: {@code "<#F5C542>서버 경제 메뉴"})이고 기울임은 자동으로 끕니다.
 * 도구 아이콘의 공격력·속도 줄은 숨깁니다.
 */
public final class Items {

    /** 빈칸 채움 (ECONOMY.md 7.4): 회색 유리판, 이름 " " */
    public static final Material FILLER = Material.GRAY_STAINED_GLASS_PANE;

    private Items() {
    }

    public static Builder of(Material material) {
        return new Builder(material);
    }

    public static ItemStack filler() {
        return of(FILLER).name(" ").build();
    }

    /** 바로 닫기 버튼에 쓰는 BARRIER */
    public static ItemStack close() {
        return of(Material.BARRIER).name("<#FF5555>닫기").build();
    }

    /** MiniMessage 한 줄을 기울임 없는 Component 로 */
    public static Component text(String miniMessage) {
        return Messages.parse(miniMessage).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static final class Builder {
        private final Material material;
        private int amount = 1;
        private String name;
        private final List<String> lore = new ArrayList<>();
        private boolean glow;
        private Player head;

        private Builder(Material material) {
            this.material = material;
        }

        public Builder amount(int amount) {
            this.amount = Math.clamp(amount, 1, material.getMaxStackSize());
            return this;
        }

        public Builder name(String miniMessage) {
            this.name = miniMessage;
            return this;
        }

        public Builder lore(String... lines) {
            this.lore.addAll(List.of(lines));
            return this;
        }

        public Builder lore(List<String> lines) {
            this.lore.addAll(lines);
            return this;
        }

        /** 인챈트 광택만 (실제 인챈트 없음) */
        public Builder glow(boolean glow) {
            this.glow = glow;
            return this;
        }

        /** PLAYER_HEAD 에 이 플레이어 스킨 */
        public Builder head(Player player) {
            this.head = player;
            return this;
        }

        public ItemStack build() {
            ItemStack stack = new ItemStack(material, amount);
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) {
                return stack;
            }
            if (name != null) {
                meta.displayName(text(name));
            }
            if (!lore.isEmpty()) {
                List<Component> lines = new ArrayList<>(lore.size());
                for (String line : lore) {
                    lines.add(text(line));
                }
                meta.lore(lines);
            }
            if (glow) {
                meta.setEnchantmentGlintOverride(true);
            }
            if (head != null && meta instanceof SkullMeta skull) {
                skull.setPlayerProfile(head.getPlayerProfile());
            }
            // 1.20.5+ 는 기본 공격력 줄을 숨기려면 속성 목록이 비어 있지 않은 상태로 지정돼 있어야 해서, 빈 목록으로 덮고 플래그도 붙임
            meta.setAttributeModifiers(ImmutableMultimap.of());
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            stack.setItemMeta(meta);
            return stack;
        }
    }
}
