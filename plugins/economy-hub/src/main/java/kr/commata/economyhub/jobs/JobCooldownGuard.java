package kr.commata.economyhub.jobs;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * 직업 쿨타임은 탈퇴한 뒤에만 (ECONOMY.md 5.1).
 *
 * <p>VJobs 2.2.0 은 가입할 때도 "마지막 직업 변경 시각"을 갱신해서, 첫 직업에 가입하자마자 두 번째 직업 가입이 막힙니다.
 * VJobs 는 {@code jobs.cooldown.instant} 권한이 있으면 쿨타임을 0초로 보므로, 마지막 탈퇴 후 정해진 시간이 지난
 * 플레이어(또는 탈퇴한 적 없는 플레이어)에게 이 권한을 붙여 줍니다. 탈퇴 직후에는 떼어 내서 VJobs 쿨타임(탈퇴 시각부터
 * 3600초)이 그대로 걸립니다.
 *
 * <p>탈퇴 시각은 {@link LeaveTimeStore}(fsync 하는 파일)와 플레이어 PDC 에 함께 쓰고 둘 중 늦은 값을 씁니다. PDC 만 쓰면
 * VJobs 가 탈퇴를 저장한 뒤 강제 종료됐을 때 탈퇴 시각이 사라져 쿨타임이 풀립니다. 파일을 읽지 못한 기동에서는 아무에게도
 * 면제 권한을 주지 않습니다(VJobs 기본 동작: 가입·탈퇴마다 쿨타임).
 */
public final class JobCooldownGuard implements Listener {

    public static final String PERMISSION = "jobs.cooldown.instant";

    private final Plugin plugin;
    private final NamespacedKey lastLeaveKey;
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();
    private final LongSupplier cooldownMs;
    private final LeaveTimeStore leaves;

    /** @param cooldownMs VJobs 설정에서 매번 읽어 오는 탈퇴 후 대기 시간. EconomyHub 는 따로 값을 두지 않습니다. */
    public JobCooldownGuard(Plugin plugin, LongSupplier cooldownMs) {
        this.plugin = plugin;
        this.lastLeaveKey = new NamespacedKey(plugin, "last_job_leave");
        this.cooldownMs = cooldownMs;
        LeaveTimeStore opened = null;
        try {
            opened = LeaveTimeStore.open(plugin.getDataFolder().toPath().resolve("job-leaves.tsv"));
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "job-leaves.tsv 를 읽지 못해 이번 기동에서는 탈퇴 후 쿨타임 면제를 끕니다"
                    + " (VJobs 기본 쿨타임이 가입·탈퇴마다 걸림). 파일은 그대로 둡니다.", e);
        }
        this.leaves = opened;
    }

    /** 탈퇴 시각 파일을 읽지 못했으면 이유, 정상이면 null. */
    public String problem() {
        return leaves == null ? "job-leaves.tsv 를 읽지 못함, 면제 꺼짐" : null;
    }

    public long cooldownMs() {
        return cooldownMs.getAsLong();
    }

    /** VJobs JobLeaveEvent(MONITOR, 취소되지 않은 것만)에서 호출. VJobs 가 탈퇴를 DB 에 저장하기 전에 파일에 반영됨. */
    public void onJobLeave(Player player) {
        long now = System.currentTimeMillis();
        player.getPersistentDataContainer().set(lastLeaveKey, PersistentDataType.LONG, now);
        if (leaves != null) {
            try {
                leaves.record(player.getUniqueId(), now);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, player.getName() + " 탈퇴 시각 파일 저장 실패 (PDC 에만 기록)", e);
            }
        }
        refresh(player);
    }

    public long lastLeaveMs(Player player) {
        Long value = player.getPersistentDataContainer().get(lastLeaveKey, PersistentDataType.LONG);
        long pdc = value == null ? 0L : value;
        return leaves == null ? pdc : Math.max(pdc, leaves.get(player.getUniqueId()));
    }

    public long remainingMs(Player player) {
        return CooldownPolicy.remainingMs(lastLeaveMs(player), System.currentTimeMillis(), cooldownMs());
    }

    public void refresh(Player player) {
        boolean exempt = leaves != null
                && CooldownPolicy.exempt(lastLeaveMs(player), System.currentTimeMillis(), cooldownMs());
        PermissionAttachment attachment = attachments.get(player.getUniqueId());
        if (exempt) {
            if (attachment == null) {
                attachment = player.addAttachment(plugin);
                attachments.put(player.getUniqueId(), attachment);
            }
            attachment.setPermission(PERMISSION, true);
        } else if (attachment != null) {
            attachment.unsetPermission(PERMISSION);
        }
    }

    public void refreshAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            refresh(player);
        }
    }

    public void shutdown() {
        for (PermissionAttachment attachment : attachments.values()) {
            try {
                attachment.remove();
            } catch (IllegalArgumentException ignored) {
                // 이미 떨어져 나간 경우 (퇴장 직후 등)
            }
        }
        attachments.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        PermissionAttachment attachment = attachments.remove(event.getPlayer().getUniqueId());
        if (attachment != null) {
            try {
                attachment.remove();
            } catch (IllegalArgumentException ignored) {
                // 퇴장 처리 중 이미 해제됨
            }
        }
    }
}
