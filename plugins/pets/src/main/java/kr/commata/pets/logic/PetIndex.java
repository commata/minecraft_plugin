package kr.commata.pets.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 펫 UUID → (주인, 마지막으로 본 월드·청크). Bukkit 없이 테스트할 수 있게 순수 자료구조로 둡니다.
 * 청크가 언로드될 때 그 순간의 위치를 기록하므로, 언로드된 펫은 기록된 청크 안에 그대로 있습니다.
 */
public final class PetIndex {

    public record Entry(UUID owner, UUID world, int chunkX, int chunkZ) {
        public ChunkKey chunk() {
            return new ChunkKey(world, chunkX, chunkZ);
        }
    }

    public record ChunkKey(UUID world, int x, int z) {
    }

    private final Map<UUID, Entry> pets = new HashMap<>();

    /** @return 내용이 바뀌었으면 true (저장 필요 여부 판단용) */
    public boolean put(UUID pet, Entry entry) {
        return !Objects.equals(pets.put(pet, entry), entry);
    }

    public boolean remove(UUID pet) {
        return pets.remove(pet) != null;
    }

    public Entry get(UUID pet) {
        return pets.get(pet);
    }

    public int size() {
        return pets.size();
    }

    public Map<UUID, Entry> all() {
        return Map.copyOf(pets);
    }

    /**
     * 주인의 펫 중 {@code exclude}(이미 찾은 펫)를 뺀 나머지를 청크별로 묶습니다.
     * 같은 청크에 여러 마리가 있어도 청크는 한 번만 불러오면 됩니다.
     */
    public Map<ChunkKey, List<UUID>> chunksOf(UUID owner, Set<UUID> exclude) {
        Map<ChunkKey, List<UUID>> out = new LinkedHashMap<>();
        pets.forEach((pet, e) -> {
            if (e.owner().equals(owner) && !exclude.contains(pet)) {
                out.computeIfAbsent(e.chunk(), k -> new ArrayList<>()).add(pet);
            }
        });
        return out;
    }
}
