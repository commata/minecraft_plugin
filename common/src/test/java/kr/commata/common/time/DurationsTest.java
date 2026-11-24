package kr.commata.common.time;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DurationsTest {

    @Test
    void formatsSecondsMinutesHours() {
        assertEquals("12초", Durations.korean(Duration.ofSeconds(12)));
        assertEquals("4분 30초", Durations.korean(Duration.ofSeconds(270)));
        assertEquals("5분", Durations.korean(Duration.ofMinutes(5)));
        assertEquals("1시간 2초", Durations.korean(Duration.ofSeconds(3602)));
        assertEquals("1시간 1분 1초", Durations.korean(Duration.ofSeconds(3661)));
    }

    @Test
    void roundsUpPartialSeconds() {
        assertEquals("1초", Durations.korean(Duration.ofMillis(200)));
        assertEquals("15초", Durations.korean(Duration.ofMillis(14_001)));
    }

    @Test
    void zeroAndNegativeAreZero() {
        assertEquals("0초", Durations.korean(Duration.ZERO));
        assertEquals("0초", Durations.korean(Duration.ofSeconds(-3)));
    }
}
