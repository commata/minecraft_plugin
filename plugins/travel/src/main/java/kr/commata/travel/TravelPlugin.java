package kr.commata.travel;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import kr.commata.travel.cast.CastManager;
import kr.commata.travel.command.HomeCommand;
import kr.commata.travel.command.SetHomeCommand;
import kr.commata.travel.command.TpaCommands;
import kr.commata.travel.gui.TpaMenuListener;
import kr.commata.travel.listener.ActivityListener;
import kr.commata.travel.listener.CombatListener;
import kr.commata.travel.listener.TpaListener;
import kr.commata.travel.logic.ConfigMigration;
import kr.commata.travel.logic.TravelSettings;
import kr.commata.travel.service.HomeService;
import kr.commata.travel.service.TeleportRequestService;
import kr.commata.travel.storage.TravelStore;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * /home 과 /tpa. 전투 제한, 시전, 취소 규칙, 안전 검사를 한 플러그인 안에서 공유합니다
 * (따로 나누면 몬스터에게 맞은 직후 /home 은 막히는데 /tpa 로 도망칠 수 있게 됨).
 * 예전 Home 플러그인을 대신합니다. 데이터(집·쿨타임·전투 제한)는 같은 키를 써서 그대로 이어집니다.
 */
public final class TravelPlugin extends JavaPlugin {

    private volatile TravelSettings settings = TravelSettings.DEFAULTS;
    private CastManager casts;
    private TeleportRequestService requests;

    @Override
    public void onEnable() {
        migrateHomeConfig();
        saveDefaultConfig();
        reloadSettings();

        TravelStore store = new TravelStore();
        casts = new CastManager(this, store);
        HomeService homes = new HomeService(this, store, casts);
        requests = new TeleportRequestService(this, store, casts);
        requests.startTicker();

        var pm = getServer().getPluginManager();
        pm.registerEvents(new ActivityListener(casts), this);
        pm.registerEvents(new CombatListener(this, casts, store), this);
        pm.registerEvents(new TpaListener(requests), this);
        pm.registerEvents(new TpaMenuListener(this, requests), this);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            var r = event.registrar();
            r.register("home", "집으로 귀환", new HomeCommand(this, homes));
            r.register("sethome", "지금 위치를 집으로 저장", new SetHomeCommand(store));
            r.register("tpa", "접속자 목록을 열거나 이동 요청 보내기", TpaCommands.tpa(getServer(), requests));
            r.register("tpaccept", "받은 이동 요청 수락", TpaCommands.answer(requests, requests::accept));
            r.register("tpdeny", "받은 이동 요청 거절", TpaCommands.answer(requests, requests::deny));
            r.register("tpcancel", "보낸 이동 요청 취소", TpaCommands.simple(requests::cancel));
            r.register("tpatoggle", "이동 요청 받기 켜기·끄기", TpaCommands.simple(requests::toggle));
        });

        // 예전 Home JAR 이 같이 들어 있으면 /home 이 두 번 등록되고 데이터를 같이 씀 → 끄고 경고
        getServer().getScheduler().runTask(this, this::disableOldHomePlugin);

        getLogger().info("Travel " + getPluginMeta().getVersion() + " 활성화 (" + describe(settings) + ")");
    }

    @Override
    public void onDisable() {
        if (requests != null) {
            requests.shutdown();
        }
        if (casts != null) {
            casts.cancelAll();
        }
    }

    public TravelSettings settings() {
        return settings;
    }

    public void reloadSettings() {
        reloadConfig();
        FileConfiguration c = getConfig();
        settings = TravelSettings.of(
                TravelSettings.Mode.of(c.getInt("home.cast-seconds", 7), c.getLong("home.cooldown-seconds", 300)),
                TravelSettings.Mode.of(c.getInt("tpa.cast-seconds", 0), c.getLong("tpa.cooldown-seconds", 300)),
                c.getLong("tpa.request-seconds", 60),
                c.getLong("tpa.resend-seconds", 10),
                c.getLong("combat.mob-seconds", 15),
                c.getLong("combat.pvp-seconds", 30),
                c.getDouble("safety.max-fall-distance", 3.0));
    }

    public static boolean isOverworld(World world) {
        return world != null && world.getEnvironment() == World.Environment.NORMAL;
    }

    /** 처음 켜질 때 plugins/Home/config.yml 의 값을 plugins/Travel/config.yml 로 옮깁니다. 예전 파일은 그대로 둡니다. */
    private void migrateHomeConfig() {
        File target = new File(getDataFolder(), "config.yml");
        File old = new File(getDataFolder().getParentFile(), "Home/config.yml");
        if (target.exists() || !old.isFile()) {
            return;
        }
        saveDefaultConfig();
        YamlConfiguration fresh = YamlConfiguration.loadConfiguration(target);
        Map<String, Object> moved = ConfigMigration.fromHome(YamlConfiguration.loadConfiguration(old).getValues(true));
        moved.forEach(fresh::set);
        try {
            fresh.save(target);
            getLogger().info("plugins/Home/config.yml 설정을 옮겼습니다: " + moved);
        } catch (IOException e) {
            getLogger().warning("Home 설정을 옮기지 못했습니다. 기본값을 사용합니다: " + e.getMessage());
        }
    }

    private void disableOldHomePlugin() {
        Plugin old = getServer().getPluginManager().getPlugin("Home");
        if (old == null) {
            return;
        }
        getLogger().severe("예전 Home 플러그인이 같이 설치되어 있습니다. Travel 이 /home 을 대신하므로 Home 을 끕니다. "
                + "plugins 폴더에서 Home JAR 을 지워 주세요.");
        if (old.isEnabled()) {
            getServer().getPluginManager().disablePlugin(old);
        }
    }

    private static String describe(TravelSettings s) {
        return "home 시전 " + s.home().castSeconds() + "초/쿨타임 " + s.home().cooldown().toSeconds()
                + "초, tpa 시전 " + s.tpa().castSeconds() + "초/쿨타임 " + s.tpa().cooldown().toSeconds()
                + "초, 요청 " + s.requestTimeout().toSeconds() + "초, 전투 제한 "
                + s.mobCombatTag().toSeconds() + "초/PvP " + s.pvpCombatTag().toSeconds() + "초";
    }
}
