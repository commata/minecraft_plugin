package kr.commata.home.listener;

import java.time.Duration;
import kr.commata.home.HomePlugin;
import kr.commata.home.cast.CastManager;
import kr.commata.home.logic.CancelReason;
import kr.commata.home.logic.Timers;
import kr.commata.home.storage.HomeStore;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * 피해·공격으로 귀환을 취소하고 전투 제한을 겁니다.
 * <ul>
 *   <li>몬스터에게 맞음 / 몬스터를 때림: config 의 combat.mob-seconds 동안 귀환 불가</li>
 *   <li>PvP 공격·피격: 양쪽 모두 combat.pvp-seconds 동안 귀환 불가</li>
 *   <li>낙하·불 등 환경 피해: 귀환만 취소, 제한 없음</li>
 * </ul>
 * 서버나 다른 플러그인이 막은 피해(PvP 꺼짐 등)는 ignoreCancelled 로 무시하므로 제한도 걸리지 않습니다.
 */
public final class CombatListener implements Listener {

    private final HomePlugin plugin;
    private final CastManager casts;
    private final HomeStore store;

    public CombatListener(HomePlugin plugin, CastManager casts, HomeStore store) {
        this.plugin = plugin;
        this.casts = casts;
        this.store = store;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        Entity victim = event.getEntity();
        Entity source = event.getDamageSource().getCausingEntity();
        if (source != null && source.equals(victim)) {
            source = null; // 자기 화살 등
        }

        if (victim instanceof Player victimPlayer) {
            if (source instanceof Player attacker) {
                tag(victimPlayer, plugin.settings().pvpCombatTag());
                tag(attacker, plugin.settings().pvpCombatTag());
                casts.cancel(attacker, CancelReason.ATTACK);
            } else if (source instanceof LivingEntity) {
                tag(victimPlayer, plugin.settings().mobCombatTag());
            }
            casts.cancel(victimPlayer, CancelReason.DAMAGED);
        } else if (source instanceof Player attacker && victim instanceof LivingEntity) {
            tag(attacker, plugin.settings().mobCombatTag());
            casts.cancel(attacker, CancelReason.ATTACK);
        }
    }

    private void tag(Player player, Duration duration) {
        if (duration.isZero()) {
            return;
        }
        long now = System.currentTimeMillis();
        store.setCombatUntil(player, Timers.extend(store.combatUntil(player), now, duration));
    }
}
