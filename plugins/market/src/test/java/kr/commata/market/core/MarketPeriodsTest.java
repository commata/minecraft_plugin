package kr.commata.market.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class MarketPeriodsTest {

    private static final ZoneId KST = MarketPeriods.DEFAULT_ZONE;

    @Test
    void weekKeyRollsOverAtMondayMidnightKst() {
        long sunNight = ZonedDateTime.of(2026, 11, 29, 23, 59, 59, 0, KST).toInstant().toEpochMilli();
        long monStart = ZonedDateTime.of(2026, 11, 30, 0, 0, 0, 0, KST).toInstant().toEpochMilli();
        assertEquals("2026-W48", MarketPeriods.weekKey(sunNight, KST));
        assertEquals("2026-W49", MarketPeriods.weekKey(monStart, KST));
        assertNotEquals(MarketPeriods.weekKey(sunNight, KST), MarketPeriods.weekKey(monStart, KST));
    }

    @Test
    void dayKeyRollsOverAtMidnightKst() {
        long before = ZonedDateTime.of(2026, 11, 26, 23, 59, 59, 0, KST).toInstant().toEpochMilli();
        long after = ZonedDateTime.of(2026, 11, 27, 0, 0, 0, 0, KST).toInstant().toEpochMilli();
        assertEquals("2026-11-26", MarketPeriods.dayKey(before, KST));
        assertEquals("2026-11-27", MarketPeriods.dayKey(after, KST));
    }

    @Test
    void formatsWonWithCommas() {
        assertEquals("0원", MarketPeriods.won(0));
        assertEquals("12,340원", MarketPeriods.won(12_340));
    }
}
