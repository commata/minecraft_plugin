package kr.commata.customitems.listener;

import kr.commata.common.text.Messages;
import kr.commata.customitems.CustomItemsPlugin;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class JoinListener implements Listener {

    private final CustomItemsPlugin plugin;

    public JoinListener(CustomItemsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        String template = plugin.getConfig().getString("join-message", "");
        if (template.isBlank()) {
            return;
        }
        Player player = event.getPlayer();
        player.sendMessage(Messages.parse(template, Placeholder.unparsed("player", player.getName())));
    }
}
