package kr.commata.economyhub.guard;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MarksTest {

    @Test
    void packIsUniqueInsideChunkAcrossWorldHeight() {
        Set<Integer> seen = new HashSet<>();
        for (int y = -64; y < 320; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    assertTrue(seen.add(Marks.pack(x, y, z)), "중복 " + x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    void packUsesChunkLocalCoordinatesForNegativeWorldCoordinates() {
        // 월드 x=-1 은 청크 안 x=15
        assertEquals(Marks.pack(15, 10, 15), Marks.pack(-1, 10, -1));
        assertNotEquals(Marks.pack(0, -64, 0), Marks.pack(0, 0, 0));
    }

    @Test
    void addRemoveContains() {
        int a = Marks.pack(1, 2, 3);
        int b = Marks.pack(4, 5, 6);
        int[] marks = Marks.add(null, a, 10);
        marks = Marks.add(marks, b, 10);
        assertTrue(Marks.contains(marks, a));
        assertTrue(Marks.contains(marks, b));
        assertSame(marks, Marks.add(marks, a, 10), "이미 있으면 같은 배열");
        int[] removed = Marks.remove(marks, a);
        assertFalse(Marks.contains(removed, a));
        assertTrue(Marks.contains(removed, b));
        assertSame(removed, Marks.remove(removed, a), "없으면 같은 배열");
        assertFalse(Marks.contains(null, a));
    }

    @Test
    void addDropsOldestOverLimit() {
        int[] marks = null;
        for (int i = 0; i < 5; i++) {
            marks = Marks.add(marks, i, 3);
        }
        assertArrayEquals(new int[] {2, 3, 4}, marks);
    }
}
