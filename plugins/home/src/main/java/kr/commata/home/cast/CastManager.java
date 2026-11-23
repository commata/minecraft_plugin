package kr.commata.home.cast;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import kr.commata.common.time.Durations;
import kr.commata.home.HomeMessages;
import kr.commata.home.HomePlugin;
import kr.commata.home.logic.CancelReason;
import kr.commata.home.logic.Countdown;
import kr.commata.home.logic.HomeLocation;
import kr.commata.home.logic.HomeSettings;
import kr.commata.home.logic.PlayerSafety;
import kr.commata.home.logic.Timers;
import kr.commata.home.storage.HomeStore;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

/** {@code /home} 시전(카운트다운)을 관리합니다. 모든 메서드는 메인 스레드에서 호출합니다. */
public final class CastManager {

    public static final String BYPASS_COOLDOWN_PERMISSION = "home.bypass.cooldown";

    private static final class Cast {
        final Countdown countdown;
        BukkitTask task;

        Cast(int seconds) {
            this.countdown = new Countdown(seconds);
        }
    }

    private final HomePlugin plugin;
    private final HomeStore store;
    private final Map<UUID, Cast> casts = new HashMap<>();
    /** 카운트다운이 끝나고 청크 로드·순간이동을 기다리는 플레이어 (그 사이 /home 중복 방지). */
    private final Set<UUID> finishing = new HashSet<>();

    public CastManager(HomePlugin plugin, HomeStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    public boolean isCasting(Player player) {
        return casts.containsKey(player.getUniqueId());
    }

    /** /home 실행. 조건을 검사하고 카운트다운을 시작합니다. */
    public void tryStart(Player player) {
        HomeSettings settings = plugin.settings();

        if (isCasting(player) || finishing.contains(player.getUniqueId())) {
            HomeMessages.error(player, "이미 귀환 중입니다.");
            return;
        }
        if (!HomePlugin.isOverworld(player.getWorld())) {
            HomeMessages.error(player, "오버월드에서만 귀환할 수 있습니다.");
            return;
        }
        Optional<HomeLocation> home = store.home(player);
        if (home.isEmpty()) {
            HomeMessages.error(player, "저장된 집이 없습니다. 먼저 /sethome 으로 집을 정해 주세요.");
            return;
        }
        if (resolve(home.get()) == null) {
            HomeMessages.error(player, "집이 있는 월드를 찾을 수 없습니다. /sethome 으로 집을 다시 정해 주세요.");
            return;
        }

        long now = System.currentTimeMillis();
        Duration combat = Timers.remaining(now, store.combatUntil(player));
        if (!combat.isZero()) {
            HomeMessages.error(player, "전투 중에는 귀환할 수 없습니다. (" + Durations.korean(combat) + " 남음)");
            return;
        }
        Duration cooldown = Timers.remaining(now, store.readyAt(player));
        if (!cooldown.isZero() && !player.hasPermission(BYPASS_COOLDOWN_PERMISSION)) {
            HomeMessages.error(player, Durations.korean(cooldown) + " 후에 다시 사용할 수 있습니다.");
            return;
        }

        int seconds = settings.castSeconds();
        if (seconds <= 0) {
            finish(player);
            return;
        }

        Cast cast = new Cast(seconds);
        casts.put(player.getUniqueId(), cast);
        HomeMessages.info(player, seconds + "초 후 집으로 이동합니다.");
        HomeMessages.hint(player, "걷기 또는 점프 중에도 귀환이 진행됩니다.");
        HomeMessages.actionBar(player, seconds);

        UUID id = player.getUniqueId();
        cast.task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> tick(id), 20L, 20L);
    }

