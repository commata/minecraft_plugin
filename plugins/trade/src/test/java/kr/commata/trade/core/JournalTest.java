package kr.commata.trade.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JournalTest {

    private static final UUID TRADE_ID = UUID.fromString("12345678-1234-1234-1234-1234567890ab");
    private static final UUID PLAYER_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID PLAYER_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static Journal.Entry sampleEntry() {
        return new Journal.Entry(
                TRADE_ID, 1_000L, 2_000L, Journal.Stage.TAKING,
                PLAYER_A, "Alice", PLAYER_B, "Bob",
                1_500L, 0L,
                List.of(new byte[]{1, 2, 3, 4}),
                List.of(new byte[]{9, 8, 7}),
                List.of("DIAMOND x4"),
                List.of("EMERALD x2"),
                Journal.SideStatus.FOUND, Journal.SideStatus.PENDING,
                Journal.SideStatus.PENDING, Journal.SideStatus.PENDING,
                Journal.SideStatus.NONE, Journal.SideStatus.NONE,
                1, 2, 1, 1,
                0, 2,
                10L, 0L,
                "", false, ""
        );
    }

    @Test
    void serializeAndDeserializeRoundTrip() throws IOException {
        Journal.Entry original = sampleEntry();
        byte[] encoded = Journal.serialize(original);
        Journal.Entry decoded = Journal.deserialize(encoded);

        assertEquals(original.tradeId(), decoded.tradeId());
        assertEquals(original.stage(), decoded.stage());
        assertEquals(original.playerA(), decoded.playerA());
        assertEquals(original.playerB(), decoded.playerB());
        assertEquals(original.aPays(), decoded.aPays());
        assertEquals(original.bPays(), decoded.bPays());
        assertEquals(1, decoded.itemsFromA().size());
        assertArrayEquals(original.itemsFromA().get(0), decoded.itemsFromA().get(0));
        assertArrayEquals(original.itemsFromB().get(0), decoded.itemsFromB().get(0));
        assertEquals(original.summaryFromA(), decoded.summaryFromA());
        assertEquals(original.takeA(), decoded.takeA());
        assertEquals(original.takeB(), decoded.takeB());
        assertEquals(original.giveAttemptB(), decoded.giveAttemptB());
        assertEquals(original.takeSaveRetriesB(), decoded.takeSaveRetriesB());
        assertEquals(original.bankTxAId(), decoded.bankTxAId());
    }

    @Test
    void deserializeRejectsBitFlipTruncationAndBadMagic() throws IOException {
        byte[] valid = Journal.serialize(sampleEntry());

        // 1비트 변조 → CRC32 실패
        byte[] flipped = valid.clone();
        flipped[flipped.length - 1] ^= 0x01;
        assertThrows(IOException.class, () -> Journal.deserialize(flipped));

        // 잘린 파일
        byte[] truncated = new byte[valid.length - 5];
        System.arraycopy(valid, 0, truncated, 0, truncated.length);
        assertThrows(IOException.class, () -> Journal.deserialize(truncated));

        // 매직 불일치
        byte[] badMagic = valid.clone();
        badMagic[0] = 'X';
        assertThrows(IOException.class, () -> Journal.deserialize(badMagic));
    }

    @Test
    void corruptJournalQuarantinesWithLockedFirstAndUnlockRequiresResolvedMarker(@TempDir Path tempDir) throws IOException {
        Journal.Store store = new Journal.Store(tempDir);
        Journal.Entry good = sampleEntry();
        store.write(good);

        // 손상 저널 주입
        Path corruptFile = store.journalDir().resolve("deadbeef-dead-beef-dead-beefdeadbeef.bin");
        Files.write(corruptFile, new byte[]{0, 1, 2, 3, 4});

        Journal.LoadReport report = store.loadAll(10_000L);
        assertEquals(1, report.entries().size());
        assertEquals(good.tradeId(), report.entries().get(0).tradeId());
        assertEquals(List.of("deadbeef-dead-beef-dead-beefdeadbeef.bin"), report.newlyQuarantined());
        assertTrue(report.locked());
        assertTrue(Files.isRegularFile(store.lockedFile()));
        assertFalse(Files.exists(corruptFile));
        assertTrue(Files.isRegularFile(store.corruptDir().resolve("deadbeef-dead-beef-dead-beefdeadbeef.bin")));

        // LOCKED 파일을 수동으로 지워도 .resolved 없는 격리 파일이 남아 있으면 계속 잠김 (TRADE.md 6·13-1장)
        Files.delete(store.lockedFile());
        assertTrue(store.isLocked());
        assertEquals(List.of("deadbeef-dead-beef-dead-beefdeadbeef.bin"), store.unresolvedCorruptFiles());

        // LOCKED 다시 만들어 두고 관리자 unlock 실행 → .resolved 먼저 fsync 후 LOCKED 삭제 (TRADE.md 6·13-2장)
        Files.writeString(store.lockedFile(), "locked");
        Journal.UnlockResult unlock = store.unlock("Admin", 20_000L);
        assertTrue(unlock.unlocked());
        assertEquals(1, unlock.resolvedFiles());
        Path marker = store.corruptDir().resolve("deadbeef-dead-beef-dead-beefdeadbeef.bin.resolved");
        assertTrue(Files.isRegularFile(marker));
        assertTrue(Files.readString(marker).contains("admin=Admin"));
        assertFalse(Files.exists(store.lockedFile()));
        assertFalse(store.isLocked());
    }

    @Test
    void serializeAndStoreRejectPayloadsExceedingSharedSizeLimits(@TempDir Path tempDir) throws IOException {
        // 단일 스택 2 MiB 초과는 사전 검증과 Entry 생성 모두 거부
        byte[] tooBigStack = new byte[Journal.MAX_ITEM_BYTES + 1];
        assertFalse(Journal.fitsItemSizeLimits(List.of(tooBigStack), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Journal.Entry(
                TRADE_ID, 1L, 1L, Journal.Stage.TAKING,
                PLAYER_A, "Alice", PLAYER_B, "Bob",
                0L, 0L,
                List.of(tooBigStack), List.of(), List.of("BIG"), List.of(),
                Journal.SideStatus.PENDING, Journal.SideStatus.FOUND,
                Journal.SideStatus.FOUND, Journal.SideStatus.PENDING,
                Journal.SideStatus.NONE, Journal.SideStatus.NONE,
                1, 1, 1, 1, 0, 0, 0L, 0L, "", false, ""));

        // 스택당 2 MiB(유효) × 9개 = 18 MiB > 전체 16 MiB → 사전 검증 거부 + serialize/write 거부 + 디스크 미기록
        byte[] twoMiB = new byte[Journal.MAX_ITEM_BYTES];
        twoMiB[0] = 42;
        List<byte[]> nineStacks = List.of(twoMiB, twoMiB, twoMiB, twoMiB, twoMiB, twoMiB, twoMiB, twoMiB, twoMiB);
        assertFalse(Journal.fitsItemSizeLimits(nineStacks, List.of()));

        Journal.Entry oversized = new Journal.Entry(
                TRADE_ID, 1L, 1L, Journal.Stage.TAKING,
                PLAYER_A, "Alice", PLAYER_B, "Bob",
                0L, 0L,
                nineStacks, List.of(), List.of(), List.of(),
                Journal.SideStatus.PENDING, Journal.SideStatus.FOUND,
                Journal.SideStatus.FOUND, Journal.SideStatus.PENDING,
                Journal.SideStatus.NONE, Journal.SideStatus.NONE,
                1, 1, 1, 1, 0, 0, 0L, 0L, "", false, "");
        assertThrows(Journal.SizeLimitExceededException.class, () -> Journal.serialize(oversized));

        Journal.Store store = new Journal.Store(tempDir);
        assertThrows(Journal.SizeLimitExceededException.class, () -> store.write(oversized));
        assertFalse(Files.exists(store.fileOf(TRADE_ID)));

        // 한도 안(7 × 2 MiB = 14 MiB)은 사전 검증 통과 + 직렬화·역직렬화 대칭 통과
        List<byte[]> sevenStacks = List.of(twoMiB, twoMiB, twoMiB, twoMiB, twoMiB, twoMiB, twoMiB);
        assertTrue(Journal.fitsItemSizeLimits(sevenStacks, List.of()));
        Journal.Entry validLarge = new Journal.Entry(
                TRADE_ID, 1L, 1L, Journal.Stage.TAKING,
                PLAYER_A, "Alice", PLAYER_B, "Bob",
                0L, 0L,
                sevenStacks, List.of(), List.of(), List.of(),
                Journal.SideStatus.PENDING, Journal.SideStatus.FOUND,
                Journal.SideStatus.FOUND, Journal.SideStatus.PENDING,
                Journal.SideStatus.NONE, Journal.SideStatus.NONE,
                1, 1, 1, 1, 0, 0, 0L, 0L, "", false, "");
        byte[] encoded = Journal.serialize(validLarge);
        Journal.Entry decoded = Journal.deserialize(encoded);
        assertEquals(7, decoded.itemsFromA().size());
        assertEquals(Journal.MAX_ITEM_BYTES, decoded.itemsFromA().get(0).length);
    }
}
