package kr.commata.common.cooldown;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 플레이어별, 키(스킬 ID 등)별 쿨다운 관리. 메모리에만 저장되며 서버 재시작 시 초기화됩니다.
 */
public final class Cooldowns {

    private record Key(UUID player, String id) {
    }

    private final Map<Key, Instant> readyAt = new ConcurrentHashMap<>();
    private final Clock clock;

    public Cooldowns() {
        this(Clock.systemUTC());
    }

    public Cooldowns(Clock clock) {
        this.clock = clock;
    }

    /** 쿨다운 중이 아니면 쿨다운을 시작하고 true, 쿨다운 중이면 false. */
    public boolean tryUse(UUID player, String id, Duration cooldown) {
        Instant now = clock.instant();
        Key key = new Key(player, id);
        Instant ready = readyAt.get(key);
        if (ready != null && now.isBefore(ready)) {
            return false;
        }
        readyAt.put(key, now.plus(cooldown));
        return true;
    }

    /** 남은 쿨다운. 쿨다운 중이 아니면 비어 있음. */
    public Optional<Duration> remaining(UUID player, String id) {
        Instant ready = readyAt.get(new Key(player, id));
        Instant now = clock.instant();
        if (ready == null || !now.isBefore(ready)) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(now, ready));
    }

    public void clear(UUID player) {
        readyAt.keySet().removeIf(k -> k.player().equals(player));
    }
}
