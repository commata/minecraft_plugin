package kr.commata.common.cooldown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CooldownsTest {

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final Cooldowns cooldowns = new Cooldowns(clock);
    private final UUID player = UUID.randomUUID();

    @Test
    void blocksUntilCooldownEnds() {
        assertTrue(cooldowns.tryUse(player, "dash", Duration.ofSeconds(5)));
        assertFalse(cooldowns.tryUse(player, "dash", Duration.ofSeconds(5)));

        clock.advance(Duration.ofSeconds(3));
        assertEquals(Optional.of(Duration.ofSeconds(2)), cooldowns.remaining(player, "dash"));

        clock.advance(Duration.ofSeconds(2));
        assertTrue(cooldowns.tryUse(player, "dash", Duration.ofSeconds(5)));
    }

    @Test
    void keysAndPlayersAreIndependent() {
        assertTrue(cooldowns.tryUse(player, "dash", Duration.ofSeconds(5)));
        assertTrue(cooldowns.tryUse(player, "heal", Duration.ofSeconds(5)));
        assertTrue(cooldowns.tryUse(UUID.randomUUID(), "dash", Duration.ofSeconds(5)));
    }

    @Test
    void clearRemovesPlayerCooldowns() {
        cooldowns.tryUse(player, "dash", Duration.ofSeconds(5));
        cooldowns.clear(player);
        assertTrue(cooldowns.remaining(player, "dash").isEmpty());
    }
}
