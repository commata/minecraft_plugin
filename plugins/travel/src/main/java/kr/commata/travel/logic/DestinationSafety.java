package kr.commata.travel.logic;

import java.util.Locale;
import java.util.Set;

/** 집 위치(발·머리·발밑 블록)가 서 있을 수 있는 곳인지 판단합니다. */
public final class DestinationSafety {

    /**
     * @param feetPassable 발 위치 블록을 통과할 수 있음 (공기, 풀, 열린 문 등)
     * @param headPassable 머리 위치 블록을 통과할 수 있음
     * @param groundSolid  발밑 블록이 밟을 수 있는 단단한 블록
     * @param hazard       발·머리·발밑 중 용암, 불, 마그마, 선인장 등 피해를 주는 블록이 있음
     */
    public record Spot(boolean feetPassable, boolean headPassable, boolean groundSolid, boolean hazard) {
    }

    /** 서 있거나 닿으면 피해를 주는 블록 (ID, 네임스페이스 없이). */
    private static final Set<String> HAZARDS = Set.of(
            "lava", "fire", "soul_fire", "magma_block", "cactus", "campfire", "soul_campfire",
            "sweet_berry_bush", "wither_rose", "powder_snow", "pointed_dripstone");

    private DestinationSafety() {
    }

    public static boolean isHazardBlock(String blockId) {
        if (blockId == null) {
            return false;
        }
        String id = blockId.toLowerCase(Locale.ROOT);
        int colon = id.indexOf(':');
        return HAZARDS.contains(colon >= 0 ? id.substring(colon + 1) : id);
    }

    public static boolean isSafe(Spot spot) {
        return spot.feetPassable() && spot.headPassable() && spot.groundSolid() && !spot.hazard();
    }
}
