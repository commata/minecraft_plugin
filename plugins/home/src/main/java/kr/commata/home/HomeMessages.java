package kr.commata.home;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/** 귀환 메시지는 모두 본인에게만 보냅니다 (broadcast 사용 안 함). */
public final class HomeMessages {

    private static final String PREFIX = "[귀환] ";

    private HomeMessages() {
    }

    public static void info(Player player, String text) {
        player.sendMessage(Component.text(PREFIX + text, NamedTextColor.YELLOW));
    }

    public static void hint(Player player, String text) {
        player.sendMessage(Component.text(PREFIX + text, NamedTextColor.GRAY));
    }

    public static void success(Player player, String text) {
        player.sendMessage(Component.text(PREFIX + text, NamedTextColor.GREEN));
    }

    public static void error(Player player, String text) {
        player.sendMessage(Component.text(PREFIX + text, NamedTextColor.RED));
    }

    public static void actionBar(Player player, int secondsLeft) {
        player.sendActionBar(Component.text("귀환까지 " + secondsLeft + "초", NamedTextColor.YELLOW));
    }

    public static void clearActionBar(Player player) {
        player.sendActionBar(Component.empty());
    }
}
