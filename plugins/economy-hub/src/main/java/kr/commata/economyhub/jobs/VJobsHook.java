package kr.commata.economyhub.jobs;

import java.lang.reflect.Method;
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
}
