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

        Journal.Entry uncertain = original
                .withGive(TradeSession.Side.A, Journal.SideStatus.UNCERTAIN, 3, false, 3_000L)
                .withReturn(TradeSession.Side.B, Journal.SideStatus.UNCERTAIN, 2, true, 4_000L);
        Journal.Entry decodedUncertain = Journal.deserialize(Journal.serialize(uncertain));
        assertEquals(Journal.SideStatus.UNCERTAIN, decodedUncertain.giveA());
        assertEquals(3, decodedUncertain.giveAttemptA());
        assertEquals(Journal.SideStatus.UNCERTAIN, decodedUncertain.returnB());
        assertEquals(2, decodedUncertain.returnAttemptB());
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

    @Test
    void storeRemainsLockedUntilLoadAllSucceedsAndDirectoryFailureKeepsLocked(@TempDir Path tempDir) throws IOException {
        Journal.Store store = new Journal.Store(tempDir);
        // 기동 전(미로드)은 fail-closed 잠금
        assertFalse(store.isLoadSucceeded());
        assertTrue(store.isLocked());

        // journal 경로가 일반 파일이면 목록 읽기 실패 → 잠금 유지
        Files.writeString(store.journalDir(), "not-a-directory");
        assertThrows(IOException.class, () -> store.loadAll(1_000L));
        assertFalse(store.isLoadSucceeded());
        assertTrue(store.isLocked());

        // 문제 해결 후 재로딩 성공해야 비로소 잠금 해제
        Files.delete(store.journalDir());
        Journal.LoadReport report = store.loadAll(2_000L);
        assertTrue(store.isLoadSucceeded());
        assertFalse(report.locked());
        assertFalse(store.isLocked());
    }

    @Test
    void lockedWriteFailureDoesNotMoveCorruptFileAndKeepsStoreLocked(@TempDir Path tempDir) throws IOException {
        Journal.Store store = new Journal.Store(tempDir);
        Files.createDirectories(store.journalDir());

        // 손상 저널 배치
        Path corruptFile = store.journalDir().resolve("11111111-1111-1111-1111-111111111111.bin");
        Files.write(corruptFile, new byte[]{1, 2, 3});

        // LOCKED 경로를 디렉터리로 막아 LOCKED 선기록이 반드시 실패하게 유도
        Files.createDirectory(store.lockedFile());

        assertThrows(IOException.class, () -> store.loadAll(10_000L));
        // LOCKED 선기록이 실패했으므로 손상 파일은 corrupt/ 로 이동되지 않고 그대로 남아야 함
        assertTrue(Files.isRegularFile(corruptFile));
        assertFalse(Files.exists(store.corruptDir().resolve("11111111-1111-1111-1111-111111111111.bin")));
        // 로드 실패 플래그로 Trade 전체 잠금 유지
        assertFalse(store.isLoadSucceeded());
        assertTrue(store.isLocked());

        // 장애 제거 후 재로딩 → LOCKED 생성 + 손상 파일 격리 + 잠금 유지
        Files.delete(store.lockedFile());
        Journal.LoadReport reloaded = store.loadAll(20_000L);
        assertTrue(store.isLoadSucceeded());
        assertTrue(reloaded.locked());
        assertTrue(store.isLocked());
        assertTrue(Files.isRegularFile(store.lockedFile()));
        assertFalse(Files.exists(corruptFile));
        assertTrue(Files.isRegularFile(store.corruptDir().resolve("11111111-1111-1111-1111-111111111111.bin")));

        // 관리자 unlock 후 재로딩 검증 통과 → 잠금 해제
        Journal.UnlockResult unlock = store.unlock("Admin", 30_000L);
        assertTrue(unlock.unlocked());
        Journal.LoadReport verified = store.loadAll(31_000L);
        assertFalse(verified.locked());
        assertFalse(store.isLocked());
    }
}
