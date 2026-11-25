package kr.commata.bank;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

/** [은행] 채팅 메시지. 모두 당사자에게만 보냅니다. */
public final class Msg {

    private static final String PREFIX = "[은행] ";

    private Msg() {
    }

    public static void info(CommandSender to, String text) {
        to.sendMessage(Component.text(PREFIX + text, NamedTextColor.YELLOW));
    }

    public static void success(CommandSender to, String text) {
        to.sendMessage(Component.text(PREFIX + text, NamedTextColor.GREEN));
    }

    public static void error(CommandSender to, String text) {
        to.sendMessage(Component.text(PREFIX + text, NamedTextColor.RED));
    }

    public static void plain(CommandSender to, String text) {
        to.sendMessage(Component.text(text, NamedTextColor.GRAY));
    }

    public static void line(CommandSender to, Component line) {
        to.sendMessage(line);
    }
}
