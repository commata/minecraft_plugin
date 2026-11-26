package kr.commata.economyhub.skills;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CaveVisionRuleTest {

    @Test
    void nightSurfaceIsNotDark() {
        // 밤의 지상: 하늘빛 15 (시간과 무관)
        assertFalse(CaveVisionRule.dark(70, 50, 15));
    }

    @Test
    void deepCoveredCave() {
        assertTrue(CaveVisionRule.dark(20, 50, 0));
        assertTrue(CaveVisionRule.dark(50, 50, 0));
        assertTrue(CaveVisionRule.dark(-40, 50, 0));
    }

    @Test
    void openRavineAtDepthIsNotDark() {
        assertFalse(CaveVisionRule.dark(30, 50, 12));
        assertFalse(CaveVisionRule.dark(30, 50, 1)); // 하늘빛이 조금이라도 들면 꺼짐
    }

    @Test
    void highCoveredPlaceIsNotDark() {
        assertFalse(CaveVisionRule.dark(51, 50, 0)); // 지상 집 안·Y 50 위 동굴
    }

    @Test
    void overwritePolicy() {
        assertTrue(CaveVisionRule.mayOverwrite(0));
        assertTrue(CaveVisionRule.mayOverwrite(200));
        assertTrue(CaveVisionRule.mayOverwrite(300));
        assertFalse(CaveVisionRule.mayOverwrite(301));
        assertFalse(CaveVisionRule.mayOverwrite(9600)); // 물약 8분
        assertFalse(CaveVisionRule.mayOverwrite(-1)); // 무한
    }
}
