package kr.commata.travel;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/** 채팅 메시지 앞머리와 색. 메시지는 모두 당사자에게만 보냅니다 (broadcast 사용 안 함). */
public final class Chat {

    public static final Chat HOME = new Chat("[귀환] ");
    public static final Chat TPA = new Chat("[이동] ");

    private final String prefix;

    private Chat(String prefix) {
        this.prefix = prefix;
    }

    public Component prefix() {
        return Component.text(prefix, NamedTextColor.GOLD);
    }

    public void info(Player player, String text) {
        player.sendMessage(Component.text(prefix + text, NamedTextColor.YELLOW));
    }

    public void hint(Player player, String text) {
        player.sendMessage(Component.text(prefix + text, NamedTextColor.GRAY));
    }

    public void success(Player player, String text) {
        player.sendMessage(Component.text(prefix + text, NamedTextColor.GREEN));
    }

    public void error(Player player, String text) {
        player.sendMessage(Component.text(prefix + text, NamedTextColor.RED));
    }

    public static void actionBar(Player player, String text) {
        player.sendActionBar(Component.text(text, NamedTextColor.YELLOW));
    }

    public static void clearActionBar(Player player) {
        player.sendActionBar(Component.empty());
    }
}
