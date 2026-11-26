package kr.commata.market.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 수확물과 심는 씨앗이 같은 작물의 판매 가능량 보정 (ECONOMY.md 14.2).
 *
 * <p>감자·당근·네더 사마귀·코코아 콩은 드롭 중 1개를 다시 심어야 농사가 이어집니다(EconomyHub 농부 Lv.10 자동 재파종도
 * 드롭에서 1개를 떼어 심음). 드롭 전체를 판매 가능량으로 적으면 실제로 남는 양보다 수확마다 1개씩 많이 쌓이고,
 * 그 차이만큼 자동 농장·상점 구매 작물을 보너스가로 팔 수 있습니다. 그래서 이 4종은 다 자란 작물 한 번 수확마다
 * 해당 아이템을 1개 빼고(0 미만 없음) 적습니다. 직접 다시 심지 않아도 똑같이 빼므로, 판매 가능량이 "적게" 잡히는
 * 쪽으로만 틀립니다. 밀·비트는 씨앗이 다른 아이템이라 보정하지 않습니다.
 */
public final class SeedCrops {

    /** 작물 블록 → 다시 심을 때 쓰는 (그리고 매입 품목인) 아이템 */
    private static final Map<String, String> REPLANT_ITEM = Map.of(
            "POTATOES", "POTATO",
            "CARROTS", "CARROT",
            "NETHER_WART", "NETHER_WART",
            "COCOA", "COCOA_BEANS");

    private SeedCrops() {
    }

    /** 이 작물 블록의 씨앗 겸 수확물 아이템 이름. 해당 없으면 null. */
    public static String replantItem(String cropBlock) {
        return REPLANT_ITEM.get(cropBlock);
    }

    /**
     * 다 자란 작물 블록 한 칸의 드롭(아이템 이름 → 개수)에서 다시 심을 1개를 뺀 판매 가능량 기록용 수량.
     * 결과가 0이 된 품목은 빠집니다. 입력은 바꾸지 않습니다.
     */
    public static Map<String, Integer> harvestQuota(String cropBlock, Map<String, Integer> drops) {
        Map<String, Integer> out = new LinkedHashMap<>();
        String seed = REPLANT_ITEM.get(cropBlock);
        for (Map.Entry<String, Integer> e : drops.entrySet()) {
            int amount = e.getValue() == null ? 0 : e.getValue();
            if (e.getKey().equals(seed)) {
                amount -= 1;
            }
            if (amount > 0) {
                out.put(e.getKey(), amount);
            }
        }
        return out;
    }
}
