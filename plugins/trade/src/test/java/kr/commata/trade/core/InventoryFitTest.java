package kr.commata.trade.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class InventoryFitTest {

    private static List<InventoryFit.StackSlot> fullStorage(String key, int amount, int maxStack) {
        List<InventoryFit.StackSlot> slots = new ArrayList<>(36);
        for (int i = 0; i < 36; i++) {
            slots.add(new InventoryFit.StackSlot(key, amount, maxStack));
        }
        return slots;
    }

    @Test
    void fitsIntoSpaceFreedByOwnOffers() {
        // 36칸이 꽉 찼지만 0번·1번 칸 전부 자기 제시분이면 비워지므로 2칸짜리 수신 물건이 들어감
        List<InventoryFit.StackSlot> storage = fullStorage("STONE", 64, 64);
        Map<Integer, Integer> ownOffer = Map.of(0, 64, 1, 64);
        List<InventoryFit.IncomingStack> incoming = List.of(
                new InventoryFit.IncomingStack("DIAMOND_SWORD", 1, 1),
                new InventoryFit.IncomingStack("SHULKER_BOX", 1, 1)
        );
        assertEquals(0, InventoryFit.missingSlots(storage, ownOffer, incoming));
        assertTrue(InventoryFit.fitsAll(storage, ownOffer, incoming));

        // 3개 받으려면 1칸 부족
        List<InventoryFit.IncomingStack> incoming3 = List.of(
                new InventoryFit.IncomingStack("DIAMOND_SWORD", 1, 1),
                new InventoryFit.IncomingStack("SHULKER_BOX", 1, 1),
                new InventoryFit.IncomingStack("BUNDLE", 1, 1)
        );
        assertEquals(1, InventoryFit.missingSlots(storage, ownOffer, incoming3));
    }

    @Test
    void mergesIntoExistingPartialStacksBeforeUsingEmptySlots() {
        List<InventoryFit.StackSlot> storage = fullStorage("COBBLESTONE", 64, 64);
        storage.set(0, new InventoryFit.StackSlot("DIAMOND", 40, 64));
        storage.set(1, new InventoryFit.StackSlot("DIAMOND", 50, 64));

        // 빈칸은 0개지만 기존 다이아 두 칸에 24 + 14 = 38개 여유 있음
        assertEquals(0, InventoryFit.missingSlots(storage, Map.of(),
                List.of(new InventoryFit.IncomingStack("DIAMOND", 38, 64))));
        // 39개면 1개가 남아서 빈칸 1칸 필요
        assertEquals(1, InventoryFit.missingSlots(storage, Map.of(),
                List.of(new InventoryFit.IncomingStack("DIAMOND", 39, 64))));
    }

    @Test
    void partialOfferDoesNotFreeSlotUnlessReducedToZero() {
        List<InventoryFit.StackSlot> storage = fullStorage("COBBLESTONE", 64, 64);
        // 0번 칸에서 63개만 제시하면 1개가 남으므로 다른 아이템을 넣을 빈칸은 0개
        Map<Integer, Integer> partialOffer = Map.of(0, 63);
        assertFalse(InventoryFit.fitsAll(storage, partialOffer,
                List.of(new InventoryFit.IncomingStack("EMERALD", 1, 64))));
        // 같은 COBBLESTONE 63개는 그 칸에 합쳐서 들어감
        assertTrue(InventoryFit.fitsAll(storage, partialOffer,
                List.of(new InventoryFit.IncomingStack("COBBLESTONE", 63, 64))));
    }

    @Test
    void countsMultipleMissingSlotsAccurately() {
        List<InventoryFit.StackSlot> storage = fullStorage("STONE", 64, 64);
        List<InventoryFit.IncomingStack> incoming = List.of(
                new InventoryFit.IncomingStack("ENDER_PEARL", 32, 16), // 2칸 필요
                new InventoryFit.IncomingStack("DIAMOND_PICKAXE", 1, 1) // 1칸 필요
        );
        assertEquals(3, InventoryFit.missingSlots(storage, Map.of(), incoming));
    }
}
