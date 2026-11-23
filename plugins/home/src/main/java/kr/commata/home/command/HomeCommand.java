package kr.commata.home.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Collection;
import java.util.List;
import kr.commata.home.HomePlugin;
import kr.commata.home.cast.CastManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /home: 집으로 귀환. /home reload: 설정 다시 불러오기 (OP). */
public final class HomeCommand implements BasicCommand {

    public static final String RELOAD_PERMISSION = "home.reload";

    private final HomePlugin plugin;
    private final CastManager casts;

    public HomeCommand(HomePlugin plugin, CastManager casts) {
        this.plugin = plugin;
        this.casts = casts;
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
            sender.sendRichMessage("<green>[귀환] 설정을 다시 불러왔습니다.");
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendRichMessage("<red>플레이어만 사용할 수 있습니다.");
            return;
        }
        casts.tryStart(player);
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
