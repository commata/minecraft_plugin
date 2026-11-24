package kr.commata.home.logic;

import java.time.Duration;

/** config.yml 값. 잘못된 값은 안전한 범위로 맞춥니다. */
public record HomeSettings(
        int castSeconds,
        Duration cooldown,
        Duration mobCombatTag,
        Duration pvpCombatTag,
        double maxFallDistance) {

    public static final HomeSettings DEFAULTS =
            of(7, 300, 15, 30, 3.0);

    public static HomeSettings of(int castSeconds, long cooldownSeconds, long mobTagSeconds,
                                  long pvpTagSeconds, double maxFallDistance) {
        return new HomeSettings(
                Math.max(0, castSeconds),
                Duration.ofSeconds(Math.max(0, cooldownSeconds)),
                Duration.ofSeconds(Math.max(0, mobTagSeconds)),
                Duration.ofSeconds(Math.max(0, pvpTagSeconds)),
                Math.max(0.0, maxFallDistance));
    }
}
