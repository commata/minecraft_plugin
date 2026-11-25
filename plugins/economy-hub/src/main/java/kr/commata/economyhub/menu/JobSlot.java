package kr.commata.economyhub.menu;

import java.util.Locale;

/** /직업 한 칸의 상태 판정과 표시 문구 (ECONOMY.md 7.4). Bukkit 없이 단위 테스트합니다. */
public final class JobSlot {

    public enum State {
        /** 가입 중: 우클릭 탈퇴 */
        JOINED,
        /** 이미 최대 수만큼 가입: 가입 불가 */
        FULL,
        /** 탈퇴 후 쿨타임: 가입 불가, 남은 시간 표시 */
        COOLDOWN,
        /** 좌클릭 가입 */
        AVAILABLE
    }

    private JobSlot() {
    }

    /** 우선순위: 가입 중 → 한도 초과 → 쿨타임 → 가입 가능 (한 칸에 하나만) */
    public static State state(boolean joined, int joinedCount, int limit, long cooldownRemainingMs) {
        if (joined) {
            return State.JOINED;
        }
        if (joinedCount >= limit) {
            return State.FULL;
        }
        if (cooldownRemainingMs > 0) {
            return State.COOLDOWN;
        }
        return State.AVAILABLE;
    }

    /** "MM분 SS초" (초는 올림, 60분 이상도 분으로). 예: 3_600_000 → "60분 00초", 61_500 → "01분 02초" */
    public static String clock(long remainingMs) {
        long seconds = remainingMs <= 0 ? 0 : (remainingMs + 999) / 1000;
        return String.format(Locale.ROOT, "%02d분 %02d초", seconds / 60, seconds % 60);
    }

    /** 보상 금액 표시: 정수면 "400원", 소수면 "2.5원" (MVP 보상은 정수, ECONOMY.md 2.2) */
    public static String won(double money) {
        if (money == Math.rint(money) && Math.abs(money) < 1e15) {
            return String.format(Locale.ROOT, "%,d원", (long) money);
        }
        return String.format(Locale.ROOT, "%,.1f원", money);
    }
}
