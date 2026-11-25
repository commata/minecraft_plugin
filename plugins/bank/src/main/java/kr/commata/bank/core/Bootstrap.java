package kr.commata.bank.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.function.LongSupplier;

/**
 * 첫 설치와 데이터 분실을 구분해서 DB 를 엽니다 (ECONOMY.md 2.3).
 * <ul>
 *   <li>plugins/Bank 폴더 자체가 없음 → 첫 설치: 빈 bank.db 생성</li>
 *   <li>폴더가 있고 bank.db 도 있음 → 열기. 무결성 검사·설치 표시 실패 시 잠금</li>
 *   <li>폴더는 있는데 bank.db 가 없음 → 새로 만들지 않고 잠금</li>
 * </ul>
 */
public final class Bootstrap {

    private Bootstrap() {
    }

    public sealed interface Result permits Ready, Locked {
    }

    public record Ready(BankStore store, boolean firstInstall) implements Result {
    }

    public record Locked(String reason) implements Result {
    }

    /**
     * @param dataDir       plugins/Bank
     * @param folderExisted 플러그인이 아무것도 만들기 전에 dataDir 이 있었는지
     */
    public static Result open(Path dataDir, boolean folderExisted, LongSupplier clock) {
        Path db = dataDir.resolve(BankStore.DB_FILE);
        if (!folderExisted) {
            try {
                Files.createDirectories(dataDir);
                return new Ready(BankStore.create(db, clock), true);
            } catch (IOException | SQLException e) {
                return new Locked("첫 설치 중 DB 생성 실패: " + e.getMessage());
            }
        }
        if (!Files.exists(db)) {
            return new Locked(dataDir.getFileName() + " 폴더는 있는데 " + BankStore.DB_FILE + " 가 없음");
        }
        try {
            return new Ready(BankStore.open(db, clock), false);
        } catch (SQLException e) {
            return new Locked(BankStore.DB_FILE + " 열기 실패: " + e.getMessage());
        }
    }
}
