package kr.commata.economyhub.guard;

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

    public UntrustedBlocks(Plugin plugin) {
        this.plugin = plugin;
    }

    public boolean isMarked(Block block) {
        long[] bits = pdc(block).get(key(block), PersistentDataType.LONG_ARRAY);
        return Marks.get(bits, index(block));
    }

    public void mark(Block block) {
        PersistentDataContainer pdc = pdc(block);
        NamespacedKey key = key(block);
        long[] before = pdc.get(key, PersistentDataType.LONG_ARRAY);
        long[] after = Marks.set(before, index(block));
        if (after != before) {
            pdc.set(key, PersistentDataType.LONG_ARRAY, after);
        }
    }

    public void unmark(Block block) {
        PersistentDataContainer pdc = pdc(block);
        NamespacedKey key = key(block);
        long[] before = pdc.get(key, PersistentDataType.LONG_ARRAY);
        if (before == null) {
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
