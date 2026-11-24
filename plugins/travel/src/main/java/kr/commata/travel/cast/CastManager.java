package kr.commata.travel.cast;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import kr.commata.common.time.Durations;
import kr.commata.travel.Chat;
import kr.commata.travel.TravelPlugin;
import kr.commata.travel.logic.CancelReason;
import kr.commata.travel.logic.Countdown;
import kr.commata.travel.logic.PlayerSafety;
import kr.commata.travel.logic.Timers;
import kr.commata.travel.storage.TravelStore;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * /home, /tpa 시전(카운트다운)을 관리합니다. 한 사람은 한 번에 하나만 시전할 수 있습니다.
 * 모든 메서드는 메인 스레드에서 호출합니다.
 */
public final class CastManager {

    private static final class Cast {
        final CastJob job;
        final Countdown countdown;
        final UUID world;
        BukkitTask task;

        Cast(CastJob job, UUID world) {
            this.job = job;
            this.countdown = new Countdown(job.seconds());
            this.world = world;
        }
    }

    private final TravelPlugin plugin;
    private final TravelStore store;
    private final Map<UUID, Cast> casts = new HashMap<>();
    /** 카운트다운이 끝나고 청크 로드·순간이동을 기다리는 플레이어 (그 사이 중복 시전 방지). */
    private final Set<UUID> finishing = new HashSet<>();

    public CastManager(TravelPlugin plugin, TravelStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    public boolean isCasting(Player player) {
        return casts.containsKey(player.getUniqueId());
    }

    /** 시전 중이거나 순간이동을 마무리하는 중. */
    public boolean isBusy(Player player) {
        return isCasting(player) || finishing.contains(player.getUniqueId());
    }

    public Optional<CastJob> job(Player player) {
        return Optional.ofNullable(casts.get(player.getUniqueId())).map(c -> c.job);
    }

    /** 남은 전투 제한. /home, /tpa 가 같은 값을 씁니다. */
    public Duration combatLeft(Player player) {
        return Timers.remaining(System.currentTimeMillis(), store.combatUntil(player));
    }

    /**
     * 시전 시작. 전투·쿨타임 등 작업별 조건은 호출하는 쪽에서 먼저 확인합니다.
     *
     * @param intro 시작할 때 보낼 안내 (예: "7초 후 집으로 이동합니다.")
     */
    public void start(Player player, CastJob job, String intro) {
        if (isBusy(player)) {
            job.chat().error(player, "이미 " + job.noun() + " 중입니다.");
            return;
        }
        if (job.seconds() <= 0) {
            finish(player, job);
            return;
        }
        Cast cast = new Cast(job, player.getWorld().getUID());
        casts.put(player.getUniqueId(), cast);
        job.chat().info(player, intro);
        job.chat().hint(player, "걷기 또는 점프 중에도 " + job.noun() + "이 진행됩니다.");
        Chat.actionBar(player, job.actionBar(job.seconds()));

        UUID id = player.getUniqueId();
        cast.task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> tick(id), 20L, 20L);
    }

    /** 시전 중이면 취소합니다. 시전 중이 아니면 아무것도 하지 않습니다. */
    public void cancel(Player player, CancelReason reason) {
        Cast cast = removeCast(player.getUniqueId());
        if (cast == null) {
            return;
        }
        String why = CastJob.cancelled(reason, cast.job.noun());
        cast.job.onAborted(player, why);
        if (reason == CancelReason.SILENT || !player.isOnline()) {
            return;
        }
        Chat.clearActionBar(player);
        cast.job.chat().error(player, why);
        // 스스로 한 행동이나 환경 피해면 바로 다시 사용 가능, 전투로 취소됐으면 남은 제한 시간 안내
        Duration combat = combatLeft(player);
        if (combat.isZero()) {
            cast.job.chat().hint(player, "대기시간 없이 다시 사용할 수 있습니다.");
        } else {
            cast.job.chat().hint(player, Durations.korean(combat) + " 동안 " + cast.job.noun() + "할 수 없습니다.");
        }
    }

    /** 이유를 직접 적어 취소 (예: 상대가 접속을 종료함). */
    public void abort(Player player, String message) {
        Cast cast = removeCast(player.getUniqueId());
        if (cast == null) {
            return;
        }
        cast.job.onAborted(player, message);
        if (player.isOnline()) {
            Chat.clearActionBar(player);
            cast.job.chat().error(player, message);
        }
    }

    /** 이 플레이어와 관련된 다른 사람의 시전을 모두 취소 (예: /tpa 상대가 로그아웃). */
    public void abortInvolving(UUID player, String message) {
        List<UUID> affected = new ArrayList<>();
        casts.forEach((id, cast) -> {
            if (cast.job.involves(player)) {
                affected.add(id);
            }
        });
        for (UUID id : affected) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null) {
                abort(p, message);
            } else {
                removeCast(id);
            }
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

    private Cast removeCast(UUID id) {
        Cast cast = casts.remove(id);
        if (cast != null && cast.task != null) {
            cast.task.cancel();
        }
        return cast;
    }

    private void tick(UUID id) {
        Cast cast = casts.get(id);
        if (cast == null) {
            return;
        }
        Player player = plugin.getServer().getPlayer(id);
        if (player == null || !player.isOnline() || player.isDead()) {
            removeCast(id);
            cast.job.onAborted(player, "");
            return;
        }
        if (!player.getWorld().getUID().equals(cast.world)) {
            cancel(player, CancelReason.MOVED_AWAY); // 시전 중 포털 등으로 차원이 바뀜
            return;
        }
        if (cast.countdown.tick()) {
            removeCast(id);
            finish(player, cast.job);
        } else {
            Chat.actionBar(player, cast.job.actionBar(cast.countdown.secondsLeft()));
        }
    }

    /** 카운트다운 끝: 최종 안전 검사 → 목적지 → 청크 로드 → 블록 검사 → 순간이동 → 작업별 마무리. */
    private void finish(Player player, CastJob job) {
        Chat.clearActionBar(player);

        Optional<PlayerSafety.Unsafe> unsafe =
                BukkitSafety.checkPlayer(player, plugin.settings().maxFallDistance());
        if (unsafe.isPresent()) {
            fail(player, job, unsafe.get().phrase() + " " + job.noun() + "할 수 없습니다.");
            return;
        }

        CastJob.Destination dest = job.destination(player);
        if (dest.error().isPresent()) {
            fail(player, job, dest.error().get());
            return;
        }
        Location target = dest.location();

        UUID id = player.getUniqueId();
        finishing.add(id);
        target.getWorld().getChunkAtAsync(target).whenComplete((chunk, error) -> {
            if (error != null || !player.isOnline() || player.isDead()) {
                finishing.remove(id);
                job.onAborted(player, "");
                return;
            }
            if (!BukkitSafety.isSafeDestination(target)) {
                finishing.remove(id);
                fail(player, job, dest.blockedMessage());
                return;
            }
            if (player.isInsideVehicle()) {
                player.leaveVehicle();
            }
            player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((ok, tpError) -> {
                finishing.remove(id);
                if (tpError != null || !Boolean.TRUE.equals(ok)) {
                    fail(player, job, "이동하지 못했습니다. 잠시 후 다시 시도해 주세요.");
                    return;
                }
                player.setFallDistance(0f);
                job.onSuccess(player);
            });
        });
    }

    private void fail(Player player, CastJob job, String message) {
        job.onAborted(player, message);
        if (player.isOnline()) {
            job.chat().error(player, message);
            job.chat().hint(player, "쿨타임 없이 다시 시도할 수 있습니다.");
        }
    }
}
