package kr.commata.market.service;

import java.util.ArrayList;
import java.util.List;
import kr.commata.market.MarketConfig;
import kr.commata.market.core.MarketStore;
import kr.commata.market.core.OrderPayload;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

/**
 * 희귀 상점·뽑기 지급 아이템 생성과 인벤토리 빈칸·매입 품목 매칭/제거 도우미.
 */
public final class ItemFactory {

    private ItemFactory() {
    }

    /** 인첸트 책 1권(바닐라 인첸트 1개, ECONOMY.md 13.4, 13.5). */
    public static ItemStack enchantedBook(String enchantKey, int level) {
        Enchantment enchantment = MarketConfig.findEnchantment(enchantKey);
        if (enchantment == null) {
            throw new IllegalStateException("없는 인첸트: " + enchantKey);
        }
        ItemStack stack = new ItemStack(Material.ENCHANTED_BOOK, 1);
        if (stack.getItemMeta() instanceof EnchantmentStorageMeta meta) {
            meta.addStoredEnchant(enchantment, level, true);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** 주문 payload 에서 지급할 실제 바닐라 아이템 목록을 만듭니다. */
    public static List<ItemStack> itemsForOrder(String kind, String payload) {
        List<ItemStack> out = new ArrayList<>();
        if (MarketStore.KIND_GACHA_PULL.equals(kind)) {
            for (OrderPayload.GachaEntry entry : OrderPayload.decodeGacha(payload)) {
                out.add(enchantedBook(entry.enchantKey(), entry.level()));
            }
            return out;
        }
        if (OrderPayload.isRareResource(payload)) {
            OrderPayload.RareResource r = OrderPayload.decodeRareResource(payload);
            Material mat = Material.matchMaterial(r.material());
            if (mat == null) {
                throw new IllegalStateException("없는 아이템 재질: " + r.material());
            }
            out.add(new ItemStack(mat, r.amount()));
            return out;
        }
        if (OrderPayload.isRareBook(payload)) {
            OrderPayload.RareBook b = OrderPayload.decodeRareBook(payload);
            out.add(enchantedBook(b.enchantKey(), b.level()));
            return out;
        }
        throw new IllegalStateException("알 수 없는 주문 payload: " + kind);
    }

    /** 플레이어 보관함(0~35)의 빈 슬롯 수. */
    public static int emptySlots(Player player) {
        ItemStack[] storage = player.getInventory().getStorageContents();
        int empty = 0;
        for (ItemStack stack : storage) {
            if (stack == null || stack.getType().isAir()) {
                empty++;
            }
        }
        return empty;
    }

    /** 보관함(0~35)의 깊은 복사본 (같은 틱 회수용). */
    public static ItemStack[] snapshotStorage(PlayerInventory inv) {
        ItemStack[] src = inv.getStorageContents();
        ItemStack[] copy = new ItemStack[src.length];
        for (int i = 0; i < src.length; i++) {
            copy[i] = src[i] == null ? null : src[i].clone();
        }
        return copy;
    }

    /**
     * 보관함 빈 슬롯에 아이템 목록을 모두 넣습니다. 빈칸이 부족하면 아무것도 넣지 않고 false.
     * 기존 스택에 섞지 않고 빈칸에만 넣어 저장 확인 실패 시 완벽하게 복원할 수 있습니다.
     */
    public static boolean giveIntoEmptySlots(PlayerInventory inv, List<ItemStack> items) {
        ItemStack[] storage = inv.getStorageContents();
        List<Integer> slots = new ArrayList<>(items.size());
        for (int i = 0; i < storage.length && slots.size() < items.size(); i++) {
            if (storage[i] == null || storage[i].getType().isAir()) {
                slots.add(i);
            }
        }
        if (slots.size() < items.size()) {
            return false;
        }
        for (int i = 0; i < items.size(); i++) {
            storage[slots.get(i)] = items.get(i).clone();
        }
        inv.setStorageContents(storage);
        return true;
    }

    /**
     * 매입 대상인 순수 바닐라 아이템인지. 같은 재질의 새 아이템과 데이터 컴포넌트까지 같아야 합니다({@code isSimilar},
     * 개수는 비교 안 함). 이름·로어·인첸트·PDC뿐 아니라 광택 덮어쓰기·커스텀 모델 데이터·속성 수정자 등이 붙은
     * 커스텀 아이템도 실수로 매입하지 않습니다.
     */
    public static boolean isSellableVanilla(ItemStack stack, Material material) {
        if (stack == null || stack.getType() != material || stack.getAmount() <= 0) {
            return false;
        }
        return stack.isSimilar(new ItemStack(material));
    }

    /** 보관함(0~35)에서 매입 가능한 바닐라 아이템 총 수량. */
    public static int countSellable(Player player, Material material) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (isSellableVanilla(stack, material)) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /** 보관함(0~35)에서 매입 가능한 바닐라 아이템을 정확히 {@code amount}개 제거. 부족하면 건드리지 않고 false. */
    public static boolean removeSellable(PlayerInventory inv, Material material, int amount) {
        if (amount <= 0) {
            return false;
        }
        ItemStack[] storage = inv.getStorageContents();
        int available = 0;
        for (ItemStack stack : storage) {
            if (isSellableVanilla(stack, material)) {
                available += stack.getAmount();
            }
        }
        if (available < amount) {
            return false;
        }
        int remaining = amount;
        for (int i = 0; i < storage.length && remaining > 0; i++) {
            ItemStack stack = storage[i];
            if (!isSellableVanilla(stack, material)) {
                continue;
            }
            int take = Math.min(remaining, stack.getAmount());
            if (take >= stack.getAmount()) {
                storage[i] = null;
            } else {
                ItemStack reduced = stack.clone();
                reduced.setAmount(stack.getAmount() - take);
                storage[i] = reduced;
            }
            remaining -= take;
        }
        inv.setStorageContents(storage);
        return true;
    }
}
