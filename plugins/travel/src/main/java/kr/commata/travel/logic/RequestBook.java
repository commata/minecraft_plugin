package kr.commata.travel.logic;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kr.commata.travel.model.TeleportRequest;

/**
 * 대기 중인 /tpa 요청 목록. Bukkit 없이 동작하므로 단위 테스트할 수 있습니다.
 * <ul>
 *   <li>한 사람이 보낼 수 있는 요청은 한 번에 하나. 새로 보내면 이전 요청은 취소됩니다.</li>
 *   <li>받는 쪽은 여러 사람의 요청을 동시에 받을 수 있습니다.</li>
 *   <li>같은 상대에게는 {@code resendInterval} 안에 다시 보낼 수 없습니다 (거절·취소 후에도).</li>
 * </ul>
 * 메인 스레드에서만 사용합니다.
 */
public final class RequestBook {

    public sealed interface SendResult {
        /** 보냄. {@code replaced} 는 새 요청 때문에 취소된 이전 요청. */
        record Sent(TeleportRequest request, Optional<TeleportRequest> replaced) implements SendResult {
        }

        /** 같은 상대에게 너무 빨리 다시 보냄. */
        record TooSoon(Duration retryIn) implements SendResult {
        }

        /** 같은 상대에게 보낸 요청이 아직 대기 중. */
        record AlreadyPending(TeleportRequest request) implements SendResult {
        }
    }

    private record Pair(UUID from, UUID to) {
    }

    /** 보낸 사람 → 요청 */
    private final Map<UUID, TeleportRequest> outgoing = new HashMap<>();
    private final Map<Pair, Long> lastSent = new HashMap<>();

    public SendResult send(UUID from, String fromName, UUID to, String toName,
                           long now, Duration timeout, Duration resendInterval) {
        if (from.equals(to)) {
            throw new IllegalArgumentException("자기 자신에게는 요청할 수 없습니다");
        }
        TeleportRequest current = outgoing.get(from);
        if (current != null && !current.isExpired(now) && current.target().equals(to)) {
            return new SendResult.AlreadyPending(current);
        }
        Long last = lastSent.get(new Pair(from, to));
        if (last != null && now - last < resendInterval.toMillis()) {
            return new SendResult.TooSoon(Duration.ofMillis(last + resendInterval.toMillis() - now));
        }
        TeleportRequest request = new TeleportRequest(from, fromName, to, toName, now, now + timeout.toMillis());
        Optional<TeleportRequest> replaced = Optional.ofNullable(outgoing.put(from, request))
                .filter(r -> !r.isExpired(now));
        lastSent.put(new Pair(from, to), now);
        return new SendResult.Sent(request, replaced);
    }

    public Optional<TeleportRequest> outgoing(UUID from) {
        return Optional.ofNullable(outgoing.get(from));
    }

    /** 받은 요청, 최근 것부터. */
    public List<TeleportRequest> incoming(UUID to) {
        return outgoing.values().stream()
                .filter(r -> r.target().equals(to))
                .sorted(Comparator.comparingLong(TeleportRequest::createdAt).reversed())
                .toList();
    }

    /**
     * 받은 요청 하나를 꺼냅니다 (수락·거절). {@code from} 이 없으면 가장 최근 요청.
     * 이름으로 고를 때는 대소문자를 무시합니다.
     */
    public Optional<TeleportRequest> take(UUID to, Optional<String> fromName) {
        Optional<TeleportRequest> found = find(to, fromName);
        found.ifPresent(r -> outgoing.remove(r.requester()));
        return found;
    }

    /** 이 요청을 목록에서 뺍니다. 그사이 바뀌었으면 false. */
    public boolean remove(TeleportRequest request) {
        return outgoing.remove(request.requester(), request);
    }

    /** 받은 요청 하나를 찾습니다 (빼지 않음). {@code fromName} 이 없으면 가장 최근 요청. */
    public Optional<TeleportRequest> find(UUID to, Optional<String> fromName) {
        return incoming(to).stream()
                .filter(r -> fromName.isEmpty() || r.requesterName().equalsIgnoreCase(fromName.get()))
                .findFirst();
    }

    /** 보낸 요청 취소. */
    public Optional<TeleportRequest> cancelOutgoing(UUID from) {
        return Optional.ofNullable(outgoing.remove(from));
    }

    /** 만료된 요청을 빼서 돌려줍니다. 오래된 재요청 기록도 정리합니다. */
    public List<TeleportRequest> expire(long now, Duration resendInterval) {
        List<TeleportRequest> expired = new ArrayList<>();
        outgoing.values().removeIf(r -> {
            if (r.isExpired(now)) {
                expired.add(r);
                return true;
            }
            return false;
        });
        lastSent.values().removeIf(t -> now - t >= resendInterval.toMillis());
        return expired;
    }

    /** 이 플레이어가 보냈거나 받은 요청을 모두 빼서 돌려줍니다 (로그아웃 등). */
    public List<TeleportRequest> removeInvolving(UUID player) {
        List<TeleportRequest> removed = new ArrayList<>();
        outgoing.values().removeIf(r -> {
            if (r.involves(player)) {
                removed.add(r);
                return true;
            }
            return false;
        });
        return removed;
    }

    /** 이 플레이어가 받은 요청만 모두 빼서 돌려줍니다 (/tpatoggle 로 끔). */
    public List<TeleportRequest> removeIncoming(UUID to) {
        List<TeleportRequest> removed = new ArrayList<>(incoming(to));
        removed.forEach(r -> outgoing.remove(r.requester()));
        return removed;
    }

    public List<TeleportRequest> all() {
        return List.copyOf(outgoing.values());
    }

    public void clear() {
        outgoing.clear();
        lastSent.clear();
    }
}
