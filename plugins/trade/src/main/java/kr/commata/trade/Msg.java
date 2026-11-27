package kr.commata.trade;

import kr.commata.common.text.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

/**
 * Trade 플러그인 MiniMessage 출력 도우미 (ECONOMY.md 7.4.6a, P2-8).
 *
 * <p>아이템 이름·플레이어 입력·예외 메시지 등 사용자 제어 문자열은 반드시 {@link Placeholder#unparsed}
 * 또는 {@code *Plain} 메서드로 리터럴 삽입하여 MiniMessage 태그로 해석되지 않게 합니다.
 */
public final class Msg {

    public static final String PREFIX = "<#F5C542>[거래] <#AAAAAA>";

    private Msg() {
    }

    public static void info(CommandSender sender, String text, TagResolver... resolvers) {
        sender.sendMessage(Messages.parse(PREFIX + "<white>" + text, resolvers));
    }

    public static void infoPlain(CommandSender sender, String plainText) {
        sender.sendMessage(Messages.parse(PREFIX + "<white><msg>", Placeholder.unparsed("msg", nonNull(plainText))));
    }

    public static void success(CommandSender sender, String text, TagResolver... resolvers) {
        sender.sendMessage(Messages.parse(PREFIX + "<#55FF55>" + text, resolvers));
    }

    public static void successPlain(CommandSender sender, String plainText) {
        sender.sendMessage(Messages.parse(PREFIX + "<#55FF55><msg>", Placeholder.unparsed("msg", nonNull(plainText))));
    }

    public static void warn(CommandSender sender, String text, TagResolver... resolvers) {
        sender.sendMessage(Messages.parse(PREFIX + "<#FFDF6B>" + text, resolvers));
    }

    public static void error(CommandSender sender, String text, TagResolver... resolvers) {
        sender.sendMessage(Messages.parse(PREFIX + "<#FF5555>" + text, resolvers));
    }

    public static void errorPlain(CommandSender sender, String plainText) {
        sender.sendMessage(Messages.parse(PREFIX + "<#FF5555><msg>", Placeholder.unparsed("msg", nonNull(plainText))));
    }

    public static void raw(CommandSender sender, String miniMessage, TagResolver... resolvers) {
        sender.sendMessage(Messages.parse(miniMessage, resolvers));
    }

    public static void component(CommandSender sender, Component component) {
        sender.sendMessage(component);
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }
}
