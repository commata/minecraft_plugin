package kr.commata.bank.core;

import java.util.Locale;
import java.util.regex.Pattern;

/** 금액 검사와 표시. 잔액은 정수 원(long)으로만 다룹니다 (ECONOMY.md 2.2). */
public final class Amounts {

    /**
     * 잔액·금액 상한 (2^53 - 1). Vault 는 금액을 double 로 주고받으므로,
     * 이보다 크면 double 로 바꿀 때 1원 단위가 정확하지 않게 됩니다.
     */
    public static final long MAX = (1L << 53) - 1;

    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,16}");

    private Amounts() {
    }

    /** Vault 에서 받은 double 금액의 검사 결과. */
    public enum Check {
        OK, NOT_A_NUMBER, FRACTIONAL, NEGATIVE, TOO_LARGE
    }

    public static Check check(double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount)) {
            return Check.NOT_A_NUMBER;
        }
        if (amount < 0) {
            return Check.NEGATIVE;
        }
        if (amount != Math.rint(amount)) {
            return Check.FRACTIONAL;
        }
        if (amount > MAX) {
            return Check.TOO_LARGE;
        }
        return Check.OK;
    }

    /** {@link #check} 가 OK 일 때만 호출. 반올림하지 않습니다. */
    public static long exact(double amount) {
        if (check(amount) != Check.OK) {
            throw new IllegalArgumentException("정수 금액이 아님: " + amount);
        }
        return (long) amount;
    }

    /**
     * 명령어에 입력한 금액. 숫자만 허용합니다 (소수점, 부호, 쉼표, 1e3 같은 표기는 거부).
     *
     * @return 금액, 잘못된 입력이면 -1
     */
    public static long parse(String text) {
        if (text == null || !DIGITS.matcher(text).matches()) {
            return -1;
        }
        long value = Long.parseLong(text);
        return value > MAX ? -1 : value;
    }

    /** 12340 → "12,340원" */
    public static String format(long amount) {
        return String.format(Locale.ROOT, "%,d원", amount);
    }
}
