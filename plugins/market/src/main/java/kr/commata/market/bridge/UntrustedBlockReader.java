package kr.commata.market.bridge;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import kr.commata.market.core.BlockTrustJudge;
import kr.commata.market.core.BlockTrustState;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * EconomyHub 및 Market 자체 청크 PDC 에 기록된 설치·피스톤·유체 블록 표시를 읽고 씁니다 (ECONOMY.md 14.2, 15.5).
 *
 * <p>EconomyHub 공용 키({@code economyhub:u<구역 y>})와 Market 자체 키({@code market:u<구역 y>}) 두 비트맵을 확인하여,
 * 어느 한쪽이라도 설치 표시되어 있거나 손상(판정 불가)된 경우 fail-closed 로 처리합니다.
 * EconomyHub 가 일시 중단된 동안 플레이어가 배치한 블록도 Market 자체 비트맵에 기록되어 자연산으로 집계되지 않습니다.
 */
public final class UntrustedBlockReader {

    public static final String HUB_NAMESPACE = "economyhub";
    public static final String MARKET_NAMESPACE = "market";

    private static final Logger LOGGER = Bukkit.getLogger();
    private static final Set<String> WARNED_KEYS = ConcurrentHashMap.newKeySet();

    private UntrustedBlockReader() {
    }

    public static NamespacedKey hubKey(int section) {
        return new NamespacedKey(HUB_NAMESPACE, "u" + section);
    }

    public static NamespacedKey marketKey(int section) {
        return new NamespacedKey(MARKET_NAMESPACE, "u" + section);
    }

    /** 호환용 편의 메서드 (기본: EconomyHub 키). */
    public static NamespacedKey sectionKey(int section) {
        return hubKey(section);
    }

    /**
     * 특정 네임스페이스의 청크 PDC 비트맵에서 블록 상태를 안전하게 조회합니다.
     */
    private static BlockTrustState judgeNamespace(Block block, String namespace) {
        int section = BlockTrustJudge.section(block.getY());
        NamespacedKey key = new NamespacedKey(namespace, "u" + section);
        PersistentDataContainer pdc;
        try {
            pdc = block.getChunk().getPersistentDataContainer();
        } catch (Exception e) {
            return BlockTrustState.UNKNOWN;
        }

        boolean keyExists;
        try {
            keyExists = pdc.has(key);
        } catch (Exception e) {
            return BlockTrustState.UNKNOWN;
        }
        if (!keyExists) {
            return BlockTrustState.NATURAL;
        }

        try {
            if (!pdc.has(key, PersistentDataType.LONG_ARRAY)) {
                if (WARNED_KEYS.add(key.toString())) {
                    LOGGER.warning("[Market] 청크 PDC 키 " + key + " 의 타입이 LONG_ARRAY 가 아닙니다 (1회 경고, 판정 차단)");
                }
                return BlockTrustState.UNKNOWN;
            }
            long[] bits = pdc.get(key, PersistentDataType.LONG_ARRAY);
            if (bits == null || bits.length != BlockTrustJudge.WORDS) {
                if (WARNED_KEYS.add(key.toString())) {
                    LOGGER.warning("[Market] 청크 PDC 키 " + key + " 의 비트맵 길이가 64가 아닙니다 (1회 경고, 판정 차단)");
                }
                return BlockTrustState.UNKNOWN;
            }
            int index = BlockTrustJudge.index(block.getX(), block.getY(), block.getZ());
            return BlockTrustJudge.judge(true, bits, index);
        } catch (Exception e) {
            if (WARNED_KEYS.add(key.toString())) {
                LOGGER.warning("[Market] 청크 PDC 키 " + key + " 읽기 중 예외 발생: " + e.getMessage() + " (1회 경고, 판정 차단)");
            }
            return BlockTrustState.UNKNOWN;
        }
    }

