package kr.commata.home.logic;

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
}
