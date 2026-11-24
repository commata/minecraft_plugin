package kr.commata.travel.service;

import java.time.Duration;
import java.util.Optional;
import kr.commata.common.time.Durations;
import kr.commata.travel.Chat;
import kr.commata.travel.TravelPlugin;
import kr.commata.travel.cast.CastJob;
import kr.commata.travel.cast.CastManager;
import kr.commata.travel.logic.HomeLocation;
import kr.commata.travel.logic.Timers;
import kr.commata.travel.storage.TravelStore;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** /home: 저장된 집으로 귀환. 시전·취소·안전 검사는 {@link CastManager} 가 처리합니다. */
public final class HomeService {

    public static final String BYPASS_COOLDOWN_PERMISSION = "home.bypass.cooldown";

    private final TravelPlugin plugin;
    private final TravelStore store;
    private final CastManager casts;

    public HomeService(TravelPlugin plugin, TravelStore store, CastManager casts) {
        this.plugin = plugin;
        this.store = store;
        this.casts = casts;
    }

    public void tryStart(Player player) {
        Chat chat = Chat.HOME;
        if (casts.isBusy(player)) {
            chat.error(player, "이미 귀환 또는 이동 중입니다.");
            return;
        }
        // 집은 오버월드에만 정할 수 있지만, /home 은 네더·엔드 등 어느 차원에서든 사용할 수 있음
        Optional<HomeLocation> home = store.home(player);
        if (home.isEmpty()) {
            chat.error(player, "저장된 집이 없습니다. 먼저 /sethome 으로 집을 정해 주세요.");
            return;
        }
        if (resolve(home.get()) == null) {
            chat.error(player, "집이 있는 월드를 찾을 수 없습니다. /sethome 으로 집을 다시 정해 주세요.");
            return;
        }
        Duration combat = casts.combatLeft(player);
        if (!combat.isZero()) {
            chat.error(player, "전투 중에는 귀환할 수 없습니다. (" + Durations.korean(combat) + " 남음)");
            return;
        }
        Duration cooldown = Timers.remaining(System.currentTimeMillis(), store.homeReadyAt(player));
        if (!cooldown.isZero() && !player.hasPermission(BYPASS_COOLDOWN_PERMISSION)) {
            chat.error(player, Durations.korean(cooldown) + " 후에 다시 사용할 수 있습니다.");
            return;
        }
        int seconds = plugin.settings().home().castSeconds();
        casts.start(player, new Job(seconds), seconds + "초 후 집으로 이동합니다.");
    }

    private final class Job implements CastJob {
        private final int seconds;

        Job(int seconds) {
            this.seconds = seconds;
        }

        @Override
        public Chat chat() {
            return Chat.HOME;
        }

        @Override
        public String noun() {
            return "귀환";
        }

        @Override
        public int seconds() {
            return seconds;
        }

        @Override
        public String actionBar(int secondsLeft) {
            return "귀환까지 " + secondsLeft + "초";
        }

        @Override
        public Destination destination(Player player) {
            Location target = store.home(player).map(HomeService.this::resolve).orElse(null);
            if (target == null) {
                return Destination.fail("집이 있는 월드를 찾을 수 없습니다.");
            }
            return Destination.to(target,
                    "집 위치가 막혀 있거나 위험해서 이동하지 않았습니다. 집에 직접 가서 /sethome 으로 다시 정해 주세요.");
        }

        @Override
        public void onSuccess(Player player) {
            store.setHomeReadyAt(player,
                    System.currentTimeMillis() + plugin.settings().home().cooldown().toMillis());
            Chat.HOME.success(player, "집으로 이동했습니다!");
        }
    }

    /** 저장된 집을 Location 으로. 월드가 없거나 오버월드가 아니면 null. */
    private Location resolve(HomeLocation home) {
        World world = plugin.getServer().getWorld(home.world());
        if (world == null || !TravelPlugin.isOverworld(world)) {
            return null;
        }
        return new Location(world, home.x(), home.y(), home.z(), home.yaw(), home.pitch());
    }
}
