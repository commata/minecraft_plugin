package kr.commata.common.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * MiniMessage 형식 문자열을 Adventure {@link Component} 로 바꿔 주는 도우미.
 * 예: {@code "<green>환영합니다, <player>!"}
 */
public final class Messages {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private Messages() {
    }

    public static Component parse(String miniMessage, TagResolver... resolvers) {
        return MINI.deserialize(miniMessage, resolvers);
    }
}
