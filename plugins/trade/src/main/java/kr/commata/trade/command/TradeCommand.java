package kr.commata.trade.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Collection;
import java.util.List;
import kr.commata.trade.Msg;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /trade} ({@code /거래}) 명령어 진입점 (TRADE.md 1장, ECONOMY.md 7.3·7.4.6a).
 */
public final class TradeCommand implements BasicCommand {

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!(source.getExecutor() instanceof Player player)) {
            Msg.error(sender, "게임 안에서만 쓸 수 있습니다.");
            return;
        }
        if (!player.hasPermission("trade.use")) {
            Msg.error(player, "거래 명령을 사용할 권한이 없습니다.");
            return;
        }
        Msg.warn(player, "거래 기능이 아직 준비 중입니다.");
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        return List.of();
    }
}
