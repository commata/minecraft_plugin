package kr.commata.market.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QuotaBufferTest {

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @Test
    void mergesSameKeyAndSeparatesJobFlag() {
        QuotaBuffer buf = new QuotaBuffer();
        buf.add(alice, "OAK_LOG", 3, true);
        buf.add(alice, "OAK_LOG", 5, true);
        buf.add(alice, "OAK_LOG", 2, false);
        buf.add(alice, "OAK_LOG", 0, true);
        buf.add(alice, "OAK_LOG", -1, true);

        Map<QuotaBuffer.Key, Long> all = buf.drainAll();
        assertTrue(buf.isEmpty());
        assertEquals(2, all.size());
        assertEquals(8L, all.get(new QuotaBuffer.Key(alice, "OAK_LOG", true)));
        assertEquals(2L, all.get(new QuotaBuffer.Key(alice, "OAK_LOG", false)));
    }

    @Test
    void drainsSinglePlayerWithoutTouchingOthers() {
        QuotaBuffer buf = new QuotaBuffer();
        buf.add(alice, "DIAMOND", 4, true);
        buf.add(bob, "DIAMOND", 7, false);

        Map<QuotaBuffer.Key, Long> aliceOnly = buf.drainPlayer(alice);
        assertEquals(1, aliceOnly.size());
        assertEquals(4L, aliceOnly.get(new QuotaBuffer.Key(alice, "DIAMOND", true)));
        assertFalse(buf.isEmpty());

        Map<QuotaBuffer.Key, Long> rest = buf.drainAll();
        assertEquals(1, rest.size());
        assertEquals(7L, rest.get(new QuotaBuffer.Key(bob, "DIAMOND", false)));
    }

    @Test
    void restoreMergesBackWithNewlyAddedEntries() {
        QuotaBuffer buf = new QuotaBuffer();
        buf.add(alice, "COAL", 10, true);
        Map<QuotaBuffer.Key, Long> drained = buf.drainAll();

        // 반영 실패 전 새 채굴이 쌓였어도 restore 시 합산 보존
        buf.add(alice, "COAL", 3, true);
        buf.restore(drained);

        Map<QuotaBuffer.Key, Long> retry = buf.drainAll();
        assertEquals(13L, retry.get(new QuotaBuffer.Key(alice, "COAL", true)));
    }
}
