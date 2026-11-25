package kr.commata.bank.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * 서버가 플레이어 파일(인벤토리 포함)을 언제 저장했는지 확인합니다.
 * 버전에 따라 위치가 다를 수 있어 알려진 위치를 차례로 봅니다: playerdata/&lt;uuid&gt;.dat, players/data/&lt;uuid&gt;.dat
 */
public final class PlayerFiles implements BankStore.SaveCheck {

    private static final List<String> LOCATIONS = List.of("playerdata", "players/data");

    @Override
    public boolean savedAfter(String worldDir, UUID player, long sinceMs) {
        // 파일 시각이 초 단위인 파일 시스템도 있으므로 초 단위로 비교
        long sinceSec = Math.floorDiv(sinceMs, 1000L);
        for (String loc : LOCATIONS) {
            Path file = Path.of(worldDir).resolve(loc).resolve(player + ".dat");
            try {
                if (Files.isRegularFile(file)
                        && Math.floorDiv(Files.getLastModifiedTime(file).toMillis(), 1000L) >= sinceSec) {
                    return true;
                }
            } catch (IOException ignored) {
                // 다음 위치
            }
        }
        return false;
    }
}
