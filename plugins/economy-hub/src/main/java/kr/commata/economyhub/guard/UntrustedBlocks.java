package kr.commata.economyhub.guard;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * "자연 블록이 아닌" 좌표 표시. 청크 PDC 에 구역(16블록 높이)마다 비트맵 하나(economyhub:u&lt;구역&gt;, long 64개)로
 * 저장되어 재시작 후에도 남고, 개수 상한이 없어 오래된 표시가 사라지지 않습니다.
 * 피스톤으로 움직인 블록의 도착 좌표, 용암·물로 생긴 돌 등의 좌표가 대상입니다 (ECONOMY.md 7.2).
 */
public final class UntrustedBlocks {

    private final Plugin plugin;
    private final Set<String> warnedKeys = ConcurrentHashMap.newKeySet();

    public UntrustedBlocks(Plugin plugin) {
        this.plugin = plugin;
    }

    public boolean isMarked(Block block) {
        PersistentDataContainer pdc = pdc(block);
        NamespacedKey key = key(block);
        if (!hasKeySafe(pdc, key)) {
            return false;
        }
        long[] bits = getBitsSafe(pdc, key);
        if (bits == null || bits.length != Marks.WORDS) {
            // fail-closed: PDC 키가 있으나 타입 불일치이거나 길이가 64가 아니면
            // 보상 악용 방지를 위해 표시된 블록(untrusted)으로 취급
            return true;
        }
        return Marks.get(bits, index(block));
    }

    public void mark(Block block) {
        PersistentDataContainer pdc = pdc(block);
        NamespacedKey key = key(block);
        if (hasKeySafe(pdc, key)) {
            long[] before = getBitsSafe(pdc, key);
            if (before == null || before.length != Marks.WORDS) {
                // 손상 배열(타입/길이 불일치)은 기존 데이터 보존을 위해 덮어쓰지 않음
                return;
            }
            long[] after = Marks.set(before, index(block));
            if (after != before) {
                pdc.set(key, PersistentDataType.LONG_ARRAY, after);
            }
        } else {
            long[] after = Marks.set(null, index(block));
            pdc.set(key, PersistentDataType.LONG_ARRAY, after);
        }
    }

    public void unmark(Block block) {
        PersistentDataContainer pdc = pdc(block);
        NamespacedKey key = key(block);
        if (!hasKeySafe(pdc, key)) {
            return;
        }
        long[] before = getBitsSafe(pdc, key);
        if (before == null || before.length != Marks.WORDS) {
            // 손상 배열은 덮어쓰거나 지우지 않고 보존
            return;
        }
        long[] after = Marks.clear(before, index(block));
        if (after == before) {
            return;
        }
        if (after == null) {
            pdc.remove(key);
        } else {
            pdc.set(key, PersistentDataType.LONG_ARRAY, after);
        }
    }

    private boolean hasKeySafe(PersistentDataContainer pdc, NamespacedKey key) {
        try {
            return pdc.has(key);
        } catch (Exception e) {
            logCorruptedOnce(key, "키 확인 예외: " + e.getMessage());
            return false;
        }
    }

    private long[] getBitsSafe(PersistentDataContainer pdc, NamespacedKey key) {
        try {
            if (!pdc.has(key, PersistentDataType.LONG_ARRAY)) {
                logCorruptedOnce(key, "타입 불일치(LONG_ARRAY 가 아님)");
                return null;
            }
            long[] bits = pdc.get(key, PersistentDataType.LONG_ARRAY);
            if (bits != null && bits.length != Marks.WORDS) {
                logCorruptedOnce(key, "길이 불일치(" + bits.length + " != " + Marks.WORDS + ")");
            }
            return bits;
        } catch (IllegalArgumentException e) {
            logCorruptedOnce(key, "조회 실패: " + e.getMessage());
            return null;
        }
    }

    private void logCorruptedOnce(NamespacedKey key, String reason) {
        String keyStr = key.asString();
        if (warnedKeys.add(keyStr)) {
            plugin.getLogger().warning("청크 구역 PDC 형식 손상 (" + keyStr + ", " + reason + "), 덮어쓰지 않고 보존합니다.");
        }
    }

    private NamespacedKey key(Block block) {
        // NamespacedKey 는 [a-z0-9/._-] 만 허용. 음수 구역은 "u-4" 처럼 하이픈
        return new NamespacedKey(plugin, "u" + Marks.section(block.getY()));
    }

    private static int index(Block block) {
        return Marks.index(block.getX(), block.getY(), block.getZ());
    }

    private static PersistentDataContainer pdc(Block block) {
        return block.getChunk().getPersistentDataContainer();
    }
}
