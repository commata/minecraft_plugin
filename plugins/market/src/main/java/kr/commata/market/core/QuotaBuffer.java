package kr.commata.market.core;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 직접 채집·사냥·낚시로 얻은 판매 가능량을 메모리에 모아 20틱마다 한 트랜잭션으로 반영하기 위한 버퍼
 * (ECONOMY.md 14.2).
 *
 * <p>Bukkit에 의존하지 않아 단위 테스트로 합산·플레이어별 추출·실패 시 복원을 검증합니다.
 */
public final class QuotaBuffer {

    public record Key(UUID uuid, String item, boolean hasJob) {
    }

    private final Map<Key, Long> pending = new LinkedHashMap<>();

    public void add(UUID uuid, String item, long amount, boolean hasJob) {
        if (uuid == null || item == null || item.isBlank() || amount <= 0) {
            return;
        }
        pending.merge(new Key(uuid, item, hasJob), amount, Long::sum);
    }

    public boolean isEmpty() {
        return pending.isEmpty();
    }

    public int size() {
        return pending.size();
    }

    /** 버퍼 전체를 꺼내고 비웁니다. */
    public Map<Key, Long> drainAll() {
        if (pending.isEmpty()) {
            return Map.of();
        }
        Map<Key, Long> out = new LinkedHashMap<>(pending);
        pending.clear();
        return out;
    }

    /** 특정 플레이어의 항목만 꺼내 버퍼에서 제거합니다. */
    public Map<Key, Long> drainPlayer(UUID uuid) {
        if (uuid == null || pending.isEmpty()) {
            return Map.of();
        }
        Map<Key, Long> out = new LinkedHashMap<>();
        Iterator<Map.Entry<Key, Long>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, Long> entry = it.next();
            if (uuid.equals(entry.getKey().uuid())) {
                out.put(entry.getKey(), entry.getValue());
                it.remove();
            }
        }
        return out;
    }

    /** DB 반영 실패 시 꺼냈던 수량을 버퍼에 다시 합산해 되돌려 넣습니다. */
    public void restore(Map<Key, Long> failed) {
        if (failed == null || failed.isEmpty()) {
            return;
        }
        for (Map.Entry<Key, Long> entry : failed.entrySet()) {
            long amount = entry.getValue();
            if (amount > 0) {
                pending.merge(entry.getKey(), amount, Long::sum);
            }
        }
    }
}
