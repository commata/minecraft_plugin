package kr.commata.economyhub.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LeaveTimeStoreTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @TempDir
    Path dir;

    @Test
    void leaveTimeSurvivesRestartAndOldEntriesArePruned() throws IOException {
        Path file = dir.resolve("job-leaves.tsv");
        LeaveTimeStore store = LeaveTimeStore.open(file);
        long now = 1_800_000_000_000L;
        store.record(B, now - LeaveTimeStore.KEEP_MS - 1);
        store.record(A, now); // B 는 30일 넘게 지나서 이번 저장에서 빠짐

        LeaveTimeStore reopened = LeaveTimeStore.open(file);
        assertEquals(now, reopened.get(A));
        assertEquals(0L, reopened.get(B));
        assertEquals(0L, reopened.get(UUID.randomUUID()));
    }

    @Test
    void brokenFileIsRejected() throws IOException {
        Path file = dir.resolve("job-leaves.tsv");
        Files.writeString(file, A + "\tabc\n");
        assertThrows(IOException.class, () -> LeaveTimeStore.open(file));
    }
}
