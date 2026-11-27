package kr.commata.trade.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.commata.trade.core.Journal.SideStatus;
import kr.commata.trade.core.Journal.Stage;
import kr.commata.trade.core.TradeSession.Side;
import org.junit.jupiter.api.Test;

/**
 * {@link JournalCacheMerge} 의 재로딩 방어적 병합 규칙을 검증합니다 (재검증 P1 · P3-11).
 */
final class JournalCacheMergeTest {

    private static final UUID PLAYER_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLAYER_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static Journal.Entry committedPending(UUID tradeId) {
        return new Journal.Entry(
                tradeId, 1000L, 1000L, Stage.COMMITTED,
                PLAYER_A, "Alice", PLAYER_B, "Bob",
                0L, 0L,
                List.of(new byte[]{1, 2, 3}), List.of(new byte[]{4, 5, 6}),
                List.of("다이아몬드 x1"), List.of("에메랄드 x1"),
                SideStatus.FOUND, SideStatus.FOUND,
                SideStatus.PENDING, SideStatus.PENDING,
                SideStatus.NONE, SideStatus.NONE,
                1, 1, 1, 1,
                0, 0,
                0L, 0L,
                "", false, ""
        );
    }

    private static Journal.Entry takingEntry(UUID tradeId, int retriesA, int retriesB) {
        return new Journal.Entry(
                tradeId, 1000L, 1000L, Stage.TAKING,
                PLAYER_A, "Alice", PLAYER_B, "Bob",
                0L, 0L,
                List.of(new byte[]{1, 2, 3}), List.of(),
                List.of("다이아몬드 x1"), List.of(),
                SideStatus.PENDING, SideStatus.FOUND,
                SideStatus.FOUND, SideStatus.PENDING,
                SideStatus.NONE, SideStatus.NONE,
                1, 1, 1, 1,
                retriesA, retriesB,
                0L, 0L,
                "", false, ""
        );
    }

    @Test
    void hasProtectiveStateDetectsUncertainSealedAndTakeRetryLockOnly() {
        UUID id = UUID.randomUUID();
        Journal.Entry plain = committedPending(id);
        assertFalse(JournalCacheMerge.hasProtectiveState(plain));
        assertFalse(JournalCacheMerge.hasProtectiveState(takingEntry(id, 2, 0)));

        assertTrue(JournalCacheMerge.hasProtectiveState(plain.withSealed("회수 실패", 2000L)));
        assertTrue(JournalCacheMerge.hasProtectiveState(plain.withGive(Side.A, SideStatus.UNCERTAIN, 1, false, 2000L)));
        assertTrue(JournalCacheMerge.hasProtectiveState(plain.withGive(Side.B, SideStatus.UNCERTAIN, 1, false, 2000L)));
        assertTrue(JournalCacheMerge.hasProtectiveState(plain.withReturn(Side.A, SideStatus.UNCERTAIN, 1, false, 2000L)));
        assertTrue(JournalCacheMerge.hasProtectiveState(plain.withReturn(Side.B, SideStatus.UNCERTAIN, 1, false, 2000L)));
        assertTrue(JournalCacheMerge.hasProtectiveState(takingEntry(id, RecoveryPolicy.MAX_TAKE_SAVE_RETRIES, 0)));
        assertTrue(JournalCacheMerge.hasProtectiveState(takingEntry(id, 0, RecoveryPolicy.MAX_TAKE_SAVE_RETRIES)));
    }

    @Test
    void diskPendingDoesNotOverwriteMemoryUncertain() {
        UUID id = UUID.randomUUID();
        Journal.Entry diskPending = committedPending(id);
        Journal.Entry memUncertain = diskPending.withGive(Side.A, SideStatus.UNCERTAIN, 1, false, 2000L);

        assertTrue(JournalCacheMerge.isDiskLessProtective(memUncertain, diskPending));

        var result = JournalCacheMerge.merge(Map.of(id, memUncertain), List.of(diskPending), Set.of(id));
        assertEquals(SideStatus.UNCERTAIN, result.merged().get(id).giveA());
        assertEquals(Set.of(id), result.preservedMemoryIds());
        assertEquals(Set.of(id), result.remainingUnpersistedIds());
    }

