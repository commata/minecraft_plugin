package kr.commata.market.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BlockTrustJudgeTest {

    @Test
    @DisplayName("PDC 키가 없으면 확정 자연 블록(NATURAL)으로 판정한다")
    void absentKeyIsNatural() {
        assertEquals(BlockTrustState.NATURAL, BlockTrustJudge.judge(false, null, 0));
        assertEquals(BlockTrustState.NATURAL, BlockTrustJudge.judge(false, new long[64], 100));
        assertEquals(BlockTrustState.NATURAL, BlockTrustJudge.judge(null, 0));
    }

    @Test
    @DisplayName("PDC 키가 있는데 비트맵이 null(타입 불일치 등)이면 판정 불가(UNKNOWN) fail-closed")
    void keyExistsButNullBitsIsUnknown() {
        assertEquals(BlockTrustState.UNKNOWN, BlockTrustJudge.judge(true, null, 0));
        assertEquals(BlockTrustState.UNKNOWN, BlockTrustJudge.judge(true, null, 500));
    }

    @Test
    @DisplayName("비트맵 길이가 64가 아니면 판정 불가(UNKNOWN) fail-closed")
    void invalidLengthIsUnknown() {
        assertEquals(BlockTrustState.UNKNOWN, BlockTrustJudge.judge(true, new long[0], 0));
        assertEquals(BlockTrustState.UNKNOWN, BlockTrustJudge.judge(true, new long[1], 0));
        assertEquals(BlockTrustState.UNKNOWN, BlockTrustJudge.judge(true, new long[63], 0));
        assertEquals(BlockTrustState.UNKNOWN, BlockTrustJudge.judge(true, new long[65], 0));
        assertEquals(BlockTrustState.UNKNOWN, BlockTrustJudge.judge(new long[63], 0));
    }

    @Test
    @DisplayName("인덱스가 0~4095 범위를 벗어나면 판정 불가(UNKNOWN) fail-closed")
    void indexOutOfBoundsIsUnknown() {
        long[] bits = new long[64];
        assertEquals(BlockTrustState.UNKNOWN, BlockTrustJudge.judge(true, bits, -1));
        assertEquals(BlockTrustState.UNKNOWN, BlockTrustJudge.judge(true, bits, 4096));
        assertEquals(BlockTrustState.UNKNOWN, BlockTrustJudge.judge(true, bits, 10000));
        assertEquals(BlockTrustState.UNKNOWN, BlockTrustJudge.judge(bits, -1));
    }

    @Test
    @DisplayName("정상 64개 long 비트맵에서 비트 0은 NATURAL, 비트 1은 MARKED")
    void bitEvaluation() {
        long[] bits = new long[64];
        // 처음(0), 중간(1234), 끝(4095) 인덱스에 표시 설정
        int[] markedIndices = {0, 63, 64, 1234, 4095};
        for (int idx : markedIndices) {
            bits[idx >>> 6] |= (1L << (idx & 63));
        }

        for (int idx : markedIndices) {
            assertEquals(BlockTrustState.MARKED, BlockTrustJudge.judge(true, bits, idx),
                    "인덱스 " + idx + "는 MARKED 여야 함");
            assertEquals(BlockTrustState.MARKED, BlockTrustJudge.judge(bits, idx));
        }

        // 표시되지 않은 인덱스는 NATURAL
        int[] naturalIndices = {1, 62, 65, 1233, 1235, 4094};
        for (int idx : naturalIndices) {
            assertEquals(BlockTrustState.NATURAL, BlockTrustJudge.judge(true, bits, idx),
                    "인덱스 " + idx + "는 NATURAL 이어야 함");
            assertEquals(BlockTrustState.NATURAL, BlockTrustJudge.judge(bits, idx));
        }
    }

    @ParameterizedTest
    @CsvSource({
            "0, 0",
            "15, 0",
            "16, 1",
            "31, 1",
            "-1, -1",
            "-16, -1",
            "-17, -2",
            "-32, -2",
            "-64, -4"
    })
    @DisplayName("y 좌표의 16블록 구역 번호 계산 (음수 y 포함)")
    void sectionCalculation(int y, int expectedSection) {
        assertEquals(expectedSection, BlockTrustJudge.section(y));
    }

    @Test
    @DisplayName("구역 내 0~4095 인덱스 계산이 Marks.index 규격과 일치")
    void indexCalculation() {
        assertEquals(0, BlockTrustJudge.index(0, 0, 0));
        assertEquals(15, BlockTrustJudge.index(15, 0, 0));
        assertEquals(16, BlockTrustJudge.index(0, 0, 1));
        assertEquals(256, BlockTrustJudge.index(0, 1, 0));
        assertEquals(4095, BlockTrustJudge.index(15, 15, 15));

        // 16 단위 주기 확인 (월드 좌표가 16 이상이거나 음수여도 구역 내 좌표 0~15로 정규화)
        assertEquals(BlockTrustJudge.index(0, 0, 0), BlockTrustJudge.index(16, 32, 64));
        assertEquals(BlockTrustJudge.index(15, 15, 15), BlockTrustJudge.index(-1, -1, -1));
    }

    @Test
    @DisplayName("BlockTrustState isNatural 헬퍼 동작 확인")
    void stateHelper() {
        assertTrue(BlockTrustState.NATURAL.isNatural());
        assertFalse(BlockTrustState.MARKED.isNatural());
        assertFalse(BlockTrustState.UNKNOWN.isNatural());
    }

    @Test
    @DisplayName("combine 은 둘 중 하나라도 UNKNOWN 이면 UNKNOWN 을 반환한다")
    void combineUnknownPriority() {
        assertEquals(BlockTrustState.UNKNOWN, BlockTrustJudge.combine(BlockTrustState.UNKNOWN, BlockTrustState.NATURAL));
        assertEquals(BlockTrustState.UNKNOWN, BlockTrustJudge.combine(BlockTrustState.NATURAL, BlockTrustState.UNKNOWN));
        assertEquals(BlockTrustState.UNKNOWN, BlockTrustJudge.combine(BlockTrustState.UNKNOWN, BlockTrustState.MARKED));
        assertEquals(BlockTrustState.UNKNOWN, BlockTrustJudge.combine(BlockTrustState.MARKED, BlockTrustState.UNKNOWN));
        assertEquals(BlockTrustState.UNKNOWN, BlockTrustJudge.combine(BlockTrustState.UNKNOWN, BlockTrustState.UNKNOWN));
    }

    @Test
    @DisplayName("combine 은 UNKNOWN 이 없고 둘 중 하나라도 MARKED 이면 MARKED 를 반환한다")
    void combineMarked() {
        assertEquals(BlockTrustState.MARKED, BlockTrustJudge.combine(BlockTrustState.MARKED, BlockTrustState.NATURAL));
        assertEquals(BlockTrustState.MARKED, BlockTrustJudge.combine(BlockTrustState.NATURAL, BlockTrustState.MARKED));
        assertEquals(BlockTrustState.MARKED, BlockTrustJudge.combine(BlockTrustState.MARKED, BlockTrustState.MARKED));
    }

    @Test
    @DisplayName("combine 은 둘 다 NATURAL 일 때만 NATURAL 을 반환한다")
    void combineNatural() {
        assertEquals(BlockTrustState.NATURAL, BlockTrustJudge.combine(BlockTrustState.NATURAL, BlockTrustState.NATURAL));
    }

    @Test
    @DisplayName("setBit 와 clearBit 동작 및 손상 배열 보존 검증")
    void bitOperations() {
        // null 배열에 setBit
        long[] bits = BlockTrustJudge.setBit(null, 100);
        assertEquals(64, bits.length);
        assertEquals(BlockTrustState.MARKED, BlockTrustJudge.judge(bits, 100));
        assertEquals(BlockTrustState.NATURAL, BlockTrustJudge.judge(bits, 101));
        assertFalse(BlockTrustJudge.isEmpty(bits));

        // clearBit
        bits = BlockTrustJudge.clearBit(bits, 100);
        assertEquals(BlockTrustState.NATURAL, BlockTrustJudge.judge(bits, 100));
        assertTrue(BlockTrustJudge.isEmpty(bits));

        // null clearBit
        assertTrue(BlockTrustJudge.clearBit(null, 50) == null);

        // 손상 배열 보존
        long[] corrupted = new long[30];
        long[] afterSet = BlockTrustJudge.setBit(corrupted, 5);
        assertTrue(corrupted == afterSet, "손상 배열은 새로 만들지 않고 그대로 반환해야 함");
        assertEquals(30, afterSet.length);

        long[] afterClear = BlockTrustJudge.clearBit(corrupted, 5);
        assertTrue(corrupted == afterClear, "손상 배열은 수정하지 않고 그대로 반환해야 함");
    }

    @Test
    @DisplayName("HubStatus isAvailable 동작 검증")
    void hubStatusAvailable() {
        assertTrue(HubStatus.AVAILABLE.isAvailable());
        assertFalse(HubStatus.DISABLED.isAvailable());
        assertFalse(HubStatus.UNSUPPORTED_SPEC.isAvailable());
    }
}
