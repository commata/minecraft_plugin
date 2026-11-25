package kr.commata.economyhub.guard;

import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * "자연 블록이 아닌" 좌표 표시. 청크 PDC(economyhub:untrusted, int 배열)에 저장되어 재시작 후에도 남습니다.
 * 피스톤으로 움직인 블록의 도착 좌표, 용암·물로 생긴 돌 등의 좌표가 대상입니다 (ECONOMY.md 7.2).
 */
public final class UntrustedBlocks {

    private final NamespacedKey key;
    private volatile int maxPerChunk;

    public UntrustedBlocks(Plugin plugin, int maxPerChunk) {
        this.key = new NamespacedKey(plugin, "untrusted");
        this.maxPerChunk = maxPerChunk;
    }

    public void setMaxPerChunk(int maxPerChunk) {
        this.maxPerChunk = maxPerChunk;
    }

    public boolean isMarked(Block block) {
        int[] marks = pdc(block).get(key, PersistentDataType.INTEGER_ARRAY);
        return Marks.contains(marks, pack(block));
    }

    public void mark(Block block) {
        PersistentDataContainer pdc = pdc(block);
        int[] before = pdc.get(key, PersistentDataType.INTEGER_ARRAY);
        int[] after = Marks.add(before, pack(block), maxPerChunk);
        if (after != before) {
            pdc.set(key, PersistentDataType.INTEGER_ARRAY, after);
        }
    }

    public void unmark(Block block) {
        PersistentDataContainer pdc = pdc(block);
        int[] before = pdc.get(key, PersistentDataType.INTEGER_ARRAY);
        if (before == null) {
            return;
        }
        int[] after = Marks.remove(before, pack(block));
        if (after == before) {
            return;
        }
        if (after.length == 0) {
            pdc.remove(key);
        } else {
            pdc.set(key, PersistentDataType.INTEGER_ARRAY, after);
        }
    }

    private static int pack(Block block) {
        return Marks.pack(block.getX(), block.getY(), block.getZ());
    }

    private static PersistentDataContainer pdc(Block block) {
        return block.getChunk().getPersistentDataContainer();
    }
}
