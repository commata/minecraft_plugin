package kr.commata.market.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SeedCropsTest {

    @Test
    void seedCropsSubtractOneReplantPerHarvest() {
        assertEquals(Map.of("POTATO", 2), SeedCrops.harvestQuota("POTATOES", Map.of("POTATO", 3)));
        assertEquals(Map.of("CARROT", 4), SeedCrops.harvestQuota("CARROTS", Map.of("CARROT", 5)));
        assertEquals(Map.of("NETHER_WART", 1), SeedCrops.harvestQuota("NETHER_WART", Map.of("NETHER_WART", 2)));
        assertEquals(Map.of("COCOA_BEANS", 2), SeedCrops.harvestQuota("COCOA", Map.of("COCOA_BEANS", 3)));
    }

    @Test
    void singleDropLeavesNothingAndOtherItemsUntouched() {
        Map<String, Integer> drops = new LinkedHashMap<>();
        drops.put("POTATO", 1);
        drops.put("POISONOUS_POTATO", 1);
        assertEquals(Map.of("POISONOUS_POTATO", 1), SeedCrops.harvestQuota("POTATOES", drops));
        // 입력은 그대로
        assertEquals(1, drops.get("POTATO"));
    }

    @Test
    void wheatAndBeetrootNotAdjusted() {
        Map<String, Integer> wheat = Map.of("WHEAT", 1, "WHEAT_SEEDS", 2);
        assertEquals(wheat, SeedCrops.harvestQuota("WHEAT", wheat));
        assertEquals(Map.of("BEETROOT", 1), SeedCrops.harvestQuota("BEETROOTS", Map.of("BEETROOT", 1)));
        assertNull(SeedCrops.replantItem("WHEAT"));
        assertEquals("POTATO", SeedCrops.replantItem("POTATOES"));
    }
}
