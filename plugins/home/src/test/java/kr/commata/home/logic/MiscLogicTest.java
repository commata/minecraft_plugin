package kr.commata.home.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MiscLogicTest {

    @Test
    void homeLocationRoundTrips() {
        var home = new HomeLocation(UUID.randomUUID(), 12.5, 64.0, -300.25, 90.5f, -10f);
        var parsed = HomeLocation.parse(home.serialize()).orElseThrow();
        assertEquals(home.world(), parsed.world());
        assertEquals(home.x(), parsed.x(), 1e-3);
        assertEquals(home.y(), parsed.y(), 1e-3);
        assertEquals(home.z(), parsed.z(), 1e-3);
        assertEquals(home.yaw(), parsed.yaw(), 1e-2);
        assertEquals(home.pitch(), parsed.pitch(), 1e-2);
    }

    @Test
    void brokenHomeDataIsIgnored() {
        assertEquals(Optional.empty(), HomeLocation.parse(null));
        assertEquals(Optional.empty(), HomeLocation.parse("garbage"));
        assertEquals(Optional.empty(), HomeLocation.parse("not-a-uuid;1;2;3;4;5"));
    }

    @Test
    void destinationNeedsRoomAndGround() {
        assertTrue(DestinationSafety.isSafe(new DestinationSafety.Spot(true, true, true, false)));
        assertFalse(DestinationSafety.isSafe(new DestinationSafety.Spot(false, true, true, false)));
        assertFalse(DestinationSafety.isSafe(new DestinationSafety.Spot(true, false, true, false)));
        assertFalse(DestinationSafety.isSafe(new DestinationSafety.Spot(true, true, false, false)));
        assertFalse(DestinationSafety.isSafe(new DestinationSafety.Spot(true, true, true, true)));
        assertTrue(DestinationSafety.isHazardBlock("minecraft:lava"));
        assertTrue(DestinationSafety.isHazardBlock("MAGMA_BLOCK"));
        assertFalse(DestinationSafety.isHazardBlock("grass_block"));
        assertFalse(DestinationSafety.isHazardBlock(null));
    }

    @Test
    void timersAndTagsNeverShorten() {
        assertEquals(Duration.ofSeconds(5), Timers.remaining(1_000, 6_000));
        assertEquals(Duration.ZERO, Timers.remaining(7_000, 6_000));
        // 30초 PvP 태그가 남아 있을 때 15초 몬스터 태그가 덮어쓰지 않음
        long pvpUntil = 31_000;
        assertEquals(pvpUntil, Timers.extend(pvpUntil, 1_000, Duration.ofSeconds(15)));
        assertEquals(20_000, Timers.extend(0, 5_000, Duration.ofSeconds(15)));
    }

    @Test
    void countdownReachesZeroAfterGivenSeconds() {
        var c = new Countdown(3);
        assertFalse(c.tick());
        assertFalse(c.tick());
        assertTrue(c.tick());
        assertEquals(0, c.secondsLeft());
        assertTrue(new Countdown(0).tick());
    }

    @Test
    void settingsClampNegativeValues() {
        var s = HomeSettings.of(-1, -5, 15, 30, -2);
        assertEquals(0, s.castSeconds());
        assertEquals(Duration.ZERO, s.cooldown());
        assertEquals(0.0, s.maxFallDistance());
        assertEquals(Duration.ofMinutes(5), HomeSettings.DEFAULTS.cooldown());
    }
}
