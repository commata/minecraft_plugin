package kr.commata.home.logic;

import java.time.Duration;

/** epoch 밀리초로 저장된 만료 시각 계산. */
public final class Timers {

    private Timers() {
    }

    /** 남은 시간. 이미 지났으면 {@link Duration#ZERO}. */
    public static Duration remaining(long nowMillis, long untilMillis) {
        return untilMillis > nowMillis ? Duration.ofMillis(untilMillis - nowMillis) : Duration.ZERO;
    }

    /** 기존 만료 시각과 새 만료 시각 중 늦은 쪽 (짧은 전투 태그가 긴 태그를 덮어쓰지 않게). */
    public static long extend(long currentUntil, long nowMillis, Duration add) {
        return Math.max(currentUntil, nowMillis + add.toMillis());
    }
}
