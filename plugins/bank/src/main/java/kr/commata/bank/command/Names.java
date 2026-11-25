package kr.commata.bank.command;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** 닉네임 자동완성: 접속 중인 플레이어. */
final class Names {

    private Names() {
    }

    static Collection<String> online(Server server, CommandSender sender, String typed) {
        String prefix = typed == null ? "" : typed.toLowerCase(Locale.ROOT);
        return server.getOnlinePlayers().stream()
                .filter(p -> !(sender instanceof Player me) || me.canSee(p))
                .map(Player::getName)
                .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }

    static List<String> startingWith(List<String> options, String typed) {
        String prefix = typed == null ? "" : typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.startsWith(prefix)).toList();
    }
}
