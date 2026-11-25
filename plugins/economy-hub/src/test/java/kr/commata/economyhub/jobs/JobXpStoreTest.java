package kr.commata.economyhub.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JobXpStoreTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @TempDir
    Path dir;

    @Test
    void writesThroughToDiskAndReloads() throws IOException {
        Path file = dir.resolve("job-xp.tsv");
        JobXpStore store = JobXpStore.open(file);
        assertTrue(store.isEmpty());
        store.put(PLAYER, "Miner", new SavedJobXp(3, 123.5));
        store.put(PLAYER, "farmer", new SavedJobXp(5, 7.25).asRestored());

        JobXpStore reopened = JobXpStore.open(file);
        assertEquals(new SavedJobXp(3, 123.5), reopened.get(PLAYER, "miner"));
        assertEquals(new SavedJobXp(5, 7.25, true), reopened.get(PLAYER, "FARMER"));
        assertEquals(2, reopened.size());
        assertFalse(Files.exists(dir.resolve("job-xp.tsv.tmp")));
    }

    @Test
    void removeIfKeepsRecordThatChangedMeanwhile() throws IOException {
        Path file = dir.resolve("job-xp.tsv");
        JobXpStore store = JobXpStore.open(file);
        SavedJobXp checked = new SavedJobXp(3, 123.5).asRestored();
        store.put(PLAYER, "miner", checked);
        store.put(PLAYER, "miner", new SavedJobXp(3, 140.0)); // 확인 중에 다시 탈퇴
        assertFalse(store.removeIf(PLAYER, "miner", checked));
        assertEquals(new SavedJobXp(3, 140.0), JobXpStore.open(file).get(PLAYER, "miner"));

        assertTrue(store.removeIf(PLAYER, "miner", new SavedJobXp(3, 140.0)));
        assertTrue(JobXpStore.open(file).isEmpty());
    }

    @Test
    void failedWriteLeavesMemoryUnchanged() throws IOException {
        Path blocker = dir.resolve("not-a-dir");
        Files.writeString(blocker, "x");
        JobXpStore store = JobXpStore.open(blocker.resolve("job-xp.tsv")); // 부모가 파일이라 쓰기 실패
        assertThrows(IOException.class, () -> store.put(PLAYER, "miner", new SavedJobXp(3, 1.0)));
        assertNull(store.get(PLAYER, "miner"));
        assertTrue(store.isEmpty());
    }

    @Test
    void brokenFileIsRejectedNotOverwritten() throws IOException {
        Path file = dir.resolve("job-xp.tsv");
        Files.writeString(file, "# header\n" + PLAYER + "\tminer\t3:abc\n", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> JobXpStore.open(file));
        Files.writeString(file, "not-a-uuid\tminer\t3:1.0\n", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> JobXpStore.open(file));
    }
}
