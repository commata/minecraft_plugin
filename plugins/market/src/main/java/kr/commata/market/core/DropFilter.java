package kr.commata.market.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 몹 사망 드롭에서 몹이 주워 장비 칸(손·갑옷)에 들고 있던 아이템 수량을 제외하는 순수 계산
 * (ECONOMY.md 8장, 14.2).
 */
public final class DropFilter {

    private DropFilter() {
    }

    /**
     * {@code drops}에서 {@code equipment}에 들어 있던 같은 종류(Material 이름) 수량만큼 차감합니다.
     * 0 이하가 된 항목은 결과에서 제외하며 절대 음수가 되지 않습니다.
     */
    public static Map<String, Integer> subtractEquipment(Map<String, Integer> drops, Map<String, Integer> equipment) {
        if (drops == null || drops.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : drops.entrySet()) {
            int dropped = entry.getValue() == null ? 0 : entry.getValue();
            int equipped = equipment == null ? 0 : Math.max(0, equipment.getOrDefault(entry.getKey(), 0));
            int remaining = dropped - equipped;
            if (remaining > 0) {
                out.put(entry.getKey(), remaining);
            }
        }
        return out;
    }
}
