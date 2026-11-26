package kr.commata.economyhub.jobs;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
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
    public static final String LEVEL_UP_EVENT = "com.vjobs.api.event.JobLevelUpEvent";

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

    /**
     * JobJoinEvent 시점에 VJobs 가 곧 가입시킬 진행도: 보관된 진행도(PlayerJobData.getArchivedProgress), 없으면 Lv1·XP 0.
     * VJobs 2.2.0 JobManager.join 이 이벤트 직후 이 값으로 putProgress 함(JAR 확인). 내부 클래스라 읽지 못하면 null.
     */
    public Progress joinBase(UUID uuid, String jobId) {
        try {
            Object manager = vjobs.getClass().getMethod("getJobManager").invoke(vjobs);
            Object cache = manager.getClass().getMethod("getCache").invoke(manager);
            Object data = cache instanceof java.util.Map<?, ?> map ? map.get(uuid) : null;
            if (data == null) {
                return null;
            }
            Object archived = data.getClass().getMethod("getArchivedProgress", String.class).invoke(data, jobId);
            if (archived == null) {
                return new Progress(1, 0.0);
            }
            int lv = ((Number) archived.getClass().getMethod("getLevel").invoke(archived)).intValue();
            double xp = ((Number) archived.getClass().getMethod("getXp").invoke(archived)).doubleValue();
            return new Progress(lv, xp);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * 지금 가입 중인 직업과 레벨 (VJobsApi.getPlayer(uuid).getJobs()). VJobs 가 이 플레이어 데이터를 아직 읽지 않았거나
     * 읽지 못하면 null.
     */
    public Map<String, Progress> joinedJobs(UUID uuid) {
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
            Class<?> levelType = api("com.vjobs.api.JobLevel");
            Method levelOf = levelType.getMethod("level");
            Method xpOf = levelType.getMethod("xp");
            Map<String, Progress> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object level = entry.getValue();
                if (entry.getKey() instanceof String id && level != null) {
                    result.put(id, new Progress(((Number) levelOf.invoke(level)).intValue(),
                            ((Number) xpOf.invoke(level)).doubleValue()));
                }
            }
            return result;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * VJobsApi.joinJob(player, jobId). 결과 이름(SUCCESS, UNKNOWN_JOB, ALREADY_JOINED, LIMIT_REACHED, COOLDOWN,
     * CANCELLED, JAR 확인), 호출하지 못하면 null. 쿨타임·최대 직업 수는 VJobs 가 판단하고 JobJoinEvent 도 그대로 발생합니다.
     */
    public String joinJob(Player player, String jobId) {
        try {
            Object apiInstance = api("com.vjobs.api.VJobsProvider").getMethod("get").invoke(null);
            Object result = api("com.vjobs.api.VJobsApi").getMethod("joinJob", Player.class, String.class)
                    .invoke(apiInstance, player, jobId);
            return result == null ? null : result.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().warning("VJobs joinJob 호출 실패: " + e);
            return null;
        }
    }

    /** VJobsApi.leaveJob(player, jobId). 탈퇴했으면 true, 거부·취소면 false, 호출하지 못하면 null. */
    public Boolean leaveJob(Player player, String jobId) {
        try {
            Object apiInstance = api("com.vjobs.api.VJobsProvider").getMethod("get").invoke(null);
            Object result = api("com.vjobs.api.VJobsApi").getMethod("leaveJob", Player.class, String.class)
                    .invoke(apiInstance, player, jobId);
            return result instanceof Boolean ok ? ok : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().warning("VJobs leaveJob 호출 실패: " + e);
            return null;
        }
    }

    /** VJobs config.yml jobs-limit.default (동시 가입 수, ECONOMY.md 5.1). */
    public int jobLimit() {
        return Math.max(1, vjobs.getConfig().getInt("jobs-limit.default", 2));
    }

    /** VJobs config.yml 의 jobs.<job>.actions.<action>.<key>.money. 없으면 null. 보상 요약 표시용. */
    public Double rewardMoney(String jobId, String actionAndKey) {
        String path = "jobs." + jobId + ".actions." + actionAndKey + ".money";
        return vjobs.getConfig().isSet(path) ? vjobs.getConfig().getDouble(path) : null;
    }

    /** VJobs 가 이 플레이어 데이터를 다 읽었는지 (VJobsApi.getPlayer 는 읽기 전이면 비어 있음, JAR 확인). */
    public boolean loaded(UUID uuid) {
        try {
            Object apiInstance = api("com.vjobs.api.VJobsProvider").getMethod("get").invoke(null);
            return unwrap(api("com.vjobs.api.VJobsApi").getMethod("getPlayer", UUID.class).invoke(apiInstance, uuid)) != null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** VJobsApi.xpForLevel(level): 그 레벨에서 다음 레벨까지 필요한 XP. 읽지 못하면 NaN. */
    public double xpForLevel(int level) {
        try {
            Object apiInstance = api("com.vjobs.api.VJobsProvider").getMethod("get").invoke(null);
            return ((Number) api("com.vjobs.api.VJobsApi").getMethod("xpForLevel", int.class)
                    .invoke(apiInstance, level)).doubleValue();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Double.NaN;
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

    /** VJobs 가 실제로 쓰는 탈퇴 설정 (ConfigManager 값, /jobs reload 반영). */
    public record LeaveSettings(double levelLossPercent, boolean keepProgress) { }

    /**
     * VJobsPlugin.getConfigManager().getLeavePenaltyPercent() / isLeaveKeepArchive(). 공개 API 에 없어 내부 클래스를 읽음
     * (VJobs 2.2.0 JAR 확인, VERSIONS.md 에 고정한 버전). 읽지 못하면 null.
     */
    public LeaveSettings leaveSettings() {
        try {
            Object config = vjobs.getClass().getMethod("getConfigManager").invoke(vjobs);
            double loss = ((Number) config.getClass().getMethod("getLeavePenaltyPercent").invoke(config)).doubleValue();
            boolean keep = (Boolean) config.getClass().getMethod("isLeaveKeepArchive").invoke(config);
            return new LeaveSettings(loss, keep);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * VJobs DB 에 저장된 레벨·XP (VJobsPlugin.getJobManager().getRepository().load(uuid).getProgress(job)).
     * 메모리가 아니라 DB 를 새로 읽으므로 비동기 스레드에서 부릅니다. 가입 중이 아니거나 읽지 못하면 null.
     * (load 는 SQL 오류를 로그만 남기고 빈 데이터를 돌려주므로 오류도 null 로 보임 → 기록을 지우지 않는 쪽)
     */
    public Progress storedProgress(UUID uuid, String jobId) {
        try {
            Object manager = vjobs.getClass().getMethod("getJobManager").invoke(vjobs);
            Object repository = manager.getClass().getMethod("getRepository").invoke(manager);
            Object data = repository.getClass().getMethod("load", UUID.class).invoke(repository, uuid);
            if (data == null) {
                return null;
            }
            Object progress = data.getClass().getMethod("getProgress", String.class).invoke(data, jobId);
            if (progress == null) {
                return null;
            }
            int lv = ((Number) progress.getClass().getMethod("getLevel").invoke(progress)).intValue();
            double xp = ((Number) progress.getClass().getMethod("getXp").invoke(progress)).doubleValue();
            return new Progress(lv, xp);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** storedProgress 를 쓸 수 있는지 (메서드가 있는지만 확인, DB 는 읽지 않음). 못 쓰면 이유. */
    public String storedProgressProblem() {
        try {
            Class<?> manager = vjobs.getClass().getMethod("getJobManager").getReturnType();
            Class<?> repository = manager.getMethod("getRepository").getReturnType();
            Class<?> data = repository.getMethod("load", UUID.class).getReturnType();
            Class<?> progress = data.getMethod("getProgress", String.class).getReturnType();
            progress.getMethod("getLevel");
            progress.getMethod("getXp");
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return "VJobs 저장소를 읽을 수 없음 (" + e + ")";
        }
    }

    private Class<?> api(String name) throws ClassNotFoundException {
        return Class.forName(name, true, vjobs.getClass().getClassLoader());
    }

    private static Object unwrap(Object value) {
        return value instanceof Optional<?> optional ? optional.orElse(null) : value;
    }
}
