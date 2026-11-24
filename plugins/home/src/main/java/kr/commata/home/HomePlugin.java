package kr.commata.home;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import kr.commata.home.cast.CastManager;
import kr.commata.home.command.HomeCommand;
import kr.commata.home.command.SetHomeCommand;
import kr.commata.home.listener.ActivityListener;
import kr.commata.home.listener.CombatListener;
import kr.commata.home.logic.HomeSettings;
import kr.commata.home.storage.HomeStore;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class HomePlugin extends JavaPlugin {

    private volatile HomeSettings settings = HomeSettings.DEFAULTS;
    private CastManager casts;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSettings();

        HomeStore store = new HomeStore(this);
        casts = new CastManager(this, store);

        var pm = getServer().getPluginManager();
        pm.registerEvents(new ActivityListener(casts), this);
        pm.registerEvents(new CombatListener(this, casts, store), this);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register("home", "집으로 귀환", new HomeCommand(this, casts));
            event.registrar().register("sethome", "지금 위치를 집으로 저장", new SetHomeCommand(store));
        });

        getLogger().info("Home " + getPluginMeta().getVersion() + " 활성화 (" + describe(settings) + ")");
    }

    @Override
    public void onDisable() {
        if (casts != null) {
            casts.cancelAll();
        }
    }

    public HomeSettings settings() {
        return settings;
    }

    public void reloadSettings() {
        reloadConfig();
        FileConfiguration c = getConfig();
        settings = HomeSettings.of(
                c.getInt("cast-seconds", 7),
                c.getLong("cooldown-seconds", 300),
                c.getLong("combat.mob-seconds", 15),
                c.getLong("combat.pvp-seconds", 30),
                c.getDouble("safety.max-fall-distance", 3.0));
    }

    public static boolean isOverworld(World world) {
        return world != null && world.getEnvironment() == World.Environment.NORMAL;
    }

    private static String describe(HomeSettings s) {
        return "시전 " + s.castSeconds() + "초, 쿨타임 " + s.cooldown().toSeconds() + "초, 전투 제한 "
                + s.mobCombatTag().toSeconds() + "초/PvP " + s.pvpCombatTag().toSeconds() + "초";
    }
}
