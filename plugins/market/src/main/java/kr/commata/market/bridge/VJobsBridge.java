package kr.commata.market.bridge;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.plugin.Plugin;

/**
 * VJobs 2.2.0 공개 API·AFK 추적기를 리플렉션으로 연동합니다 (ECONOMY.md 14.2, 14.8, AGENTS.md 3.3).
 *
 * <p>VJobs 가 없거나 시그니처가 바뀌어도 예외로 죽지 않고 해당 기능만 비활성화·경고합니다.
 */
public final class VJobsBridge {

    private static final String LEAVE_EVENT = "com.vjobs.api.event.JobLeaveEvent";
    private static final Listener OWNER = new Listener() { };

    private final Plugin market;
    private final Plugin vjobs;
    private boolean afkWarned;

    public VJobsBridge(Plugin market, Plugin vjobs) {
        this.market = market;
        this.vjobs = vjobs;
    }

    public boolean available() {
        return vjobs != null && vjobs.isEnabled();
    }

    /**
     * 플레이어가 지금 가입한 직업 ID 집합 ({@code VJobsProvider.get().getPlayer(uuid).getJobs().keySet()}).
     * VJobs 가 없거나 아직 로딩 중이면 {@code null}(이때는 탈퇴 이관 보정을 실행하지 않아 오삭제 방지).
     */
    public Set<String> joinedJobIds(UUID uuid) {
        if (!available()) {
            return null;
        }
        try {
            Object apiInstance = api("com.vjobs.api.VJobsProvider").getMethod("get").invoke(null);
            Object player = unwrap(api("com.vjobs.api.VJobsApi").getMethod("getPlayer", UUID.class)
                    .invoke(apiInstance, uuid));
            if (player == null) {
                return null;
            }
            Object jobs = api("com.vjobs.api.JobPlayer").getMethod("getJobs").invoke(player);
            if (!(jobs instanceof Map<?, ?> map)) {
                return null;
            }
            Set<String> out = new LinkedHashSet<>();
            for (Object key : map.keySet()) {
                if (key instanceof String id) {
                    out.add(id);
                }
            }
            return out;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * VJobs 2.2.0 {@code VJobsPlugin.getAfkTracker().isAfk(uuid)} (JAR 직접 확인).
     * 낚시 AFK 판정 중 판매 가능량 기록 제외에 사용 (ECONOMY.md 14.2).
     */
    public boolean isAfk(UUID uuid) {
        if (!available()) {
            return false;
        }
        try {
            Object tracker = vjobs.getClass().getMethod("getAfkTracker").invoke(vjobs);
            if (tracker == null) {
                return false;
            }
            Object result = tracker.getClass().getMethod("isAfk", UUID.class).invoke(tracker, uuid);
            return Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!afkWarned) {
                afkWarned = true;
                market.getLogger().warning("VJobs AFK 상태 조회 실패 (낚시 AFK 제외 비활성): " + e);
            }
            return false;
        }
    }

    /**
     * VJobs {@code JobLeaveEvent} MONITOR 리스너 등록 (ECONOMY.md 14.2, 14.8).
     *
     * @return 실패 시 사유, 성공 시 null
     */
    public String listenLeave(BiConsumer<Player, String> handler) {
        if (!available()) {
            return "VJobs 플러그인이 없음";
        }
        Class<? extends Event> eventType;
        try {
            Class<?> raw = Class.forName(LEAVE_EVENT, true, vjobs.getClass().getClassLoader());
            if (!Event.class.isAssignableFrom(raw)) {
                return LEAVE_EVENT + " 가 Bukkit 이벤트가 아님";
            }
            eventType = raw.asSubclass(Event.class);
        } catch (ClassNotFoundException | LinkageError e) {
            return LEAVE_EVENT + " 를 찾지 못함 (" + e + ")";
        }
        market.getServer().getPluginManager().registerEvent(eventType, OWNER, EventPriority.MONITOR, (l, event) -> {
            if (eventType.isInstance(event) && event instanceof PlayerEvent playerEvent) {
                String jobId = jobId(event);
                if (jobId != null) {
                    handler.accept(playerEvent.getPlayer(), jobId);
                }
            }
        }, market, true);
        return null;
    }

    private String jobId(Event event) {
        try {
            Object job = event.getClass().getMethod("getJob").invoke(event);
            return job == null ? null : (String) api("com.vjobs.api.Job").getMethod("getId").invoke(job);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private Class<?> api(String name) throws ClassNotFoundException {
        return Class.forName(name, true, vjobs.getClass().getClassLoader());
    }

    private static Object unwrap(Object value) {
        return value instanceof Optional<?> optional ? optional.orElse(null) : value;
    }
}
