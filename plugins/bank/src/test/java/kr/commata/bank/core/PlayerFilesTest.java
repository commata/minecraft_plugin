package kr.commata.bank.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayerFilesTest {

    @TempDir
    Path world;

    private final PlayerFiles files = new PlayerFiles();
    private final UUID player = UUID.randomUUID();

    private void save(String location, long mtimeMs) throws Exception {
        Path file = world.resolve(location).resolve(player + ".dat");
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] {1});
        Files.setLastModifiedTime(file, FileTime.fromMillis(mtimeMs));
    }

    @Test
    void savedAfterShutdownCounts() throws Exception {
        save("playerdata", 1_700_000_005_000L);
        assertTrue(files.savedAfter(world.toString(), player, 1_700_000_004_000L));
    }

    @Test
    void savedBeforeShutdownDoesNotCount() throws Exception {
        save("playerdata", 1_700_000_000_000L);
        assertFalse(files.savedAfter(world.toString(), player, 1_700_000_004_000L));
    }

    @Test
    void newerLayoutIsChecked() throws Exception {
        save("players/data", 1_700_000_005_000L);
        assertTrue(files.savedAfter(world.toString(), player, 1_700_000_004_000L));
    }

    @Test
    void missingFileDoesNotCount() {
        assertFalse(files.savedAfter(world.toString(), player, 0));
    }
}
