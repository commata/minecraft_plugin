package kr.commata.market.bridge;

import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.persistence.PersistentDataType;

/**
 * EconomyHub 가 청크 PDC에 기록하는 공용 설치·피스톤·유체 블록 표시({@code economyhub:u<구역 y>})를
 * 직접 읽습니다 (ECONOMY.md 14.2, 15.5).
 *
 * <p>EconomyHub 에 컴파일 의존을 만들지 않고 동일한 청크 PDC 비트맵 규격({@code long[64]})을 공유합니다.
 */
public final class UntrustedBlockReader {

    private static final String NAMESPACE = "economyhub";
    private static final int WORDS = 64;

    private UntrustedBlockReader() {
    }

    public static boolean isMarked(Block block) {
        int section = block.getY() >> 4;
        NamespacedKey key = new NamespacedKey(NAMESPACE, "u" + section);
        long[] bits = block.getChunk().getPersistentDataContainer().get(key, PersistentDataType.LONG_ARRAY);
        if (bits == null || bits.length != WORDS) {
            return false;
        }
        int index = ((block.getY() & 15) << 8) | ((block.getZ() & 15) << 4) | (block.getX() & 15);
        return (bits[index >>> 6] & (1L << (index & 63))) != 0;
    }
}
