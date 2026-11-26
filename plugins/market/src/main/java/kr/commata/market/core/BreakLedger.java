package kr.commata.market.core;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * {@code BlockBreakEvent}에서 읽어 둔 블록 상태를 같은 블록의 {@code BlockDropItemEvent}까지 들고 가는 장부.
 *
 * <p>블록 좌표를 키로 쓰므로, 한 블록의 파괴 처리 안에서 다른 블록을 {@code player.breakBlock}으로 부수는 연쇄 파괴
 * (ECONOMY.md 15.5, 2.5b)에서도 바깥 블록 기록이 덮이지 않습니다. 취소된 파괴는 {@link #remove}로 지우고,
 * 드롭 이벤트가 오지 않은 기록(드롭 없음 등)은 다음 틱 이후 첫 파괴 때 {@link #purgeBefore}로 정리합니다.
 * 메인 스레드 전용입니다.
 */
public final class BreakLedger<K, V> {

    private record Entry<V>(long tick, V value) {
    }

    private final Map<K, Entry<V>> entries = new HashMap<>();

    public void put(K key, long tick, V value) {
        entries.put(key, new Entry<>(tick, value));
    }

    /** 기록을 꺼내고 지웁니다. 없으면 null. */
    public V take(K key) {
        Entry<V> e = entries.remove(key);
        return e == null ? null : e.value();
    }

    public void remove(K key) {
        entries.remove(key);
    }

    /** {@code tick}보다 이전 틱에 남긴 기록을 지웁니다. */
    public void purgeBefore(long tick) {
        if (entries.isEmpty()) {
            return;
        }
        Iterator<Entry<V>> it = entries.values().iterator();
        while (it.hasNext()) {
            if (it.next().tick() < tick) {
                it.remove();
            }
        }
    }

    public int size() {
        return entries.size();
    }
}
