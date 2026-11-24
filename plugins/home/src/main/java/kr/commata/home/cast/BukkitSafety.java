package kr.commata.home.cast;

import java.util.Optional;
import kr.commata.home.logic.DestinationSafety;
import kr.commata.home.logic.PlayerSafety;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

/** Bukkit 객체에서 값을 꺼내 {@link PlayerSafety}, {@link DestinationSafety} 로 넘깁니다. */
public final class BukkitSafety {

    private BukkitSafety() {
    }

    public static Optional<PlayerSafety.Unsafe> checkPlayer(Player player, double maxFallDistance) {
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            return Optional.empty(); // 피해를 받지 않으므로 검사 생략 (OP 테스트용)
        }
        var state = new PlayerSafety.State(
                player.isOnGround(),
                player.isFlying(),
                player.isGliding(),
                player.isInsideVehicle(),
                player.getFallDistance(),
                player.isInLava(),
                player.getFireTicks() > 0,
                player.isUnderWater(),
                player.hasPotionEffect(PotionEffectType.WATER_BREATHING)
                        || player.hasPotionEffect(PotionEffectType.CONDUIT_POWER));
        return PlayerSafety.check(state, maxFallDistance);
    }

    /** 청크가 로드된 상태에서 호출해야 합니다. */
    public static boolean isSafeDestination(Location location) {
        Block feet = location.getBlock();
        // 반 블록·밭·영혼 모래처럼 1칸보다 낮은 블록 위에 서 있으면 발 위치 블록이 그 블록 자체가 됨
        if (!feet.isPassable() && feet.getBoundingBox().getMaxY() <= location.getY() + 0.01) {
            feet = feet.getRelative(BlockFace.UP);
        }
        Block head = feet.getRelative(BlockFace.UP);
        Block ground = feet.getRelative(BlockFace.DOWN);

        boolean hazard = isHazard(feet) || isHazard(head) || isHazard(ground);
        var spot = new DestinationSafety.Spot(
                feet.isPassable(),
                head.isPassable(),
                ground.getType().isSolid(),
                hazard);
        return DestinationSafety.isSafe(spot);
    }

    private static boolean isHazard(Block block) {
        return DestinationSafety.isHazardBlock(block.getType().getKey().getKey());
    }
}
