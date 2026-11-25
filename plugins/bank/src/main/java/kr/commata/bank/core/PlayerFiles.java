package kr.commata.bank.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * 서버가 플레이어 파일(인벤토리 포함)을 Bank 종료 뒤에 저장했는지 확인합니다.
 *
 * <p>MC 26.1 부터 저장 구조가 바뀌어 플레이어 파일은 월드 루트의 {@code players/data/<uuid>.dat} 에 있고,
 * {@code World#getWorldFolder()} 는 차원 폴더({@code world/dimensions/minecraft/overworld})를 돌려줍니다.
 * 그래서 {@link #worldRoot} 로 월드 루트를 구한 뒤 {@code players/data}, 예전 구조의 {@code playerdata} 를 봅니다.
 */
public final class PlayerFiles implements BankStore.SaveCheck {

    private static final List<String> LOCATIONS = List.of("players/data", "playerdata");

    /**
     * World#getWorldFolder() 결과에서 월드 루트를 구합니다.
     * {@code <루트>/dimensions/<namespace>/<이름>} 이면 {@code <루트>}, 아니면 그대로 (예전 구조).
     */
    public static Path worldRoot(Path worldFolder) {
        Path abs = worldFolder.toAbsolutePath().normalize();
        for (Path p = abs; p != null; p = p.getParent()) {
            Path name = p.getFileName();
            if (name != null && name.toString().equals("dimensions") && p.getParent() != null) {
                return p.getParent();
            }
        }
        return abs;
    }

    @Override
    public boolean savedAfter(String worldRoot, UUID player, long sinceMs) {
        for (String loc : LOCATIONS) {
            Path file = Path.of(worldRoot).resolve(loc).resolve(player + ".dat");
            try {
                if (Files.isRegularFile(file) && isStrictlyAfter(Files.getLastModifiedTime(file).toMillis(), sinceMs)) {
                    return true;
                }
            } catch (IOException ignored) {
                // 다음 위치
            }
        }
        return false;
    }

    /**
     * 파일 시각이 종료 시각보다 확실히 늦은지.
     * <ul>
     *   <li>밀리초 단위 값이 있으면(1000 으로 나눠떨어지지 않음) 밀리초로 엄격 비교 ({@code >})</li>
     *   <li>정확히 초 단위 값이면 초 단위 파일 시스템일 수 있으므로, 종료 시각이 속한 초보다 뒤의 초일 때만 인정.
     *       같은 초면 순서를 구별할 수 없으므로 미확인(false)</li>
     * </ul>
     */
    static boolean isStrictlyAfter(long fileMs, long sinceMs) {
        if (Math.floorMod(fileMs, 1000L) != 0) {
            return fileMs > sinceMs;
        }
        return Math.floorDiv(fileMs, 1000L) > Math.floorDiv(sinceMs, 1000L);
    }
}
