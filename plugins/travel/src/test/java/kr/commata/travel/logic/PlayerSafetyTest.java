package kr.commata.travel.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class PlayerSafetyTest {

    private static PlayerSafety.State standing() {
        return new PlayerSafety.State(true, false, false, false, 0f, false, false, false, false);
    }

    @Test
    void standingOrSmallJumpIsSafe() {
        assertEquals(Optional.empty(), PlayerSafety.check(standing(), 3.0));
        var jumping = new PlayerSafety.State(false, false, false, false, 1.2f, false, false, false, false);
        assertEquals(Optional.empty(), PlayerSafety.check(jumping, 3.0));
    }

    @Test
    void longFallIsUnsafeUnlessFlyingOrRiding() {
        var falling = new PlayerSafety.State(false, false, false, false, 8f, false, false, false, false);
        assertEquals(Optional.of(PlayerSafety.Unsafe.FALLING), PlayerSafety.check(falling, 3.0));

        var flying = new PlayerSafety.State(false, true, false, false, 8f, false, false, false, false);
        assertEquals(Optional.empty(), PlayerSafety.check(flying, 3.0));

        var riding = new PlayerSafety.State(false, false, false, true, 8f, false, false, false, false);
        assertEquals(Optional.empty(), PlayerSafety.check(riding, 3.0));
    }

    @Test
    void hazardsAreUnsafe() {
        var lava = new PlayerSafety.State(true, false, false, false, 0f, true, true, false, false);
        assertEquals(Optional.of(PlayerSafety.Unsafe.LAVA), PlayerSafety.check(lava, 3.0));

        var fire = new PlayerSafety.State(true, false, false, false, 0f, false, true, false, false);
        assertEquals(Optional.of(PlayerSafety.Unsafe.FIRE), PlayerSafety.check(fire, 3.0));

        var gliding = new PlayerSafety.State(false, false, true, false, 0f, false, false, false, false);
        assertEquals(Optional.of(PlayerSafety.Unsafe.GLIDING), PlayerSafety.check(gliding, 3.0));
    }

    @Test
    void underwaterIsUnsafeWithoutWaterBreathing() {
        var under = new PlayerSafety.State(false, false, false, false, 0f, false, false, true, false);
        assertEquals(Optional.of(PlayerSafety.Unsafe.DROWNING), PlayerSafety.check(under, 3.0));

        var breathing = new PlayerSafety.State(false, false, false, false, 0f, false, false, true, true);
        assertEquals(Optional.empty(), PlayerSafety.check(breathing, 3.0));
    }

    @Test
    void destinationPlayerMustBeStandingStill() {
        assertEquals(Optional.empty(), PlayerSafety.checkDestination(standing(), 3.0));

        var flying = new PlayerSafety.State(false, true, false, false, 0f, false, false, false, false);
        assertEquals(Optional.of(PlayerSafety.Unsafe.FLYING), PlayerSafety.checkDestination(flying, 3.0));

        var riding = new PlayerSafety.State(true, false, false, true, 0f, false, false, false, false);
        assertEquals(Optional.of(PlayerSafety.Unsafe.VEHICLE), PlayerSafety.checkDestination(riding, 3.0));

        var falling = new PlayerSafety.State(false, false, false, false, 8f, false, false, false, false);
        assertEquals(Optional.of(PlayerSafety.Unsafe.FALLING), PlayerSafety.checkDestination(falling, 3.0));

        // 수락자가 수중 호흡 중이어도, 도착하는 사람은 아닐 수 있음
        var underwater = new PlayerSafety.State(false, false, false, false, 0f, false, false, true, true);
        assertEquals(Optional.of(PlayerSafety.Unsafe.DROWNING), PlayerSafety.checkDestination(underwater, 3.0));
    }

    @Test
    void phrasesReadNaturally() {
        assertEquals("용암 속에서는", PlayerSafety.Unsafe.LAVA.phrase());
        assertEquals("떨어지는 중에는", PlayerSafety.Unsafe.FALLING.phrase());
        assertEquals("탈것에 타고 있는 중", PlayerSafety.Unsafe.VEHICLE.what());
    }
}
