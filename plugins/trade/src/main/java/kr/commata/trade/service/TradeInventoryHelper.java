package kr.commata.trade.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.commata.trade.core.InventoryFit;
import kr.commata.trade.core.OfferValidation;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Bukkit {@link PlayerInventory}·{@link ItemStack} 과 순수 로직({@link InventoryFit}·{@link OfferValidation}) 사이의 어댑터.
 *
 * <p>아이템 직렬화는 Paper 의 {@link ItemStack#serializeAsBytes()} / {@link ItemStack#deserializeBytes(byte[])} 를 써서
 * 셜커 상자·꾸러미·인첸트·내구도·이름·PDC 를 손실 없이 보존합니다 (TRADE.md 3·5장).
 */
public final class TradeInventoryHelper {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private TradeInventoryHelper() {
    }

    public static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType().isAir() || stack.getAmount() <= 0;
    }

    /** 보관함(0..35) 깊은 스냅샷 (같은 틱 지급 회수용). */
    public static ItemStack[] snapshotStorage(PlayerInventory inv) {
        ItemStack[] src = inv.getStorageContents();
        ItemStack[] copy = new ItemStack[src.length];
        for (int i = 0; i < src.length; i++) {
            copy[i] = isEmpty(src[i]) ? null : src[i].clone();
        }
        return copy;
    }

    /**
     * 제시 목록의 각 칸을 현재 보관함과 깊은 스냅샷으로 비교해 {@link OfferValidation.SlotState} 맵을 만듭니다.
     * {@link ItemStack#isSimilar(ItemStack)} 는 내구도·꾸러미 내용·셜커 내용·인첸트 등 데이터 컴포넌트 전체를 비교합니다.
     */
    public static Map<Integer, OfferValidation.SlotState> inspectOfferSlots(
            PlayerInventory inv,
            Map<Integer, Integer> offers,
            Map<Integer, ItemStack> snapshots
    ) {
        ItemStack[] storage = inv.getStorageContents();
        Map<Integer, OfferValidation.SlotState> result = new HashMap<>(offers.size());
        for (int invSlot : offers.keySet()) {
            if (invSlot < 0 || invSlot >= storage.length) {
                result.put(invSlot, new OfferValidation.SlotState(invSlot, false, false, 0));
                continue;
            }
            ItemStack current = storage[invSlot];
            ItemStack snap = snapshots.get(invSlot);
            if (isEmpty(current) || isEmpty(snap)) {
                result.put(invSlot, new OfferValidation.SlotState(invSlot, false, false, 0));
            } else {
                result.put(invSlot, new OfferValidation.SlotState(
                        invSlot, true, current.isSimilar(snap), current.getAmount()));
            }
        }
        return result;
    }

    /**
     * 정산 2단계: 스냅샷이 아니라 <b>그 순간 그 칸의 실제 스택</b>에서 제시 개수만큼 복제해 바이트로 직렬화합니다
     * (TRADE.md 3·5장 2번).
     */
    public static List<byte[]> serializeOfferedStacks(PlayerInventory inv, Map<Integer, Integer> offers) {
        ItemStack[] storage = inv.getStorageContents();
        List<byte[]> serialized = new ArrayList<>(offers.size());
        for (Map.Entry<Integer, Integer> entry : offers.entrySet()) {
            ItemStack live = storage[entry.getKey()];
            ItemStack portion = live.clone();
            portion.setAmount(entry.getValue());
            serialized.add(portion.serializeAsBytes());
        }
        return serialized;
    }

    /** 제시 목록의 사람 읽기용 요약(로그·완료 안내용). */
    public static List<String> summarizeOfferedStacks(PlayerInventory inv, Map<Integer, Integer> offers) {
        ItemStack[] storage = inv.getStorageContents();
        List<String> summaries = new ArrayList<>(offers.size());
        for (Map.Entry<Integer, Integer> entry : offers.entrySet()) {
            ItemStack live = storage[entry.getKey()];
            if (!isEmpty(live)) {
                summaries.add(summarize(live, entry.getValue()));
            }
        }
        return summaries;
    }

    public static String summarize(ItemStack stack, int amount) {
        String base = stack.getType().name() + " x" + amount;
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            String name = PLAIN.serialize(meta.displayName()).replace('\r', ' ').replace('\n', ' ').trim();
            if (!name.isEmpty()) {
                return base + " (" + name + ")";
            }
        }
        return base;
    }

    /** 저널 바이트 목록을 실제 {@link ItemStack} 목록으로 역직렬화합니다. */
    public static List<ItemStack> deserializeStacks(List<byte[]> serialized) {
        List<ItemStack> out = new ArrayList<>(serialized.size());
        for (byte[] bytes : serialized) {
            ItemStack item = ItemStack.deserializeBytes(bytes);
            if (isEmpty(item)) {
                throw new IllegalStateException("역직렬화된 아이템이 비어 있습니다");
            }
            out.add(item);
        }
        return out;
    }

    /**
     * 정산 3단계: 각 제시 칸이 스냅샷과 일치하고 제시 개수 이상인지 다시 확인한 뒤 정확히 차감합니다.
     * 한 칸이라도 부족하면 인벤토리를 건드리지 않고 {@code false}.
     */
    public static boolean removeOfferedItems(PlayerInventory inv,
                                             Map<Integer, Integer> offers,
                                             Map<Integer, ItemStack> snapshots) {
        if (offers.isEmpty()) {
            return true;
        }
        ItemStack[] storage = snapshotStorage(inv);
        for (Map.Entry<Integer, Integer> entry : offers.entrySet()) {
            int slot = entry.getKey();
            int takeAmount = entry.getValue();
            if (slot < 0 || slot >= storage.length || takeAmount <= 0) {
                return false;
            }
            ItemStack current = storage[slot];
            ItemStack snap = snapshots.get(slot);
            if (isEmpty(current) || isEmpty(snap) || !current.isSimilar(snap) || current.getAmount() < takeAmount) {
                return false;
            }
        }
        for (Map.Entry<Integer, Integer> entry : offers.entrySet()) {
            int slot = entry.getKey();
            int takeAmount = entry.getValue();
            ItemStack current = storage[slot];
            int remain = current.getAmount() - takeAmount;
            if (remain <= 0) {
                storage[slot] = null;
            } else {
                ItemStack next = current.clone();
                next.setAmount(remain);
                storage[slot] = next;
            }
        }
        inv.setStorageContents(storage);
        return true;
    }

    /**
     * {@link InventoryFit} 을 사용해 받을 물건 전부가 들어가는지(부족한 빈칸 수) 계산합니다.
     * {@code isSimilar} 동치류를 만들어 순수 시뮬레이션에 넘깁니다.
     */
    public static int missingSlots(PlayerInventory inv,
                                   Map<Integer, Integer> ownOfferedBySlot,
                                   List<ItemStack> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return 0;
        }
        List<ItemStack> representatives = new ArrayList<>();
        ItemStack[] storage = inv.getStorageContents();
        List<InventoryFit.StackSlot> pureStorage = new ArrayList<>(storage.length);
        for (ItemStack stack : storage) {
            if (isEmpty(stack)) {
                pureStorage.add(null);
            } else {
                String key = keyOf(stack, representatives);
                pureStorage.add(new InventoryFit.StackSlot(key, stack.getAmount(), stack.getMaxStackSize()));
            }
        }
        List<InventoryFit.IncomingStack> pureIncoming = new ArrayList<>(incoming.size());
        for (ItemStack stack : incoming) {
            if (!isEmpty(stack)) {
                String key = keyOf(stack, representatives);
                pureIncoming.add(new InventoryFit.IncomingStack(key, stack.getAmount(), stack.getMaxStackSize()));
            }
        }
        return InventoryFit.missingSlots(pureStorage, ownOfferedBySlot, pureIncoming);
    }

    /**
     * 받을 물건 전부를 보관함(기존 동일 스택 합치기 + 빈칸)에 배치합니다.
     * 전부 들어가지 않으면 인벤토리를 전혀 건드리지 않고 {@code false}.
     */
    public static boolean placeAllItems(PlayerInventory inv, List<ItemStack> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return true;
        }
        ItemStack[] storage = snapshotStorage(inv);
        for (ItemStack raw : incoming) {
            if (isEmpty(raw)) {
                continue;
            }
            int remaining = raw.getAmount();
            int maxStack = raw.getMaxStackSize();

            // 1) 기존 동일 스택에 합치기
            for (int i = 0; i < storage.length && remaining > 0; i++) {
                ItemStack existing = storage[i];
                if (!isEmpty(existing) && existing.isSimilar(raw) && existing.getAmount() < existing.getMaxStackSize()) {
                    int cap = Math.min(existing.getMaxStackSize(), maxStack);
                    int space = cap - existing.getAmount();
                    if (space > 0) {
                        int add = Math.min(remaining, space);
                        existing.setAmount(existing.getAmount() + add);
                        remaining -= add;
                    }
                }
            }

            // 2) 남은 수량은 빈칸에 배치
            for (int i = 0; i < storage.length && remaining > 0; i++) {
                if (isEmpty(storage[i])) {
                    int add = Math.min(remaining, maxStack);
                    ItemStack placed = raw.clone();
                    placed.setAmount(add);
                    storage[i] = placed;
                    remaining -= add;
                }
            }

            if (remaining > 0) {
                return false;
            }
        }
        inv.setStorageContents(storage);
        return true;
    }

    private static String keyOf(ItemStack stack, List<ItemStack> representatives) {
        for (int i = 0; i < representatives.size(); i++) {
            if (representatives.get(i).isSimilar(stack)) {
                return "k" + i;
            }
        }
        int id = representatives.size();
        representatives.add(stack);
        return "k" + id;
    }
}