    /** 귀환 중이면 취소합니다. 귀환 중이 아니면 아무것도 하지 않습니다. */
    public void cancel(Player player, CancelReason reason) {
        Cast cast = casts.remove(player.getUniqueId());
        if (cast == null) {
            return;
        }
        if (cast.task != null) {
            cast.task.cancel();
        }
        if (reason == CancelReason.SILENT || !player.isOnline()) {
            return;
        }
        HomeMessages.clearActionBar(player);
        HomeMessages.error(player, reason.phrase() + " 귀환이 취소되었습니다.");
        // 스스로 한 행동이나 환경 피해면 바로 다시 사용 가능, 전투로 취소됐으면 남은 제한 시간 안내
        Duration combat = Timers.remaining(System.currentTimeMillis(), store.combatUntil(player));
        if (combat.isZero()) {
            HomeMessages.hint(player, "대기시간 없이 다시 사용할 수 있습니다.");
        } else {
            HomeMessages.hint(player, Durations.korean(combat) + " 동안 귀환할 수 없습니다.");
        }
    }

    public void cancelAll() {
        casts.values().forEach(c -> {
            if (c.task != null) {
                c.task.cancel();
            }
        });
        casts.clear();
        finishing.clear();
    }

    private void tick(UUID id) {
        Cast cast = casts.get(id);
        Player player = plugin.getServer().getPlayer(id);
        if (cast == null) {
            return;
        }
        if (player == null || !player.isOnline() || player.isDead()) {
            cancelSilently(id, cast);
            return;
        }
        if (!HomePlugin.isOverworld(player.getWorld())) {
            cancel(player, CancelReason.MOVED_AWAY);
            return;
        }
        if (cast.countdown.tick()) {
            casts.remove(id);
            cast.task.cancel();
            finish(player);
        } else {
            HomeMessages.actionBar(player, cast.countdown.secondsLeft());
        }
    }

    private void cancelSilently(UUID id, Cast cast) {
        casts.remove(id);
        if (cast.task != null) {
            cast.task.cancel();
        }
    }

    /** 카운트다운 끝: 최종 안전 검사 → 목적지 청크 로드 → 목적지 검사 → 순간이동 → 쿨타임. */
    private void finish(Player player) {
        HomeMessages.clearActionBar(player);
        HomeSettings settings = plugin.settings();

        Optional<PlayerSafety.Unsafe> unsafe = BukkitSafety.checkPlayer(player, settings.maxFallDistance());
        if (unsafe.isPresent()) {
            HomeMessages.error(player, unsafe.get().phrase() + " 귀환할 수 없습니다.");
            HomeMessages.hint(player, "대기시간 없이 다시 사용할 수 있습니다.");
            return;
        }

        Optional<HomeLocation> home = store.home(player);
        Location target = home.map(this::resolve).orElse(null);
        if (target == null) {
            HomeMessages.error(player, "집이 있는 월드를 찾을 수 없습니다.");
            return;
        }

        UUID id = player.getUniqueId();
        finishing.add(id);
        target.getWorld().getChunkAtAsync(target).whenComplete((chunk, error) -> {
            if (error != null || !player.isOnline() || player.isDead()) {
                finishing.remove(id);
                return;
            }
            if (!BukkitSafety.isSafeDestination(target)) {
                finishing.remove(id);
                HomeMessages.error(player, "집 위치가 막혀 있거나 위험해서 이동하지 않았습니다.");
                HomeMessages.hint(player, "집에 직접 가서 /sethome 으로 다시 정해 주세요.");
                return;
            }
            if (player.isInsideVehicle()) {
                player.leaveVehicle();
            }
            player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((ok, tpError) -> {
                finishing.remove(id);
                if (tpError != null || !Boolean.TRUE.equals(ok)) {
                    HomeMessages.error(player, "이동하지 못했습니다. 잠시 후 다시 시도해 주세요.");
                    return;
                }
                player.setFallDistance(0f);
                store.setReadyAt(player, System.currentTimeMillis() + plugin.settings().cooldown().toMillis());
                HomeMessages.success(player, "집으로 이동했습니다!");
            });
        });
    }

    /** 저장된 집을 Location 으로. 월드가 없거나 오버월드가 아니면 null. */
    private Location resolve(HomeLocation home) {
        World world = plugin.getServer().getWorld(home.world());
        if (world == null || !HomePlugin.isOverworld(world)) {
            return null;
        }
        return new Location(world, home.x(), home.y(), home.z(), home.yaw(), home.pitch());
    }
}
