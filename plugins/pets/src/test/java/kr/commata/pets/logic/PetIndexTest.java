package kr.commata.pets.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PetIndexTest {

    private final UUID me = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final UUID world = UUID.randomUUID();

    @Test
    void putReportsChangesOnlyWhenEntryDiffers() {
        PetIndex index = new PetIndex();
        UUID pet = UUID.randomUUID();
        assertTrue(index.put(pet, new PetIndex.Entry(me, world, 1, 2)));
        assertFalse(index.put(pet, new PetIndex.Entry(me, world, 1, 2)));
        assertTrue(index.put(pet, new PetIndex.Entry(me, world, 3, 2)));
        assertEquals(1, index.size());
    }

    @Test
    void chunksOfGroupsMyPetsByChunkAndSkipsExcludedAndOthers() {
        PetIndex index = new PetIndex();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        UUID e = UUID.randomUUID();
        index.put(a, new PetIndex.Entry(me, world, 0, 0));
        index.put(b, new PetIndex.Entry(me, world, 0, 0));
        index.put(c, new PetIndex.Entry(me, world, 5, 5));
        index.put(d, new PetIndex.Entry(other, world, 0, 0));
        index.put(e, new PetIndex.Entry(me, world, 9, 9));

        Map<PetIndex.ChunkKey, List<UUID>> chunks = index.chunksOf(me, Set.of(e));

        assertEquals(2, chunks.size());
        assertEquals(Set.of(a, b), Set.copyOf(chunks.get(new PetIndex.ChunkKey(world, 0, 0))));
        assertEquals(List.of(c), chunks.get(new PetIndex.ChunkKey(world, 5, 5)));
    }

    @Test
    void removeForgetsPet() {
        PetIndex index = new PetIndex();
        UUID pet = UUID.randomUUID();
        index.put(pet, new PetIndex.Entry(me, world, 0, 0));
        assertTrue(index.remove(pet));
        assertFalse(index.remove(pet));
        assertTrue(index.chunksOf(me, Set.of()).isEmpty());
    }
}
