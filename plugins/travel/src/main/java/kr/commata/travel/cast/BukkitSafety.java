package kr.commata.travel.cast;

import java.util.Optional;
import kr.commata.travel.logic.DestinationSafety;
import kr.commata.travel.logic.PlayerSafety;
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

    /** 이동하는 사람 검사. 크리에이티브·관전은 피해를 받지 않으므로 생략 (OP 테스트용). */
    public static Optional<PlayerSafety.Unsafe> checkPlayer(Player player, double maxFallDistance) {
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            return Optional.empty();
        }
        return PlayerSafety.check(stateOf(player), maxFallDistance);
    }

    /**
     * /tpa 목적지가 되는 사람(수락자) 검사. 이동하는 사람은 크리에이티브가 아닐 수 있으므로
     * 게임 모드와 상관없이 검사합니다.
     */
    public static Optional<PlayerSafety.Unsafe> checkDestinationPlayer(Player player, double maxFallDistance) {
        return PlayerSafety.checkDestination(stateOf(player), maxFallDistance);
    }

    /** 관전 모드 수락자에게는 블록 속일 수 있어서 이동하지 않습니다. */
    public static boolean isSpectator(Player player) {
        return player.getGameMode() == GameMode.SPECTATOR;
    }

    private static PlayerSafety.State stateOf(Player player) {
        return new PlayerSafety.State(
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
