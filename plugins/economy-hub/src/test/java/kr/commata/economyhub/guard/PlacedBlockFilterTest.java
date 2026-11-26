package kr.commata.economyhub.guard;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PlacedBlockFilterTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "SUGAR_CANE",
            "CACTUS",
            "BROWN_MUSHROOM",
            "RED_MUSHROOM",
            "BROWN_MUSHROOM_BLOCK",
            "RED_MUSHROOM_BLOCK",
            "MUSHROOM_STEM",
            "PUMPKIN",
            "MELON",
            "ANCIENT_DEBRIS"
    })
    @DisplayName("농부 및 특수 매입 대상 블록 설치 시 표시 대상이어야 한다")
    void exactBlocksAreMarked(String blockName) {
        assertTrue(PlacedBlockFilter.isMarkedOnPlace(blockName, false),
                blockName + " 은 설치 시 mark 대상이어야 함");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "COAL_ORE",
            "IRON_ORE",
            "COPPER_ORE",
            "GOLD_ORE",
            "REDSTONE_ORE",
            "EMERALD_ORE",
            "LAPIS_ORE",
            "DIAMOND_ORE",
            "NETHER_QUARTZ_ORE",
            "NETHER_GOLD_ORE",
            "DEEPSLATE_COAL_ORE",
            "DEEPSLATE_IRON_ORE",
            "DEEPSLATE_DIAMOND_ORE"
    })
    @DisplayName("모든 광석 블록(_ORE)은 설치 시 표시 대상이어야 한다")
    void oresAreMarked(String oreName) {
        assertTrue(PlacedBlockFilter.isMarkedOnPlace(oreName, false),
                oreName + " 은 설치 시 mark 대상이어야 함");
    }

    @Test
    @DisplayName("원목 태그(isLog=true) 블록은 설치 시 표시 대상이어야 한다")
    void logsAreMarked() {
        assertTrue(PlacedBlockFilter.isMarkedOnPlace("OAK_LOG", true));
        assertTrue(PlacedBlockFilter.isMarkedOnPlace("CRIMSON_STEM", true));
        assertTrue(PlacedBlockFilter.isMarkedOnPlace("CUSTOM_LOG", true));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DIRT",
            "STONE",
            "COBBLESTONE",
            "SAND",
            "GRAVEL",
            "GLASS",
            "WHEAT",
            "CARROTS",
            "SWEET_BERRY_BUSH"
    })
    @DisplayName("일반 블록 및 파종 작물은 설치 표시 대상이 아니어야 한다")
    void normalBlocksAreNotMarked(String blockName) {
        assertFalse(PlacedBlockFilter.isMarkedOnPlace(blockName, false),
                blockName + " 은 설치 시 mark 대상이 아니어야 함");
    }

    @Test
    @DisplayName("Market 매입 블록 목록(사탕수수·선인장·버섯 5종)이 누락 없이 모두 포함되어야 한다")
    void matchesMarketDirectFarmerBlocks() {
        // Market 의 FarmerCropRules.DIRECT_FARMER_BLOCKS 규격과 일치 검증
        Set<String> marketBlocks = Set.of(
                "SUGAR_CANE",
                "CACTUS",
                "BROWN_MUSHROOM",
                "RED_MUSHROOM",
                "BROWN_MUSHROOM_BLOCK",
                "RED_MUSHROOM_BLOCK",
                "MUSHROOM_STEM"
        );
        for (String block : marketBlocks) {
            assertTrue(PlacedBlockFilter.isMarkedOnPlace(block, false),
                    "Market 매입 품목 " + block + " 이 PlacedBlockFilter 에 포함되어야 함");
        }
    }
}
