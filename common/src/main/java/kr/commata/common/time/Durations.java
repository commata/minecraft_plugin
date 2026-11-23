package kr.commata.common.time;

import java.time.Duration;

/** 남은 시간을 사람이 읽기 쉬운 한국어로 바꿔 주는 도우미. */
public final class Durations {

    private Durations() {
    }

    /**
     * 초 단위로 올림해서 "1시간 2분 3초", "4분 30초", "12초" 형식으로 바꿉니다.
     * 0 이하이면 "0초".
     */
    public static String korean(Duration duration) {
        long totalSeconds = ceilSeconds(duration);
        if (totalSeconds <= 0) {
            return "0초";
        }
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder sb = new StringBuilder();
        if (hours > 0) {
            sb.append(hours).append("시간");
        }
        if (minutes > 0) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(minutes).append("분");
        }
        if (seconds > 0) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(seconds).append("초");
        }
        return sb.toString();
    }

    /** 초 단위 올림. 0.2초 남았으면 1초로 표시해야 "0초 남음"이 나오지 않습니다. */
    public static long ceilSeconds(Duration duration) {
        if (duration.isNegative() || duration.isZero()) {
            return 0;
        }
        long seconds = duration.getSeconds();
        return duration.getNano() > 0 ? seconds + 1 : seconds;
    }
}
