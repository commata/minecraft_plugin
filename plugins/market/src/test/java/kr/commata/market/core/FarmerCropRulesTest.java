package kr.commata.market.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FarmerCropRulesTest {

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4})
    @DisplayName("달콤한 열매 덤불은 나이 2 이상일 때 성숙으로 판정한다")
    void matureBerryBush(int age) {
        assertTrue(FarmerCropRules.isMatureBerryBush(age));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1})
    @DisplayName("달콤한 열매 덤불 나이 0, 1은 미성숙으로 판정한다")
    void immatureBerryBush(int age) {
        assertFalse(FarmerCropRules.isMatureBerryBush(age));
    }

    @Test
    @DisplayName("달콤한 열매 덤불 식별 및 설치 확인 면제 검증")
    void berryBushIdentification() {
        assertTrue(FarmerCropRules.isBerryBush("SWEET_BERRY_BUSH"));
        assertFalse(FarmerCropRules.isBerryBush("WHEAT"));
        assertTrue(FarmerCropRules.isPlacementCheckIgnored("SWEET_BERRY_BUSH"));
        assertFalse(FarmerCropRules.isPlacementCheckIgnored("SUGAR_CANE"));
        assertFalse(FarmerCropRules.isPlacementCheckIgnored("OAK_LOG"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SUGAR_CANE",
            "CACTUS",
            "BROWN_MUSHROOM",
            "RED_MUSHROOM",
            "BROWN_MUSHROOM_BLOCK",
            "RED_MUSHROOM_BLOCK",
            "MUSHROOM_STEM"
    })
    @DisplayName("사탕수수, 선인장, 버섯류는 성숙 검사 없는 직거래 블록이다")
    void directFarmerBlocks(String material) {
        assertTrue(FarmerCropRules.isDirectFarmerBlock(material));
    }

    @Test
    @DisplayName("버섯 블록 파괴 시 버섯 아이템만 유효 드롭으로 인정한다")
    void mushroomDrops() {
        assertTrue(FarmerCropRules.isEligibleDrop("BROWN_MUSHROOM_BLOCK", "BROWN_MUSHROOM"));
        assertTrue(FarmerCropRules.isEligibleDrop("RED_MUSHROOM_BLOCK", "RED_MUSHROOM"));
        assertTrue(FarmerCropRules.isEligibleDrop("MUSHROOM_STEM", "BROWN_MUSHROOM"));
        assertTrue(FarmerCropRules.isEligibleDrop("BROWN_MUSHROOM", "BROWN_MUSHROOM"));

        // 실크터치로 캔 거대 버섯 블록은 제외
        assertFalse(FarmerCropRules.isEligibleDrop("BROWN_MUSHROOM_BLOCK", "BROWN_MUSHROOM_BLOCK"));
        assertFalse(FarmerCropRules.isEligibleDrop("RED_MUSHROOM_BLOCK", "RED_MUSHROOM_BLOCK"));
        assertFalse(FarmerCropRules.isEligibleDrop("MUSHROOM_STEM", "MUSHROOM_STEM"));
    }

    @Test
    @DisplayName("사탕수수, 선인장, 열매 블록 드롭 검증")
    void otherDrops() {
        assertTrue(FarmerCropRules.isEligibleDrop("SUGAR_CANE", "SUGAR_CANE"));
        assertFalse(FarmerCropRules.isEligibleDrop("SUGAR_CANE", "DIRT"));

        assertTrue(FarmerCropRules.isEligibleDrop("CACTUS", "CACTUS"));
        assertFalse(FarmerCropRules.isEligibleDrop("CACTUS", "SAND"));

        assertTrue(FarmerCropRules.isEligibleDrop("SWEET_BERRY_BUSH", "SWEET_BERRIES"));
        assertFalse(FarmerCropRules.isEligibleDrop("SWEET_BERRY_BUSH", "STICK"));
    }
}
