package kr.commata.trade.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 받을 물건 전부가 플레이어 보관함(0..35)에 들어가는지 시뮬레이션합니다 (TRADE.md 5·8·14장).
 *
 * <p>자기 제시분(정산 때 빠져나갈 개수)을 제외한 상태에서, 기존 동일 스택 합치기와 빈칸 배치를 모두 반영해
 * 부족한 빈칸 수를 정확히 계산합니다. 부분 지급은 하지 않으므로 부족 칸 수가 0일 때만 지급할 수 있습니다.
 */
public final class InventoryFit {

    public static final int STORAGE_SLOTS = 36;

    /**
     * 보관함 한 칸의 순수 값 표현.
     *
     * @param typeKey  {@code isSimilar} 동치류 식별자 (빈 칸은 {@code null})
     * @param amount   현재 수량 (1 이상)
     * @param maxStack 해당 아이템 최대 스택 크기 (1..99)
     */
    public record StackSlot(String typeKey, int amount, int maxStack) {
        public StackSlot {
            if (typeKey != null && (amount <= 0 || maxStack <= 0)) {
                throw new IllegalArgumentException("잘못된 스택 값: amount=" + amount + ", maxStack=" + maxStack);
            }
        }
    }

    /**
     * 받을 아이템 한 덩어리.
     *
     * @param typeKey  {@code isSimilar} 동치류 식별자
     * @param amount   받을 수량 (1 이상)
     * @param maxStack 해당 아이템 최대 스택 크기 (1 이상)
     */
    public record IncomingStack(String typeKey, int amount, int maxStack) {
        public IncomingStack {
            if (typeKey == null || amount <= 0 || maxStack <= 0) {
                throw new IllegalArgumentException("잘못된 수신 스택: " + typeKey + " x" + amount + " / " + maxStack);
            }
        }
    }

    private static final class MutableSlot {
        String typeKey;
        int amount;
        int maxStack;

        MutableSlot(StackSlot src) {
            if (src != null && src.typeKey() != null && src.amount() > 0) {
                this.typeKey = src.typeKey();
                this.amount = src.amount();
                this.maxStack = src.maxStack();
            }
        }

        boolean isEmpty() {
            return typeKey == null || amount <= 0;
        }
    }

    private InventoryFit() {
    }

    /**
     * 현재 보관함에 자기 제시분을 뺀 뒤 {@code incoming} 전부가 들어가는지 계산합니다.
     *
     * @param storage           현재 보관함 슬롯 목록 (길이 36, 빈 칸은 {@code null})
     * @param ownOfferedBySlot  자기 제시분 {@code invSlot -> offeredAmount} (지급/claim 시점엔 빈 맵)
     * @param incoming          받을 아이템 목록
     * @return 0 이면 전부 들어감. 1 이상이면 추가로 필요한 빈칸 수.
     */
    public static int missingSlots(List<StackSlot> storage,
                                   Map<Integer, Integer> ownOfferedBySlot,
                                   List<IncomingStack> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return 0;
        }
        int size = storage == null ? STORAGE_SLOTS : storage.size();
        List<MutableSlot> slots = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            StackSlot src = storage == null ? null : storage.get(i);
            MutableSlot slot = new MutableSlot(src);
            if (ownOfferedBySlot != null) {
                Integer offered = ownOfferedBySlot.get(i);
                if (offered != null && offered > 0 && !slot.isEmpty()) {
                    slot.amount = Math.max(0, slot.amount - offered);
                    if (slot.amount == 0) {
                        slot.typeKey = null;
                        slot.maxStack = 0;
                    }
                }
            }
            slots.add(slot);
        }

        int missing = 0;
        for (IncomingStack inc : incoming) {
            int remaining = inc.amount();
            int maxStack = inc.maxStack();

            // 1) 같은 아이템의 기존 스택에 먼저 합침
            for (MutableSlot slot : slots) {
                if (remaining <= 0) {
                    break;
                }
                if (!slot.isEmpty() && inc.typeKey().equals(slot.typeKey) && slot.amount < slot.maxStack) {
                    int space = Math.min(slot.maxStack, maxStack) - slot.amount;
                    if (space > 0) {
                        int add = Math.min(remaining, space);
                        slot.amount += add;
                        remaining -= add;
                    }
                }
            }

            // 2) 남은 수량은 빈칸에 배치
            for (MutableSlot slot : slots) {
                if (remaining <= 0) {
                    break;
                }
                if (slot.isEmpty()) {
                    int add = Math.min(remaining, maxStack);
                    slot.typeKey = inc.typeKey();
                    slot.amount = add;
                    slot.maxStack = maxStack;
                    remaining -= add;
                }
            }

            // 3) 그래도 남으면 필요한 추가 빈칸 수 합산
            if (remaining > 0) {
                missing += (remaining + maxStack - 1) / maxStack;
            }
        }
        return missing;
    }

    /** 전부 들어가면 {@code true}. */
    public static boolean fitsAll(List<StackSlot> storage,
                                  Map<Integer, Integer> ownOfferedBySlot,
                                  List<IncomingStack> incoming) {
        return missingSlots(storage, ownOfferedBySlot, incoming) == 0;
    }
}
