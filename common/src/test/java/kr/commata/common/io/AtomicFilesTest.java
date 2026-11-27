package kr.commata.common.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link AtomicFiles} 텍스트·바이너리 원자적 쓰기 및 임시 파일 정리 검증. */
class AtomicFilesTest {

    @TempDir
    Path temp;

    @Test
    void writesAndOverwritesUtf8TextWithoutLeavingTempFile() throws Exception {
        Path file = temp.resolve("nested/data.tsv");
        AtomicFiles.write(file, "첫 줄\n둘째 줄\n");
        assertEquals("첫 줄\n둘째 줄\n", Files.readString(file, StandardCharsets.UTF_8));
        assertFalse(Files.exists(file.resolveSibling("data.tsv.tmp")));

        AtomicFiles.write(file, "덮어쓴 내용\n");
        assertEquals("덮어쓴 내용\n", Files.readString(file, StandardCharsets.UTF_8));
        assertFalse(Files.exists(file.resolveSibling("data.tsv.tmp")));
    }

    @Test
    void writesArbitraryBinaryBytes() throws Exception {
        Path file = temp.resolve("journal/trade.bin");
        byte[] payload = new byte[] {0, 1, 2, (byte) 0xFF, 0x7F, (byte) 0x80};
        AtomicFiles.writeBytes(file, payload);
        assertArrayEquals(payload, Files.readAllBytes(file));
        assertFalse(Files.exists(file.resolveSibling("trade.bin.tmp")));
    }
}
