package kr.commata.economyhub.skills;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * 광부 동굴 시야 (Lv.10). 2초마다 확인해 조건이면 야간 투시 15초(입자·아이콘 없음)를 다시 걸고, 조건이 끝나거나 스킬이
 * 꺼지거나 탈퇴하면 스킬이 건 효과만 지웁니다. 물약으로 마신 더 긴 야간 투시는 덮어쓰거나 지우지 않습니다.
 */
public final class CaveVision implements Listener {

    public static final long PERIOD_TICKS = 40L;

    private final Skills skills;
    /** 지금 스킬이 야간 투시를 걸어 둔 플레이어 */
    private final Set<UUID> applied = new HashSet<>();

    public CaveVision(Skills skills) {
        this.skills = skills;
    }

    public void tickAll() {
        for (Player player : skills.plugin().getServer().getOnlinePlayers()) {
            update(player);
        }
    }

    public void update(Player player) {
        boolean ours = applied.contains(player.getUniqueId());
        PotionEffect current = player.getPotionEffect(PotionEffectType.NIGHT_VISION);
        int duration = current == null ? 0 : (current.isInfinite() ? -1 : current.getDuration());
        if (ours && !CaveVisionRule.mayOverwrite(duration)) {
            // 그 사이 물약을 마심: 이제 그 효과는 플레이어 것
            applied.remove(player.getUniqueId());
            ours = false;
        }
        boolean want = player.getWorld().getEnvironment() == World.Environment.NORMAL
                && skills.active(player, Skill.MINER_CAVE_VISION) && dark(player);
        if (want) {
            if (CaveVisionRule.mayOverwrite(duration)) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, CaveVisionRule.EFFECT_TICKS, 0,
                        true, false, false));
                applied.add(player.getUniqueId());
            }
        } else if (ours) {
            player.removePotionEffect(PotionEffectType.NIGHT_VISION);
            applied.remove(player.getUniqueId());
        }
    }

    private boolean dark(Player player) {
        Block eye = player.getEyeLocation().getBlock();
        return CaveVisionRule.dark(eye.getY(), skills.settings().caveMaxY(), eye.getLightFromSky());
    }

    /** 플러그인 종료: 스킬이 건 효과만 지움 */
    public void shutdown() {
        for (UUID uuid : applied) {
            Player player = skills.plugin().getServer().getPlayer(uuid);
            if (player != null) {
                PotionEffect current = player.getPotionEffect(PotionEffectType.NIGHT_VISION);
                if (current != null && !current.isInfinite() && current.getDuration() <= CaveVisionRule.EFFECT_TICKS) {
                    player.removePotionEffect(PotionEffectType.NIGHT_VISION);
                }
            }
        }
        applied.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (applied.remove(player.getUniqueId())) {
            // 접속 종료 때 남은 15초가 저장되지 않게 지움
            player.removePotionEffect(PotionEffectType.NIGHT_VISION);
        }
        skills.forget(player.getUniqueId());
    }
}
