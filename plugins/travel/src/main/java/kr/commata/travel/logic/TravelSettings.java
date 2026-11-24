package kr.commata.travel.logic;

import java.time.Duration;

/** config.yml 값. 잘못된 값은 안전한 범위로 맞춥니다. */
public record TravelSettings(
        Mode home,
        Mode tpa,
        Duration requestTimeout,
        Duration resendInterval,
        Duration mobCombatTag,
        Duration pvpCombatTag,
        double maxFallDistance) {

    /** /home, /tpa 각각의 시전 시간과 성공 후 쿨타임. 쿨타임은 서로 따로 셉니다. */
    public record Mode(int castSeconds, Duration cooldown) {
        public static Mode of(int castSeconds, long cooldownSeconds) {
            return new Mode(Math.max(0, castSeconds), Duration.ofSeconds(Math.max(0, cooldownSeconds)));
        }
    }

    public static final TravelSettings DEFAULTS =
            of(Mode.of(7, 300), Mode.of(0, 300), 60, 10, 15, 30, 3.0); // /tpa 는 수락 즉시 이동

    public static TravelSettings of(Mode home, Mode tpa, long requestSeconds, long resendSeconds,
                                    long mobTagSeconds, long pvpTagSeconds, double maxFallDistance) {
        return new TravelSettings(
                home,
                tpa,
                // 요청 유효시간 0 은 받자마자 사라지므로 최소 5초
                Duration.ofSeconds(Math.max(5, requestSeconds)),
                Duration.ofSeconds(Math.max(0, resendSeconds)),
                Duration.ofSeconds(Math.max(0, mobTagSeconds)),
                Duration.ofSeconds(Math.max(0, pvpTagSeconds)),
                Math.max(0.0, maxFallDistance));
    }
}
