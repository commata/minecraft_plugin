package kr.commata.common.gui;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 메뉴 버튼 연타 방지 (ECONOMY.md 7.4). 한 플레이어의 클릭은 마지막으로 받아 준 클릭 뒤 {@code lockMs} 동안 무시합니다.
 * 체감용 장치일 뿐이고, 같은 거래가 두 번 처리되지 않게 막는 것은 각 기능의 "플레이어당 거래 하나" 규칙이 맡습니다.
 * 메인 스레드에서만 씁니다.
 */
public final class ClickLock {

    private final long lockMs;
    private final Map<UUID, Long> lastAccepted = new HashMap<>();

    public ClickLock(long lockMs) {
        if (lockMs < 0) {
            throw new IllegalArgumentException("lockMs < 0");
        }
        this.lockMs = lockMs;
    }

    /** 이 클릭을 처리해도 되면 true 를 돌려주고 시각을 기록합니다. 잠긴 동안의 클릭은 기록하지 않습니다(잠금이 늘어나지 않음). */
    public boolean tryAcquire(UUID player, long nowMs) {
        Long last = lastAccepted.get(player);
        if (last != null && nowMs - last < lockMs && nowMs >= last) {
            return false;
        }
        lastAccepted.put(player, nowMs);
        return true;
    }

    public void forget(UUID player) {
        lastAccepted.remove(player);
    }
}
