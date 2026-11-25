package kr.commata.bank.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private static final long BASE = 1_700_000_004_000L;

    private void save(String location, long mtimeMs) throws Exception {
        Path file = world.resolve(location).resolve(player + ".dat");
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] {1});
        Files.setLastModifiedTime(file, FileTime.fromMillis(mtimeMs));
    }

    @Test
    void worldRootFromDimensionFolder() {
        // Paper 26.3: getWorldFolder() = world/dimensions/minecraft/overworld (운영 /data/world 와 같은 구조)
        Path root = world;
        assertEquals(root, PlayerFiles.worldRoot(root.resolve("dimensions/minecraft/overworld")));
        assertEquals(root, PlayerFiles.worldRoot(root.resolve("dimensions/minecraft/the_nether")));
        // 예전 구조: 월드 폴더가 곧 루트
        assertEquals(root, PlayerFiles.worldRoot(root));
    }

    @Test
    void newLayoutUnderWorldRootIsFound() throws Exception {
        save("players/data", BASE + 5_500);
        Path dimension = world.resolve("dimensions/minecraft/overworld");
        Files.createDirectories(dimension);
        String root = PlayerFiles.worldRoot(dimension).toString();
        assertTrue(files.savedAfter(root, player, BASE));
        // 차원 폴더 기준으로 찾으면 못 찾음 (이전 버그)
        assertFalse(files.savedAfter(dimension.toString(), player, BASE));
    }

    @Test
    void oldLayoutIsStillChecked() throws Exception {
        save("playerdata", BASE + 5_500);
        assertTrue(files.savedAfter(world.toString(), player, BASE));
    }

    @Test
    void earlierSaveInSameSecondDoesNotCount() throws Exception {
        // 리뷰 재현: 파일 .100초 저장, Bank .900초 종료
        save("players/data", BASE + 100);
        assertFalse(files.savedAfter(world.toString(), player, BASE + 900));
    }

    @Test
    void laterSaveInSameSecondCountsWithMillisecondPrecision() throws Exception {
        save("players/data", BASE + 950);
        assertTrue(files.savedAfter(world.toString(), player, BASE + 900));
        assertFalse(PlayerFiles.isStrictlyAfter(BASE + 900, BASE + 900));
    }

    @Test
    void secondPrecisionTimeInSameSecondIsUnconfirmed() {
        // 초 단위 파일 시스템: 같은 초면 순서를 알 수 없음
        assertFalse(PlayerFiles.isStrictlyAfter(BASE + 1000, BASE + 1900));
        assertFalse(PlayerFiles.isStrictlyAfter(BASE, BASE + 900));
        assertTrue(PlayerFiles.isStrictlyAfter(BASE + 2000, BASE + 1900));
    }

    @Test
    void savedBeforeShutdownOrMissingDoesNotCount() throws Exception {
        assertFalse(files.savedAfter(world.toString(), player, 0));
        save("players/data", BASE - 3_000);
        assertFalse(files.savedAfter(world.toString(), player, BASE));
    }
}
