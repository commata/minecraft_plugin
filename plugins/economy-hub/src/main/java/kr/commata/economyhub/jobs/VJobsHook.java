package kr.commata.economyhub.jobs;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.plugin.Plugin;

/**
 * VJobs 2.2.0 공개 API(com.vjobs.api.event.*)를 리플렉션으로 씁니다. VJobs 는 공개 Maven 저장소가 없어서
 * 컴파일 의존성을 두지 않습니다. 이벤트 이름이 바뀌면 등록이 실패하고 로그에 경고가 남습니다.
 */
public final class VJobsHook {

    public static final String LEAVE_EVENT = "com.vjobs.api.event.JobLeaveEvent";
    public static final String PAYOUT_EVENT = "com.vjobs.api.event.JobPayoutEvent";
    public static final String JOIN_EVENT = "com.vjobs.api.event.JobJoinEvent";

    /** 핸들러를 한 객체에 묶어 두면 플러그인을 끌 때 Bukkit 이 한꺼번에 해제함. */
    private static final Listener OWNER = new Listener() { };

    private final Plugin plugin;
    private final Plugin vjobs;

    public VJobsHook(Plugin plugin, Plugin vjobs) {
        this.plugin = plugin;
        this.vjobs = vjobs;
    }

    /**
     * VJobs 이벤트 클래스에 핸들러를 등록. 취소된 이벤트는 받지 않음(ignoreCancelled).
     *
     * @return 등록에 실패하면 이유, 성공하면 null
     */
    public String listen(String className, EventPriority priority, Consumer<Event> handler) {
        Class<? extends Event> type;
        try {
            Class<?> raw = Class.forName(className, true, vjobs.getClass().getClassLoader());
            if (!Event.class.isAssignableFrom(raw)) {
                return className + " 가 Bukkit 이벤트가 아님";
            }
            type = raw.asSubclass(Event.class);
        } catch (ClassNotFoundException | LinkageError e) {
            return className + " 를 찾지 못함 (" + e + ")";
        }
        Class<? extends Event> eventType = type;
        plugin.getServer().getPluginManager().registerEvent(eventType, OWNER, priority, (listener, event) -> {
            if (eventType.isInstance(event)) {
                handler.accept(event);
            }
        }, plugin, true);
        return null;
    }

    /** JobPlayerEvent 는 PlayerEvent 를 상속함. */
    public static Player player(Event event) {
        return event instanceof PlayerEvent playerEvent ? playerEvent.getPlayer() : null;
    }

    /** JobPayoutEvent.getActionType() 의 이름 (BREAK, HARVEST, ...). 읽지 못하면 null. */
    public static String actionType(Event event) {
        try {
            Method method = event.getClass().getMethod("getActionType");
            Object value = method.invoke(event);
            return value == null ? null : value.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** 현재 직업 레벨과 그 레벨에서 쌓은 XP. */
    public record Progress(int level, double xp) { }

    /** JobPlayerEvent.getJob().getId(). 읽지 못하면 null. */
    public String jobId(Event event) {
        try {
            Object job = event.getClass().getMethod("getJob").invoke(event);
            return job == null ? null : (String) api("com.vjobs.api.Job").getMethod("getId").invoke(job);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * VJobsProvider.get().getPlayer(uuid).getJob(jobId) 의 레벨·XP. 가입하지 않은 직업이거나 읽지 못하면 null.
     * 인터페이스의 Method 로 호출해야 구현 클래스가 public 이 아니어도 됨.
     */
    public Progress progress(UUID uuid, String jobId) {
        try {
            Object apiInstance = api("com.vjobs.api.VJobsProvider").getMethod("get").invoke(null);
            Object player = unwrap(api("com.vjobs.api.VJobsApi").getMethod("getPlayer", UUID.class)
                    .invoke(apiInstance, uuid));
            if (player == null) {
                return null;
            }
            Object level = unwrap(api("com.vjobs.api.JobPlayer").getMethod("getJob", String.class)
                    .invoke(player, jobId));
            if (level == null) {
                return null;
            }
            Class<?> levelType = api("com.vjobs.api.JobLevel");
            int lv = (Integer) levelType.getMethod("level").invoke(level);
            double xp = (Double) levelType.getMethod("xp").invoke(level);
            return new Progress(lv, xp);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** VJobsApi.giveXp(player, jobId, xp). 배율 없이 그대로 더함(JobManager.giveXp, JAR 확인). */
    public boolean giveXp(Player player, String jobId, double xp) {
        try {
            Object apiInstance = api("com.vjobs.api.VJobsProvider").getMethod("get").invoke(null);
            api("com.vjobs.api.VJobsApi").getMethod("giveXp", Player.class, String.class, double.class)
                    .invoke(apiInstance, player, jobId, xp);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().warning("VJobs giveXp 실패 (" + jobId + "): " + e);
            return false;
        }
    }

    private Class<?> api(String name) throws ClassNotFoundException {
        return Class.forName(name, true, vjobs.getClass().getClassLoader());
    }

    private static Object unwrap(Object value) {
        return value instanceof Optional<?> optional ? optional.orElse(null) : value;
    }
}
