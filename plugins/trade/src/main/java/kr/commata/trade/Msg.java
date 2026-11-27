package kr.commata.trade;

import kr.commata.common.text.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;

/** Trade 플러그인 MiniMessage 출력 도우미 (ECONOMY.md 7.4.6a). */
public final class Msg {

    public static final String PREFIX = "<#F5C542>[거래] <#AAAAAA>";

    private Msg() {
    }

    public static void info(CommandSender sender, String text) {
        sender.sendMessage(Messages.parse(PREFIX + "<white>" + text));
    }

    public static void success(CommandSender sender, String text) {
        sender.sendMessage(Messages.parse(PREFIX + "<#55FF55>" + text));
    }

    public static void warn(CommandSender sender, String text) {
        sender.sendMessage(Messages.parse(PREFIX + "<#FFDF6B>" + text));
    }

    public static void error(CommandSender sender, String text) {
        sender.sendMessage(Messages.parse(PREFIX + "<#FF5555>" + text));
    }

    public static void raw(CommandSender sender, String miniMessage) {
        sender.sendMessage(Messages.parse(miniMessage));
    }

    public static void component(CommandSender sender, Component component) {
        sender.sendMessage(component);
    }
}
