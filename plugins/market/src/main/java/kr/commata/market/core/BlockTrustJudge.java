package kr.commata.market.core;

/**
 * 청크 구역 비트맵(long[64])을 기반으로 블록의 설치·자연 여부를 판정하는 순수 로직 (ECONOMY.md 14.2, 15.5).
 * Bukkit 에 의존하지 않아 단위 테스트로 검증합니다.
 */
public final class BlockTrustJudge {

    /** 구역 하나의 long 개수 (4,096 비트 = 16×16×16 블록). */
    public static final int WORDS = 64;

    private BlockTrustJudge() {
    }

    /** 블록 y 가 속한 구역 번호 (음수 y 도 올바르게 내림). */
    public static int section(int y) {
        return y >> 4;
    }

    /** 구역 안 비트 위치 0~4095. x·y·z 는 월드 좌표를 그대로 넣어도 됨. */
    public static int index(int x, int y, int z) {
        return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    }

    /**
     * 비트맵 데이터와 구역 안 인덱스로 블록 상태를 3상태(NATURAL / MARKED / UNKNOWN)로 판정합니다.
     *
     * @param keyExists 해당 구역의 PDC 키 존재 여부
     * @param bits      읽어온 long 배열 (키가 없으면 null, 타입이 달라도 null 일 수 있음)
     * @param index     구역 내 인덱스 (0~4095)
     * @return 판정 결과 (NATURAL, MARKED, UNKNOWN)
     */
    public static BlockTrustState judge(boolean keyExists, long[] bits, int index) {
        if (!keyExists) {
            // 비트맵이 아예 없는 경우(=그 구역에 표시된 블록 없음)는 자연 블록
            return BlockTrustState.NATURAL;
        }
        if (bits == null || bits.length != WORDS) {
            // 비트맵 키는 있는데 읽지 못했거나(타입 불일치) 길이가 64가 아니면 판정 불가 (fail-closed)
            return BlockTrustState.UNKNOWN;
        }
        if (index < 0 || index >= WORDS * 64) {
            return BlockTrustState.UNKNOWN;
        }
        boolean marked = (bits[index >>> 6] & (1L << (index & 63))) != 0;
        return marked ? BlockTrustState.MARKED : BlockTrustState.NATURAL;
    }

    /**
     * 두 비트맵(EconomyHub 와 Market 자체 비트맵)의 판정 결과를 결합합니다 (fail-closed).
     * <p>
     * - 둘 중 하나라도 UNKNOWN(손상/판정불가)이면 -> UNKNOWN
     * - 둘 중 하나라도 MARKED(설치됨)이면 -> MARKED
     * - 둘 다 NATURAL 인 경우만 -> NATURAL
     */
    public static BlockTrustState combine(BlockTrustState first, BlockTrustState second) {
        if (first == BlockTrustState.UNKNOWN || second == BlockTrustState.UNKNOWN) {
            return BlockTrustState.UNKNOWN;
        }
        if (first == BlockTrustState.MARKED || second == BlockTrustState.MARKED) {
            return BlockTrustState.MARKED;
        }
        return BlockTrustState.NATURAL;
    }

    /**
     * 비트맵의 특정 인덱스에 비트를 1로 설정합니다.
     * <p>
     * 손상된 배열(길이가 64가 아님)은 덮어쓰지 않고 원본을 반환합니다.
     *
     * @param bits  기존 비트맵 (null 일 수 있음)
     * @param index 구역 내 인덱스 (0~4095)
     * @return 갱신된 비트맵 배열 (길이 64) 또는 손상 시 원본 배열
     */
    public static long[] setBit(long[] bits, int index) {
        if (bits != null && bits.length != WORDS) {
            return bits; // 손상된 배열 보존
        }
        if (index < 0 || index >= WORDS * 64) {
            return bits != null ? bits : new long[WORDS];
        }
        long[] target = bits != null ? bits : new long[WORDS];
        target[index >>> 6] |= (1L << (index & 63));
        return target;
    }

    /**
     * 비트맵의 특정 인덱스 비트를 0으로 해제합니다.
     * <p>
     * 손상된 배열(길이가 64가 아님)은 덮어쓰지 않고 원본을 반환합니다.
     *
     * @param bits  기존 비트맵 (null 일 수 있음)
     * @param index 구역 내 인덱스 (0~4095)
     * @return 갱신된 비트맵 배열 또는 null
     */
    public static long[] clearBit(long[] bits, int index) {
        if (bits == null) {
            return null;
        }
        if (bits.length != WORDS) {
            return bits; // 손상된 배열 보존
        }
        if (index < 0 || index >= WORDS * 64) {
            return bits;
        }
        bits[index >>> 6] &= ~(1L << (index & 63));
        return bits;
    }

    /**
     * 비트맵에 1로 켜진 비트가 하나도 없는지 검사합니다.
     */
    public static boolean isEmpty(long[] bits) {
        if (bits == null) {
            return true;
        }
        for (long word : bits) {
            if (word != 0L) {
                return false;
            }
        }
        return true;
    }

    /**
     * 편의 메서드: 비트맵 배열만으로 판정할 때 (bits == null 이면 키 부재로 간주).
     */
    public static BlockTrustState judge(long[] bits, int index) {
        return judge(bits != null, bits, index);
    }
}
