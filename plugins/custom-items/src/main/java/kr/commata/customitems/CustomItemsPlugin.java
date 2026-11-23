package kr.commata.customitems;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import kr.commata.customitems.command.CiCommand;
import kr.commata.customitems.listener.JoinListener;
import org.bukkit.plugin.java.JavaPlugin;

public final class CustomItemsPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();

        getServer().getPluginManager().registerEvents(new JoinListener(this), this);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register("ci", "CustomItems 명령어", new CiCommand(this)));

        getLogger().info("CustomItems " + getPluginMeta().getVersion() + " 활성화");
    }
}
