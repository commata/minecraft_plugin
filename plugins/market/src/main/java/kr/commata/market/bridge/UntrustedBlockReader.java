package kr.commata.market.bridge;

import kr.commata.market.core.BlockTrustJudge;
import kr.commata.market.core.BlockTrustState;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * EconomyHub 가 청크 PDC에 기록하는 공용 설치·피스톤·유체 블록 표시({@code economyhub:u<구역 y>})를
 * 직접 읽습니다 (ECONOMY.md 14.2, 15.5).
 *
 * <p>EconomyHub 에 컴파일 의존을 만들지 않고 동일한 청크 PDC 비트맵 규격({@code long[64]})을 공유합니다.
 * 비트맵이 깨졌거나 형식이 다르면 fail-closed 로 판정 불가({@link BlockTrustState#UNKNOWN}) 처리합니다.
 */
public final class UntrustedBlockReader {

    private static final String NAMESPACE = "economyhub";

    private UntrustedBlockReader() {
    }

    public static NamespacedKey sectionKey(int section) {
        return new NamespacedKey(NAMESPACE, "u" + section);
    }

    /**
     * 블록의 신뢰 상태를 3상태(NATURAL / MARKED / UNKNOWN)로 판정합니다.
     */
    public static BlockTrustState judge(Block block) {
        int section = BlockTrustJudge.section(block.getY());
        NamespacedKey key = sectionKey(section);
        PersistentDataContainer pdc = block.getChunk().getPersistentDataContainer();

        boolean keyExists;
        try {
            keyExists = pdc.has(key);
        } catch (Exception e) {
            return BlockTrustState.UNKNOWN;
        }
        if (!keyExists) {
            return BlockTrustState.NATURAL;
        }

        long[] bits;
        try {
            if (!pdc.has(key, PersistentDataType.LONG_ARRAY)) {
                return BlockTrustState.UNKNOWN;
            }
            bits = pdc.get(key, PersistentDataType.LONG_ARRAY);
        } catch (IllegalArgumentException e) {
            return BlockTrustState.UNKNOWN;
        }

        int index = BlockTrustJudge.index(block.getX(), block.getY(), block.getZ());
        return BlockTrustJudge.judge(true, bits, index);
    }

    /**
     * 표시된 블록이거나 형식 불명(fail-closed)으로 신뢰할 수 없는 블록인지 여부를 반환합니다.
     * 자연 블록({@link BlockTrustState#NATURAL})이 확실할 때만 {@code false}를 반환합니다.
     */
    public static boolean isMarked(Block block) {
        return judge(block) != BlockTrustState.NATURAL;
    }
}
