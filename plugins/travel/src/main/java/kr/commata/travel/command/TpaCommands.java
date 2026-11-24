package kr.commata.travel.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import kr.commata.travel.Chat;
import kr.commata.travel.gui.TpaMenu;
import kr.commata.travel.model.TeleportRequest;
import kr.commata.travel.service.TeleportRequestService;
import org.bukkit.Server;
import org.bukkit.entity.Player;

/** /tpa, /tpaccept, /tpdeny, /tpcancel, /tpatoggle */
public final class TpaCommands {

    private TpaCommands() {
    }

    /** /tpa: 접속자 목록 열기. /tpa 닉네임: 바로 요청. */
    public static BasicCommand tpa(Server server, TeleportRequestService requests) {
        return new PlayerCommand() {
            @Override
            void run(Player player, String[] args) {
                if (args.length == 0) {
                    new TpaMenu(server, requests, player).open(player);
                    return;
                }
                Player target = server.getPlayerExact(args[0]);
                if (target == null || !player.canSee(target)) {
                    Chat.TPA.error(player, args[0] + "님은 접속 중이 아닙니다.");
                    return;
                }
                requests.send(player, target);
            }

            @Override
            public Collection<String> suggest(CommandSourceStack source, String[] args) {
                if (args.length > 1 || !(source.getSender() instanceof Player player)) {
                    return List.of();
                }
                String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
                return server.getOnlinePlayers().stream()
                        .filter(p -> !p.equals(player) && player.canSee(p))
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .toList();
            }
        };
    }

    /** /tpaccept [닉네임], /tpdeny [닉네임]: 닉네임이 없으면 가장 최근 요청. */
    public static BasicCommand answer(TeleportRequestService requests,
                                      BiConsumer<Player, Optional<String>> action) {
        return new PlayerCommand() {
            @Override
            void run(Player player, String[] args) {
                action.accept(player, args.length == 0 ? Optional.empty() : Optional.of(args[0]));
            }

            @Override
            public Collection<String> suggest(CommandSourceStack source, String[] args) {
                if (args.length > 1 || !(source.getSender() instanceof Player player)) {
                    return List.of();
                }
                String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
                return requests.incoming(player).stream()
                        .map(TeleportRequest::requesterName)
                        .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .toList();
            }
        };
    }

    /** 인자 없는 명령 (/tpcancel, /tpatoggle). */
    public static BasicCommand simple(Consumer<Player> action) {
        return new PlayerCommand() {
            @Override
            void run(Player player, String[] args) {
                action.accept(player);
            }
        };
    }

    private abstract static class PlayerCommand implements BasicCommand {
        abstract void run(Player player, String[] args);

        @Override
        public void execute(CommandSourceStack source, String[] args) {
            if (!(source.getSender() instanceof Player player)) {
                source.getSender().sendRichMessage("<red>플레이어만 사용할 수 있습니다.");
                return;
            }
            run(player, args);
        }
    }
}