    /**
     * 블록의 신뢰 상태를 3상태(NATURAL / MARKED / UNKNOWN)로 판정합니다.
     * EconomyHub 비트맵과 Market 자체 비트맵 중 하나라도 표시/불명이면 fail-closed 로 판정합니다.
     */
    public static BlockTrustState judge(Block block) {
        BlockTrustState hubState = judgeNamespace(block, HUB_NAMESPACE);
        BlockTrustState marketState = judgeNamespace(block, MARKET_NAMESPACE);
        return BlockTrustJudge.combine(hubState, marketState);
    }

    /**
     * 표시된 블록이거나 형식 불명(fail-closed)으로 신뢰할 수 없는 블록인지 여부를 반환합니다.
     * 자연 블록({@link BlockTrustState#NATURAL})이 확실할 때만 {@code false}를 반환합니다.
     */
    public static boolean isMarked(Block block) {
        return judge(block) != BlockTrustState.NATURAL;
    }

    /**
     * Market 자체 청크 PDC 비트맵({@code market:u<구역 y>})에 설치 블록을 표시합니다.
     * 손상된 배열이 감지되면 덮어쓰지 않고 보존합니다.
     */
    public static void markMarket(Block block) {
        int section = BlockTrustJudge.section(block.getY());
        NamespacedKey key = marketKey(section);
        try {
            PersistentDataContainer pdc = block.getChunk().getPersistentDataContainer();
            long[] bits = null;
            if (pdc.has(key)) {
                if (!pdc.has(key, PersistentDataType.LONG_ARRAY)) {
                    if (WARNED_KEYS.add(key.toString())) {
                        LOGGER.warning("[Market] 청크 PDC 키 " + key + " 타입 불일치로 덮어쓰지 않고 보존합니다 (1회 경고)");
                    }
                    return;
                }
                bits = pdc.get(key, PersistentDataType.LONG_ARRAY);
                if (bits != null && bits.length != BlockTrustJudge.WORDS) {
                    if (WARNED_KEYS.add(key.toString())) {
                        LOGGER.warning("[Market] 청크 PDC 키 " + key + " 비트맵 손상으로 덮어쓰지 않고 보존합니다 (1회 경고)");
                    }
                    return;
                }
            }

            int index = BlockTrustJudge.index(block.getX(), block.getY(), block.getZ());
            long[] updated = BlockTrustJudge.setBit(bits, index);
            if (updated != null && updated.length == BlockTrustJudge.WORDS) {
                pdc.set(key, PersistentDataType.LONG_ARRAY, updated);
            }
        } catch (Exception e) {
            if (WARNED_KEYS.add(key.toString())) {
                LOGGER.warning("[Market] 청크 PDC " + key + " 마킹 중 오류 발생: " + e.getMessage() + " (1회 경고)");
            }
        }
    }

    /**
     * Market 자체 청크 PDC 비트맵({@code market:u<구역 y>})에서 블록 표시를 해제합니다.
     * 비트맵이 완전히 비면 키를 삭제합니다.
     */
    public static void unmarkMarket(Block block) {
        int section = BlockTrustJudge.section(block.getY());
        NamespacedKey key = marketKey(section);
        try {
            PersistentDataContainer pdc = block.getChunk().getPersistentDataContainer();
            if (!pdc.has(key)) {
                return;
            }
            if (!pdc.has(key, PersistentDataType.LONG_ARRAY)) {
                return; // 타입 불일치 시 건드리지 않음
            }
            long[] bits = pdc.get(key, PersistentDataType.LONG_ARRAY);
            if (bits == null || bits.length != BlockTrustJudge.WORDS) {
                return; // 손상 배열 보존
            }

            int index = BlockTrustJudge.index(block.getX(), block.getY(), block.getZ());
            long[] updated = BlockTrustJudge.clearBit(bits, index);
            if (updated == null || BlockTrustJudge.isEmpty(updated)) {
                pdc.remove(key);
            } else if (updated.length == BlockTrustJudge.WORDS) {
                pdc.set(key, PersistentDataType.LONG_ARRAY, updated);
            }
        } catch (Exception e) {
            if (WARNED_KEYS.add(key.toString())) {
                LOGGER.warning("[Market] 청크 PDC " + key + " 해제 중 오류 발생: " + e.getMessage() + " (1회 경고)");
            }
        }
    }
}
