package kr.commata.travel.logic;

import java.util.Optional;

/** 순간이동 직전에 플레이어(이동하는 사람, 또는 /tpa 목적지가 되는 사람)가 위험한 상태인지 검사합니다. */
public final class PlayerSafety {

    /** 검사에 필요한 플레이어 상태. Bukkit 의 Player 에서 값을 채워 넘깁니다. */
    public record State(
            boolean onGround,
            boolean flying,
            boolean gliding,
            boolean inVehicle,
            float fallDistance,
            boolean inLava,
            boolean onFire,
            boolean underWater,
            boolean canBreatheUnderwater) {
    }

    public enum Unsafe {
        FALLING("떨어지는 중", "떨어지는 중에는"),
        GLIDING("겉날개로 비행하는 중", "겉날개로 비행하는 중에는"),
        FLYING("날고 있는 중", "날고 있는 중에는"),
        VEHICLE("탈것에 타고 있는 중", "탈것에 타고 있는 중에는"),
        LAVA("용암 속", "용암 속에서는"),
        FIRE("불에 타는 중", "불에 타는 중에는"),
        DROWNING("물속에 잠긴 상태", "물속에 잠긴 상태에서는");

        private final String what;
        private final String phrase;

        Unsafe(String what, String phrase) {
            this.what = what;
            this.phrase = phrase;
        }

        /** "떨어지는 중" 처럼 상태를 나타내는 말. */
        public String what() {
            return what;
        }

        /** "떨어지는 중에는" 처럼 "귀환할 수 없습니다" 앞에 붙는 말. */
        public String phrase() {
            return phrase;
        }
    }

    private PlayerSafety() {
    }

    /**
     * 이동하는 사람 검사.
     *
     * @param maxFallDistance 이보다 많이 떨어지고 있으면 이동 불가 (순간이동하면 낙하 거리가 초기화돼서
     *                        낙하 피해를 피하는 데 악용될 수 있음)
     */
    public static Optional<Unsafe> check(State s, double maxFallDistance) {
        if (s.inLava()) {
            return Optional.of(Unsafe.LAVA);
        }
        if (s.onFire()) {
            return Optional.of(Unsafe.FIRE);
        }
        if (s.gliding()) {
            return Optional.of(Unsafe.GLIDING);
        }
        // 크리에이티브·관전 비행(OP 테스트)이나 탈것 위는 공중이어도 허용
        if (!s.flying() && !s.inVehicle() && !s.onGround() && s.fallDistance() > maxFallDistance) {
            return Optional.of(Unsafe.FALLING);
        }
        if (s.underWater() && !s.canBreatheUnderwater()) {
            return Optional.of(Unsafe.DROWNING);
        }
        return Optional.empty();
    }

    /**
     * /tpa 목적지가 되는 사람(수락자) 검사. 이동하는 사람이 그 자리에 그대로 도착하므로 더 엄격합니다:
     * 날고 있거나 탈것 위, 떨어지는 중이면 도착한 사람이 공중에 뜨거나 끼일 수 있어 막습니다.
     */
    public static Optional<Unsafe> checkDestination(State s, double maxFallDistance) {
        if (s.inLava()) {
            return Optional.of(Unsafe.LAVA);
        }
        if (s.onFire()) {
            return Optional.of(Unsafe.FIRE);
        }
        if (s.gliding()) {
            return Optional.of(Unsafe.GLIDING);
        }
        if (s.flying()) {
            return Optional.of(Unsafe.FLYING);
        }
        if (s.inVehicle()) {
            return Optional.of(Unsafe.VEHICLE);
        }
        if (!s.onGround() && s.fallDistance() > maxFallDistance) {
            return Optional.of(Unsafe.FALLING);
        }
        if (s.underWater()) {
            return Optional.of(Unsafe.DROWNING); // 이동하는 사람이 수중 호흡이 없을 수 있음
        }
        return Optional.empty();
    }
}
