package kr.commata.travel.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Collection;
import java.util.List;
import kr.commata.travel.TravelPlugin;
import kr.commata.travel.service.HomeService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /home: 집으로 귀환. /home reload: 설정 다시 불러오기 (OP). */
public final class HomeCommand implements BasicCommand {

    public static final String RELOAD_PERMISSION = "home.reload";

    private final TravelPlugin plugin;
    private final HomeService homes;

    public HomeCommand(TravelPlugin plugin, HomeService homes) {
        this.plugin = plugin;
        this.homes = homes;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();

        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission(RELOAD_PERMISSION)) {
                sender.sendRichMessage("<red>권한이 없습니다.");
                return;
            }
            plugin.reloadSettings();
            sender.sendRichMessage("<green>[Travel] /home, /tpa 설정을 다시 불러왔습니다.");
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendRichMessage("<red>플레이어만 사용할 수 있습니다.");
            return;
        }
        homes.tryStart(player);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1 && source.getSender().hasPermission(RELOAD_PERMISSION)) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase();
            return "reload".startsWith(prefix) ? List.of("reload") : List.of();
        }
        return List.of();
    }
}
