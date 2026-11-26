package kr.commata.market.core;

import java.util.Set;

/**
 * 농부 매입 작물 및 수확 판정 순수 로직 (ECONOMY.md 14.2, 14.4 v0.9.3).
 * Bukkit 에 의존하지 않아 단위 테스트로 검증합니다.
 */
public final class FarmerCropRules {

    /** 달콤한 열매 덤불 최소 성숙 나이 (나이 2, 3 수확 가능). */
    public static final int BERRY_BUSH_MATURE_AGE = 2;

    /** 추가된 농부 매입 블록 (사탕수수, 선인장, 버섯류). 성숙 검사 없이 파괴 시 집계. */
    private static final Set<String> DIRECT_FARMER_BLOCKS = Set.of(
            "SUGAR_CANE",
            "CACTUS",
            "BROWN_MUSHROOM",
            "RED_MUSHROOM",
            "BROWN_MUSHROOM_BLOCK",
            "RED_MUSHROOM_BLOCK",
            "MUSHROOM_STEM"
    );

    /** 버섯류 블록 집합. */
    private static final Set<String> MUSHROOM_BLOCKS = Set.of(
            "BROWN_MUSHROOM",
            "RED_MUSHROOM",
            "BROWN_MUSHROOM_BLOCK",
            "RED_MUSHROOM_BLOCK",
            "MUSHROOM_STEM"
    );

    private FarmerCropRules() {
    }

    /**
     * 달콤한 열매 덤불이 수확 가능한 성숙 상태(나이 2 이상)인지 확인합니다.
     */
    public static boolean isMatureBerryBush(int age) {
        return age >= BERRY_BUSH_MATURE_AGE;
    }

    /**
     * 해당 블록 재질이 달콤한 열매 덤불인지 확인합니다.
     */
    public static boolean isBerryBush(String materialName) {
        return "SWEET_BERRY_BUSH".equals(materialName);
    }

    /**
     * 설치 표시(PDC) 확인을 면제하는 블록인지 확인합니다.
     * 달콤한 열매 덤불은 심어서 자란 뒤 수확하는 특성상 설치 여부를 확인하지 않습니다 (기획 14.4).
     */
    public static boolean isPlacementCheckIgnored(String materialName) {
        return isBerryBush(materialName);
    }

    /**
     * 성숙 나이 검사 없이 블록 파괴 시 농부로 집계 가능한 추가 블록인지 확인합니다.
     */
    public static boolean isDirectFarmerBlock(String materialName) {
        return materialName != null && DIRECT_FARMER_BLOCKS.contains(materialName);
    }

    /**
     * 버섯류 블록인지 확인합니다 (거대 버섯 블록 및 줄기 포함).
     */
    public static boolean isMushroomBlock(String materialName) {
        return materialName != null && MUSHROOM_BLOCKS.contains(materialName);
    }

    /**
     * 블록 파괴 드롭에서 농부 매입 품목으로 유효한 드롭인지 판정합니다.
     * 거대 버섯 블록을 실크터치로 캐서 블록 아이템이 나온 경우 자동으로 제외됩니다.
     */
    public static boolean isEligibleDrop(String blockType, String dropItemType) {
        if (blockType == null || dropItemType == null) {
            return false;
        }
        if (isMushroomBlock(blockType)) {
            // 버섯 블록 파괴 시 갈색 버섯 또는 빨간 버섯 아이템만 인정 (실크터치 블록 드롭 제외)
            return "BROWN_MUSHROOM".equals(dropItemType) || "RED_MUSHROOM".equals(dropItemType);
        }
        if ("SUGAR_CANE".equals(blockType)) {
            return "SUGAR_CANE".equals(dropItemType);
        }
        if ("CACTUS".equals(blockType)) {
            return "CACTUS".equals(dropItemType);
        }
        if ("SWEET_BERRY_BUSH".equals(blockType)) {
            return "SWEET_BERRIES".equals(dropItemType);
        }
        return true;
    }
}
