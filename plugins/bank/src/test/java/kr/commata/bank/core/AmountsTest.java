package kr.commata.bank.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class AmountsTest {

    @Test
    void checkAcceptsOnlyNonNegativeIntegers() {
        assertEquals(Amounts.Check.OK, Amounts.check(0));
        assertEquals(Amounts.Check.OK, Amounts.check(1280));
        assertEquals(Amounts.Check.OK, Amounts.check(Amounts.MAX));
        assertEquals(Amounts.Check.FRACTIONAL, Amounts.check(8.04));
        assertEquals(Amounts.Check.FRACTIONAL, Amounts.check(0.5));
        // 부동소수 계산 오차로 생긴 값도 반올림하지 않고 거부
        assertEquals(Amounts.Check.FRACTIONAL, Amounts.check(0.1 + 0.2 + 99.7 - 0.0000001));
        assertEquals(Amounts.Check.NEGATIVE, Amounts.check(-1));
        assertEquals(Amounts.Check.NEGATIVE, Amounts.check(-0.5));
        assertEquals(Amounts.Check.NOT_A_NUMBER, Amounts.check(Double.NaN));
        assertEquals(Amounts.Check.NOT_A_NUMBER, Amounts.check(Double.POSITIVE_INFINITY));
        assertEquals(Amounts.Check.TOO_LARGE, Amounts.check(1e17));
    }

    @Test
    void exactRefusesFractions() {
        assertEquals(100L, Amounts.exact(100.0));
        assertThrows(IllegalArgumentException.class, () -> Amounts.exact(100.5));
    }

    @Test
    void parseAcceptsDigitsOnly() {
        assertEquals(10, Amounts.parse("10"));
        assertEquals(0, Amounts.parse("0"));
        assertEquals(1_000_000, Amounts.parse("1000000"));
        Stream.of("1.5", "10.0", "-10", "+10", "1e3", "1,000", "", " 10", "십", "99999999999999999")
                .forEach(s -> assertEquals(-1, Amounts.parse(s), s));
        assertEquals(-1, Amounts.parse(null));
    }

    @Test
    void formatUsesCommasAndWon() {
        assertEquals("12,340원", Amounts.format(12340));
        assertEquals("0원", Amounts.format(0));
    }
}
