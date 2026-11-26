package kr.commata.market.core;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/**
 * 주간(매주 월요일 00:00 KST)·일간(매일 00:00 KST) 초기화 키 계산과 금액 표기 (ECONOMY.md 13.4, 14.4).
 */
public final class MarketPeriods {

    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Seoul");

    private MarketPeriods() {
    }

    /** ISO 주차 키 (예: {@code "2026-W48"}). 매주 월요일 00:00 지정 시간대 기준. */
    public static String weekKey(long epochMs, ZoneId zone) {
        LocalDate date = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate();
        int weekYear = date.get(IsoFields.WEEK_BASED_YEAR);
        int week = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        return String.format(Locale.ROOT, "%04d-W%02d", weekYear, week);
    }

    /** 일간 키 (예: {@code "2026-11-26"}). 매일 00:00 지정 시간대 기준. */
    public static String dayKey(long epochMs, ZoneId zone) {
        return Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().toString();
    }

    /** 다음 월요일 00:00까지 남은 시간. */
    public static Duration untilNextMonday(long epochMs, ZoneId zone) {
        ZonedDateTime now = Instant.ofEpochMilli(epochMs).atZone(zone);
        ZonedDateTime next = now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atStartOfDay(zone);
        return Duration.between(now, next);
    }

    /** 다음 날 00:00까지 남은 시간. */
    public static Duration untilNextMidnight(long epochMs, ZoneId zone) {
        ZonedDateTime now = Instant.ofEpochMilli(epochMs).atZone(zone);
        ZonedDateTime next = now.toLocalDate().plusDays(1).atStartOfDay(zone);
        return Duration.between(now, next);
    }

    /** 금액을 "12,340원" 형식으로 표기 (ECONOMY.md 2.1, AGENTS.md 8). */
    public static String won(long amount) {
        return String.format(Locale.ROOT, "%,d원", amount);
    }
}
