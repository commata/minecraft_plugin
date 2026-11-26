package kr.commata.market.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DropFilterTest {

    @Test
    void subtractsEquippedItemsWithoutGoingBelowZero() {
        Map<String, Integer> drops = Map.of(
                "GUNPOWDER", 3,
                "ENDER_PEARL", 16,
                "BONE", 2
        );
        Map<String, Integer> equipment = Map.of(
                "ENDER_PEARL", 16, // 좀비가 주워 들고 있던 엔더 진주 16개 → 전액 제외
                "GUNPOWDER", 1,    // 1개만 들고 있었으면 자연 드롭 2개만 남음
                "DIAMOND", 5       // 드롭에 없는 장비 → 무시, 0 미만 금지
        );

        Map<String, Integer> filtered = DropFilter.subtractEquipment(drops, equipment);
        assertEquals(Map.of("GUNPOWDER", 2, "BONE", 2), filtered);
    }

    @Test
    void dropsLessThanEquipmentProducesEmptyEntry() {
        Map<String, Integer> filtered = DropFilter.subtractEquipment(
                Map.of("BLAZE_ROD", 1),
                Map.of("BLAZE_ROD", 4)
        );
        assertTrue(filtered.isEmpty());
    }
}
