package kr.commata.home.logic;

import java.util.Optional;

/** 순간이동 직전에 플레이어가 위험한 상태인지 검사합니다. */
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
        FALLING("떨어지는 중에는"),
        GLIDING("겉날개로 비행하는 중에는"),
        LAVA("용암 속에서는"),
        FIRE("불에 타는 중에는"),
        DROWNING("물속에 잠긴 상태에서는");

        private final String phrase;

        Unsafe(String phrase) {
            this.phrase = phrase;
        }

        /** "떨어지는 중에는" 처럼 "귀환할 수 없습니다" 앞에 붙는 말. */
        public String phrase() {
            return phrase;
        }
    }

    private PlayerSafety() {
    }

    /**
     * @param maxFallDistance 이보다 많이 떨어지고 있으면 귀환 불가 (순간이동하면 낙하 거리가 초기화돼서
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
}
