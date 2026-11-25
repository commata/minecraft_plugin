package kr.commata.economyhub.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CooldownPolicyTest {

    private static final long HOUR = 3_600_000L;

    @Test
    void neverLeftIsExempt() {
        // 빈 슬롯을 처음 채울 때: 첫 직업 가입 직후 두 번째 가입도 바로
        assertTrue(CooldownPolicy.exempt(0, 1_000, HOUR));
        assertEquals(0, CooldownPolicy.remainingMs(0, 1_000, HOUR));
    }

    @Test
    void blockedForAnHourAfterLeaving() {
        long left = 10 * HOUR;
        assertFalse(CooldownPolicy.exempt(left, left + 1, HOUR));
        assertFalse(CooldownPolicy.exempt(left, left + HOUR - 1, HOUR));
        assertTrue(CooldownPolicy.exempt(left, left + HOUR, HOUR));
        assertEquals(HOUR - 1, CooldownPolicy.remainingMs(left, left + 1, HOUR));
    }

    @Test
    void zeroCooldownAlwaysExempt() {
        assertTrue(CooldownPolicy.exempt(5, 5, 0));
    }
}
