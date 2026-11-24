package kr.commata.travel.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
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
        var s = TravelSettings.of(TravelSettings.Mode.of(-1, -5), TravelSettings.Mode.of(7, 300), 0, -1, 15, 30, -2);
        assertEquals(0, s.home().castSeconds());
        assertEquals(Duration.ZERO, s.home().cooldown());
        assertEquals(Duration.ofSeconds(5), s.requestTimeout());
        assertEquals(Duration.ZERO, s.resendInterval());
        assertEquals(0.0, s.maxFallDistance());
    }

    @Test
    void homeAndTpaDefaultToSeparateFiveMinuteCooldowns() {
        assertEquals(Duration.ofMinutes(5), TravelSettings.DEFAULTS.home().cooldown());
        assertEquals(Duration.ofMinutes(5), TravelSettings.DEFAULTS.tpa().cooldown());
        assertEquals(0, TravelSettings.DEFAULTS.tpa().castSeconds()); // 수락 즉시 이동
        assertEquals(Duration.ofSeconds(60), TravelSettings.DEFAULTS.requestTimeout());
        assertEquals(Duration.ofSeconds(10), TravelSettings.DEFAULTS.resendInterval());
    }

    @Test
    void oldHomeConfigMovesToNewPaths() {
        var moved = ConfigMigration.fromHome(Map.of(
                "cast-seconds", 5,
                "cooldown-seconds", 120,
                "combat.mob-seconds", 20,
                "combat", Map.of(),
                "safety.max-fall-distance", 4.5,
                "unknown", 1));
        assertEquals(Map.of(
                "home.cast-seconds", 5,
                "home.cooldown-seconds", 120,
                "combat.mob-seconds", 20,
                "safety.max-fall-distance", 4.5), moved);
    }
}