    @Test
    void diskFoundOrLowerAttemptUncertainDoesNotOverwriteMemoryUncertain() {
        UUID id = UUID.randomUUID();
        Journal.Entry base = committedPending(id);
        Journal.Entry memUncertain2 = base.withGive(Side.A, SideStatus.UNCERTAIN, 2, false, 3000L);
        Journal.Entry diskFound = base.withGive(Side.A, SideStatus.FOUND, 1, false, 2000L);
        Journal.Entry diskUncertain1 = base.withGive(Side.A, SideStatus.UNCERTAIN, 1, false, 2000L);
        Journal.Entry diskUncertain2 = base.withGive(Side.A, SideStatus.UNCERTAIN, 2, false, 3000L);

        assertTrue(JournalCacheMerge.isDiskLessProtective(memUncertain2, diskFound));
        assertTrue(JournalCacheMerge.isDiskLessProtective(memUncertain2, diskUncertain1));
        assertFalse(JournalCacheMerge.isDiskLessProtective(memUncertain2, diskUncertain2));

        var rFound = JournalCacheMerge.merge(Map.of(id, memUncertain2), List.of(diskFound), Set.of(id));
        assertEquals(SideStatus.UNCERTAIN, rFound.merged().get(id).giveA());
        assertEquals(2, rFound.merged().get(id).giveAttemptA());
        assertEquals(Set.of(id), rFound.preservedMemoryIds());

        var rLower = JournalCacheMerge.merge(Map.of(id, memUncertain2), List.of(diskUncertain1), Set.of(id));
        assertEquals(2, rLower.merged().get(id).giveAttemptA());
        assertEquals(Set.of(id), rLower.remainingUnpersistedIds());

        // 디스크에 같은 시도 번호 UNCERTAIN 이 이미 영속화된 경우 정상 병합 + 미영속 해제
        var rSame = JournalCacheMerge.merge(Map.of(id, memUncertain2), List.of(diskUncertain2), Set.of(id));
        assertEquals(diskUncertain2, rSame.merged().get(id));
        assertTrue(rSame.preservedMemoryIds().isEmpty());
        assertTrue(rSame.remainingUnpersistedIds().isEmpty());
    }

    @Test
    void diskUnsealedDoesNotOverwriteMemorySealed() {
        UUID id = UUID.randomUUID();
        Journal.Entry diskUnsealed = committedPending(id);
        Journal.Entry memSealed = diskUnsealed.withSealed("지급/반환 같은 틱 회수 실패: Alice", 2000L);

        assertTrue(JournalCacheMerge.isDiskLessProtective(memSealed, diskUnsealed));
        assertFalse(JournalCacheMerge.isDiskLessProtective(memSealed, memSealed));

        var blocked = JournalCacheMerge.merge(Map.of(id, memSealed), List.of(diskUnsealed), Set.of(id));
        assertTrue(blocked.merged().get(id).isSealed());
        assertEquals(Set.of(id), blocked.preservedMemoryIds());
        assertEquals(Set.of(id), blocked.remainingUnpersistedIds());

        // 디스크에도 봉인이 반영되어 있으면 정상 병합 + 미영속 해제
        var synced = JournalCacheMerge.merge(Map.of(id, memSealed), List.of(memSealed), Set.of(id));
        assertTrue(synced.merged().get(id).isSealed());
        assertTrue(synced.preservedMemoryIds().isEmpty());
        assertTrue(synced.remainingUnpersistedIds().isEmpty());
    }

    @Test
    void diskLowerTakeRetriesDoesNotOverwriteThreeRetryLock() {
        UUID id = UUID.randomUUID();
        Journal.Entry memLocked = takingEntry(id, RecoveryPolicy.MAX_TAKE_SAVE_RETRIES, 0);
        Journal.Entry diskStale = takingEntry(id, RecoveryPolicy.MAX_TAKE_SAVE_RETRIES - 1, 0);

        assertTrue(JournalCacheMerge.isDiskLessProtective(memLocked, diskStale));

        var result = JournalCacheMerge.merge(Map.of(id, memLocked), List.of(diskStale), Set.of(id));
        assertEquals(RecoveryPolicy.MAX_TAKE_SAVE_RETRIES, result.merged().get(id).takeSaveRetriesA());
        assertEquals(Set.of(id), result.preservedMemoryIds());
        assertEquals(Set.of(id), result.remainingUnpersistedIds());
    }

    @Test
    void protectiveMemoryEntryMissingFromDiskIsPreservedWhilePlainEntryIsDropped() {
        UUID protectedId = UUID.randomUUID();
        UUID plainId = UUID.randomUUID();
        Journal.Entry protectedEntry = committedPending(protectedId)
                .withGive(Side.B, SideStatus.UNCERTAIN, 1, false, 2000L);
        Journal.Entry plainEntry = committedPending(plainId);

        var result = JournalCacheMerge.merge(
                Map.of(protectedId, protectedEntry, plainId, plainEntry),
                List.of(),
                Set.of(protectedId));

        assertEquals(1, result.merged().size());
        assertEquals(protectedEntry, result.merged().get(protectedId));
        assertFalse(result.merged().containsKey(plainId));
        assertEquals(Set.of(protectedId), result.preservedMemoryIds());
        assertEquals(Set.of(protectedId), result.remainingUnpersistedIds());
    }
}
