package kr.commata.economyhub.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MarksTest {

    @Test
    void indexIsUniqueInsideSection() {
        Set<Integer> seen = new HashSet<>();
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int i = Marks.index(x, y, z);
                    assertTrue(i >= 0 && i < 4096);
                    assertTrue(seen.add(i));
                }
            }
        }
    }

    @Test
    void sectionAndIndexHandleNegativeCoordinates() {
        assertEquals(-1, Marks.section(-1));
        assertEquals(-4, Marks.section(-64));
        assertEquals(19, Marks.section(319));
        // 월드 x=-1 은 청크 안 x=15, y=-1 은 구역 -1 의 y=15
        assertEquals(Marks.index(15, 15, 15), Marks.index(-1, -1, -1));
    }

    @Test
    void everyBlockOfAFullSectionStaysMarked() {
        // 이전 방식(개수 상한 4,096 + 오래된 것 버림)의 회귀: 4,096개를 다 채워도 첫 표시가 남아야 함
        long[] bits = null;
        for (int i = 0; i < 4096; i++) {
            bits = Marks.set(bits, i);
        }
        for (int i = 0; i < 4096; i++) {
            assertTrue(Marks.get(bits, i), "표시 사라짐 " + i);
        }
    }

    @Test
    void setClearGet() {
        int a = Marks.index(1, 2, 3);
        int b = Marks.index(4, 5, 6);
        long[] bits = Marks.set(null, a);
        bits = Marks.set(bits, b);
        assertTrue(Marks.get(bits, a));
        assertTrue(Marks.get(bits, b));
        assertSame(bits, Marks.set(bits, a), "이미 있으면 같은 배열");
        long[] cleared = Marks.clear(bits, a);
        assertFalse(Marks.get(cleared, a));
        assertTrue(Marks.get(cleared, b));
        assertTrue(Marks.get(bits, a), "원래 배열은 그대로");
        assertSame(cleared, Marks.clear(cleared, a), "없으면 같은 배열");
        assertNull(Marks.clear(cleared, b), "다 지우면 null");
        assertFalse(Marks.get(null, a));
    }
}
