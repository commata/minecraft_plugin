package kr.commata.market.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.function.LongSupplier;

/**
 * Market 첫 설치와 DB 분실·손상을 구분합니다 (AGENTS.md 2.8, ECONOMY.md 2.3).
 *
 * <p>운영 첫 배포 때 {@code config.yml}을 먼저 복사해 둘 수 있으므로, {@code market.db}와
 * 설치 표시 파일({@code .installed})이 모두 없을 때만 새 DB를 만듭니다. 이미 설치된 서버에서
 * {@code market.db}가 사라지거나 손상되면 새 DB를 만들지 않고 잠금 모드로 뜹니다.
 */
public final class MarketBootstrap {

    static final String INSTALLED_MARKER = ".installed";

    public sealed interface Result permits Ready, Locked {
    }

    public record Ready(MarketStore store, boolean firstInstall) implements Result {
    }

    public record Locked(String reason) implements Result {
    }

    private MarketBootstrap() {
    }

    public static Result open(Path dataDir, LongSupplier clock) {
        Path dbFile = dataDir.resolve(MarketStore.DB_FILE);
        Path marker = dataDir.resolve(INSTALLED_MARKER);
        Path wal = dataDir.resolve(MarketStore.DB_FILE + "-wal");
        Path shm = dataDir.resolve(MarketStore.DB_FILE + "-shm");

        if (Files.exists(dbFile)) {
            try {
                MarketStore store = MarketStore.open(dbFile, clock);
                ensureMarker(marker);
                return new Ready(store, false);
            } catch (SQLException e) {
                return new Locked("market.db 열기·무결성 검사 실패: " + e.getMessage());
            }
        }

        if (Files.exists(marker) || Files.exists(wal) || Files.exists(shm)) {
            return new Locked("이미 설치된 Market 폴더에 market.db 가 없습니다 (분실·삭제 의심). 새 DB를 만들지 않습니다.");
        }

        try {
            Files.createDirectories(dataDir);
            MarketStore store = MarketStore.create(dbFile, clock);
            ensureMarker(marker);
            return new Ready(store, true);
        } catch (IOException | SQLException e) {
            return new Locked("첫 설치 market.db 생성 실패: " + e.getMessage());
        }
    }

    private static void ensureMarker(Path marker) {
        try {
            if (!Files.exists(marker)) {
                Files.writeString(marker, "installed\n");
            }
        } catch (IOException ignored) {
            // DB 자체가 이미 정상 설치됨
        }
    }
}
