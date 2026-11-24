package kr.commata.pets.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 플레이어 주변에서 펫을 내려놓을 칸을 고르는 순수 로직 (Bukkit 없이 테스트 가능). */
public final class SpotSearch {

    /** 플레이어 블록 기준 상대 좌표. */
    public record Offset(int dx, int dy, int dz) {
    }

    /** 한 칸의 상태. feet = 발 위치, head = 그 위, ground = 발밑. */
    public record Spot(boolean feetPassable, boolean headPassable, boolean groundSolid, boolean hazard) {
    }

    /** 닿으면 피해를 주는 블록 ID (네임스페이스 없이). */
    private static final Set<String> HAZARDS = Set.of(
            "lava", "fire", "soul_fire", "magma_block", "cactus", "campfire", "soul_campfire",
            "sweet_berry_bush", "wither_rose", "powder_snow", "pointed_dripstone");

    private SpotSearch() {
    }

    /**
     * 가까운 순서의 후보 좌표. 플레이어 칸(0,0,0)은 빼고, 같은 높이를 위·아래보다 먼저 봅니다.
     *
     * @param radius 가로 반경 (블록)
     * @param height 위아래 탐색 높이 (블록)
     */
    public static List<Offset> candidates(int radius, int height) {
        List<Offset> out = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -height; dy <= height; dy++) {
                    if (dx == 0 && dz == 0 && dy == 0) {
                        continue;
                    }
                    out.add(new Offset(dx, dy, dz));
                }
            }
        }
        out.sort(Comparator
                .comparingInt((Offset o) -> o.dx() * o.dx() + o.dz() * o.dz())
                .thenComparingInt(o -> Math.abs(o.dy()))
                .thenComparingInt(o -> -o.dy()) // 같은 거리면 위쪽 우선 (아래로 떨어뜨리는 것보다 안전)
                .thenComparingInt(Offset::dx)
                .thenComparingInt(Offset::dz));
        return out;
    }

    public static boolean isSafe(Spot spot) {
        return spot.feetPassable() && spot.headPassable() && spot.groundSolid() && !spot.hazard();
    }

    public static boolean isHazardBlock(String blockId) {
        if (blockId == null) {
            return false;
        }
        String id = blockId.toLowerCase(Locale.ROOT);
        int colon = id.indexOf(':');
        return HAZARDS.contains(colon >= 0 ? id.substring(colon + 1) : id);
    }
}
