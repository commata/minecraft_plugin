package kr.commata.common.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 작은 기록 파일·저널을 임시 파일 → fsync → 원자적 바꿔치기로 씁니다.
 * 돌아오면 디스크에 반영된 상태입니다 (AGENTS.md 5.1, TRADE.md 5장). Bukkit 에 의존하지 않습니다.
 */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    /** UTF-8 문자열을 원자적으로 기록합니다. */
    public static void write(Path file, String content) throws IOException {
        writeBytes(file, content.getBytes(StandardCharsets.UTF_8));
    }

    /** 바이트 배열을 임시 파일에 쓰고 fsync 한 뒤 원자적으로 대상 경로에 바꿔치기합니다. */
    public static void writeBytes(Path file, byte[] content) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = dir.resolve(file.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        try (FileChannel dirChannel = FileChannel.open(dir, StandardOpenOption.READ)) {
            dirChannel.force(true); // Linux: 파일 이름 바꾼 것까지 디스크에 반영
        } catch (IOException | UnsupportedOperationException e) {
            // Windows 는 폴더를 열 수 없음. 파일 내용은 이미 fsync 됨
        }
    }
}
