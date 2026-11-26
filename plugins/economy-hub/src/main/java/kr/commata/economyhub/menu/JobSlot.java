package kr.commata.economyhub.menu;

import java.util.Locale;
import java.util.OptionalInt;
import java.util.Set;

/** /직업 한 칸과 아래 상태 바의 상태 판정·표시 문구 (ECONOMY.md 7.4.2). Bukkit 없이 단위 테스트합니다. */
public final class JobSlot {

    /** /직업 에서 직업·상태 바를 놓을 수 있는 위 4줄 상한 (0–35). */
    public static final int GRID_LIMIT = 36;
    /** 한 줄 칸 수 (상태 바 = 직업 칸 + 9). */
    public static final int ROW_WIDTH = 9;

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

    /** 카드 아래 상태 바의 6가지 표시 상태 (ECONOMY.md 7.4.2). */
    public enum StatusKind {
        LOADING,
        JOINED,
        CONFIRM_LEAVE,
        FULL,
        COOLDOWN,
        AVAILABLE
    }

    /** 상태 바 유리판 재질 ID 와 MiniMessage 이름 (Bukkit 비의존). */
    public record StatusBar(String materialId, String name) {
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

    /**
     * 직업 칸 바로 아래 상태 바 슬롯({@code jobSlot + 9})을 구합니다.
     * 0–35 범위를 벗어나거나 {@code occupied}(다른 직업 칸·다른 직업의 상태 바)와 겹치면 비어 있음.
     */
    public static OptionalInt statusSlot(int jobSlot, Set<Integer> occupied) {
        int candidate = jobSlot + ROW_WIDTH;
        if (candidate < 0 || candidate >= GRID_LIMIT) {
            return OptionalInt.empty();
        }
        if (occupied != null && occupied.contains(candidate)) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(candidate);
    }

    /** 상태별 유리판 재질 ID와 이름 매핑 (ECONOMY.md 7.4.2). */
    public static StatusBar statusBar(StatusKind kind, int joinedCount, int limit, long cooldownRemainingMs) {
        return switch (kind) {
            case LOADING -> new StatusBar("LIGHT_GRAY_STAINED_GLASS_PANE", "<#AAAAAA>불러오는 중");
            case JOINED -> new StatusBar("LIME_STAINED_GLASS_PANE", "<#55FF55>가입 완료 ✔");
            case CONFIRM_LEAVE -> new StatusBar("RED_STAINED_GLASS_PANE", "<#FF5555>한 번 더 우클릭하면 탈퇴");
            case FULL -> new StatusBar("RED_STAINED_GLASS_PANE",
                    "<#FF5555>가입 불가 (" + joinedCount + "/" + limit + ")");
            case COOLDOWN -> new StatusBar("ORANGE_STAINED_GLASS_PANE",
                    "<#FFDF6B>쿨타임 " + clockColon(cooldownRemainingMs));
            case AVAILABLE -> new StatusBar("GREEN_STAINED_GLASS_PANE", "<#55FF55>▶ 좌클릭하여 가입");
        };
    }

    /** "MM분 SS초" (초는 올림, 60분 이상도 분으로). 예: 3_600_000 → "60분 00초", 61_500 → "01분 02초" */
    public static String clock(long remainingMs) {
        long seconds = remainingMs <= 0 ? 0 : (remainingMs + 999) / 1000;
        return String.format(Locale.ROOT, "%02d분 %02d초", seconds / 60, seconds % 60);
    }

    /** 상태 바용 "MM:SS" (초는 올림, 60분 이상도 분으로). 예: 3_600_000 → "60:00", 61_500 → "01:02" */
    public static String clockColon(long remainingMs) {
        long seconds = remainingMs <= 0 ? 0 : (remainingMs + 999) / 1000;
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    /** 보상 금액 표시: 정수면 "400원", 소수면 "2.5원" (MVP 보상은 정수, ECONOMY.md 2.2) */
    public static String won(double money) {
        if (money == Math.rint(money) && Math.abs(money) < 1e15) {
            return String.format(Locale.ROOT, "%,d원", (long) money);
        }
        return String.format(Locale.ROOT, "%,.1f원", money);
    }
}
