package kr.commata.economyhub.skills;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CaveVisionRuleTest {

    @Test
    void nightSurfaceIsNotDark() {
        // 밤의 지상: 하늘빛 15 (시간과 무관), 블록 빛 0
        assertFalse(CaveVisionRule.dark(70, 50, 15, 0));
    }

    @Test
    void deepCoveredCave() {
        assertTrue(CaveVisionRule.dark(20, 50, 0, 14));
        assertTrue(CaveVisionRule.dark(-40, 50, 0, 0));
    }

    @Test
    void openRavineAtDepthIsNotDark() {
        assertFalse(CaveVisionRule.dark(30, 50, 12, 0));
    }

    @Test
    void highButCoveredAndUnlit() {
        assertTrue(CaveVisionRule.dark(80, 50, 3, 2));
        assertFalse(CaveVisionRule.dark(80, 50, 3, 12)); // 횃불 켠 집 안
    }

    @Test
    void overwritePolicy() {
        assertTrue(CaveVisionRule.mayOverwrite(0, false));
        assertTrue(CaveVisionRule.mayOverwrite(200, true));
        assertFalse(CaveVisionRule.mayOverwrite(200, false)); // 남이 건 짧은 효과
        assertFalse(CaveVisionRule.mayOverwrite(9600, true)); // 물약 8분
        assertFalse(CaveVisionRule.mayOverwrite(-1, true)); // 무한
    }
}
