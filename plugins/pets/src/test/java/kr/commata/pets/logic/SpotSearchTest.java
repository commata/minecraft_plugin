package kr.commata.pets.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpotSearchTest {

    @Test
    void candidatesExcludePlayerBlockAndAreUnique() {
        List<SpotSearch.Offset> c = SpotSearch.candidates(3, 2);
        assertEquals(7 * 7 * 5 - 1, c.size());
        assertEquals(c.size(), new HashSet<>(c).size());
        assertFalse(c.contains(new SpotSearch.Offset(0, 0, 0)));
    }

    @Test
    void candidatesAreSortedNearestFirstSameLevelFirst() {
        List<SpotSearch.Offset> c = SpotSearch.candidates(3, 2);
        // 바로 위·아래 칸(가로 거리 0)이 먼저, 그다음 옆 칸의 같은 높이
        assertEquals(0, c.get(0).dx() * c.get(0).dx() + c.get(0).dz() * c.get(0).dz());
        SpotSearch.Offset firstSide = c.stream().filter(o -> o.dx() != 0 || o.dz() != 0).findFirst().orElseThrow();
        assertEquals(0, firstSide.dy());
        assertEquals(1, Math.abs(firstSide.dx()) + Math.abs(firstSide.dz()));
        int prev = -1;
        for (SpotSearch.Offset o : c) {
            int d = o.dx() * o.dx() + o.dz() * o.dz();
            assertTrue(d >= prev);
            prev = d;
        }
    }

    @Test
    void safeSpotNeedsRoomAndGroundAndNoHazard() {
        assertTrue(SpotSearch.isSafe(new SpotSearch.Spot(true, true, true, false)));
        assertFalse(SpotSearch.isSafe(new SpotSearch.Spot(false, true, true, false)));
        assertFalse(SpotSearch.isSafe(new SpotSearch.Spot(true, false, true, false)));
        assertFalse(SpotSearch.isSafe(new SpotSearch.Spot(true, true, false, false)));
        assertFalse(SpotSearch.isSafe(new SpotSearch.Spot(true, true, true, true)));
    }

    @Test
    void hazardBlocksIgnoreNamespaceAndCase() {
        assertTrue(SpotSearch.isHazardBlock("lava"));
        assertTrue(SpotSearch.isHazardBlock("minecraft:MAGMA_BLOCK"));
        assertFalse(SpotSearch.isHazardBlock("grass_block"));
        assertFalse(SpotSearch.isHazardBlock(null));
    }
}
