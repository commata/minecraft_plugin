package kr.commata.customitems.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Collection;
import java.util.List;
import kr.commata.common.text.Messages;
import kr.commata.customitems.CustomItemsPlugin;
import org.bukkit.command.CommandSender;

/** /ci version, /ci reload */
public final class CiCommand implements BasicCommand {

    private static final String RELOAD_PERMISSION = "customitems.reload";

    private final CustomItemsPlugin plugin;

    public CiCommand(CustomItemsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        String sub = args.length == 0 ? "version" : args[0].toLowerCase();

        switch (sub) {
            case "version" -> sender.sendMessage(Messages.parse(
                    "<gray>CustomItems <white>" + plugin.getPluginMeta().getVersion()
                            + "</white> (Paper " + plugin.getServer().getMinecraftVersion() + ")"));
            case "reload" -> {
                if (!sender.hasPermission(RELOAD_PERMISSION)) {
                    sender.sendMessage(Messages.parse("<red>권한이 없습니다."));
                    return;
                }
                plugin.reloadConfig();
                sender.sendMessage(Messages.parse("<green>설정을 다시 불러왔습니다."));
            }
            default -> sender.sendMessage(Messages.parse("<yellow>사용법: /ci <version|reload>"));
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase();
            return List.of("version", "reload").stream().filter(s -> s.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
