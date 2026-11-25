package kr.commata.economyhub.guard;

import java.util.Arrays;

/**
 * 청크 구역(16×16×16) 하나의 표시 비트맵. 블록 4,096개 = long 64개, 블록마다 1비트라 상한 없이 실제로 남아 있는 표시를
 * 모두 보존합니다. Bukkit 에 의존하지 않아 단위 테스트로 검증합니다.
 */
public final class Marks {

    /** 구역 하나의 long 개수 (4096 비트) */
    public static final int WORDS = 64;

    private Marks() {
    }

    /** 블록 y 가 속한 구역 번호 (음수 y 도 올바르게 내림). */
    public static int section(int y) {
        return y >> 4;
    }

    /** 구역 안 비트 위치 0~4095. x·y·z 는 월드 좌표를 그대로 넣어도 됨. */
    public static int index(int x, int y, int z) {
        return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    }

    public static boolean get(long[] bits, int index) {
        if (bits == null || bits.length != WORDS) {
            return false;
        }
        return (bits[index >>> 6] & (1L << (index & 63))) != 0;
    }

    /** 표시. 이미 표시돼 있으면 같은 배열을 그대로 돌려줌(저장을 건너뛸 수 있게). */
    public static long[] set(long[] bits, int index) {
        if (get(bits, index)) {
            return bits;
        }
        long[] out = bits == null || bits.length != WORDS ? new long[WORDS] : Arrays.copyOf(bits, WORDS);
        out[index >>> 6] |= 1L << (index & 63);
        return out;
    }

    /** 표시 지움. 표시가 없으면 같은 배열을 그대로, 다 지워지면 null 을 돌려줌(PDC 키 삭제). */
    public static long[] clear(long[] bits, int index) {
        if (!get(bits, index)) {
            return bits;
        }
        long[] out = Arrays.copyOf(bits, WORDS);
        out[index >>> 6] &= ~(1L << (index & 63));
        for (long word : out) {
            if (word != 0) {
                return out;
            }
        }
        return null;
    }
}
