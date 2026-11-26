package kr.commata.economyhub.guard;

import java.util.Set;

/**
 * 플레이어가 설치했을 때 청크 PDC 에 설치 표시(mark)를 남겨야 하는 블록인지 판정하는 순수 로직 (ECONOMY.md 14.2, 14.4).
 * Bukkit 의존성 없이 문자열과 boolean 플래그만으로 판정하여 단위 테스트로 검증합니다.
 */
public final class PlacedBlockFilter {

    /**
     * 이름으로 직접 일치하는 매입 대상 블록 집합 (호박, 수박, 고대 잔해, 사탕수수, 선인장, 버섯류 5종).
     */
    public static final Set<String> EXACT_BLOCKS = Set.of(
            "ANCIENT_DEBRIS",
            "PUMPKIN",
            "MELON",
            "SUGAR_CANE",
            "CACTUS",
            "BROWN_MUSHROOM",
            "RED_MUSHROOM",
            "BROWN_MUSHROOM_BLOCK",
            "RED_MUSHROOM_BLOCK",
            "MUSHROOM_STEM"
    );

    private PlacedBlockFilter() {
    }

    /**
     * 블록 이름과 원목 태그 여부를 바탕으로 설치 시 표시 대상인지 판정합니다.
     *
     * @param materialName 블록 Material 이름
     * @param isLog        원목(Tag.LOGS) 여부
     * @return 설치 표시(mark) 대상이면 true
     */
    public static boolean isMarkedOnPlace(String materialName, boolean isLog) {
        if (isLog) {
            return true;
        }
        if (materialName == null) {
            return false;
        }
        if (materialName.endsWith("_ORE")) {
            return true;
        }
        return EXACT_BLOCKS.contains(materialName);
    }
}
