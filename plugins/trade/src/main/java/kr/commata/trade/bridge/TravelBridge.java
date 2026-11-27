package kr.commata.trade.bridge;

import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Travel 플러그인의 전투 태그({@code home:combat_until}, AGENTS.md 2.4, TRADE.md 2장) 조회 어댑터.
 *
 * <p>Travel 이 없으면 전투 검사를 생략하고 {@code /trade status} 에 상태를 표시합니다.
 */
public final class TravelBridge {

    /** Travel 이 예전 Home 시절부터 유지하는 고정 PDC 키 (AGENTS.md 2.4) */
    public static final NamespacedKey COMBAT_UNTIL = NamespacedKey.fromString("home:combat_until");

    private final Server server;

    public TravelBridge(Server server) {
        this.server = server;
    }

    public boolean available() {
        Plugin travel = server.getPluginManager().getPlugin("Travel");
        return travel != null && travel.isEnabled();
    }

    public String statusText() {
        return available() ? "연결됨 (전투 태그 확인 중)" : "꺼짐 (전투 검사 생략)";
    }

    /** 플레이어가 현재 전투 중인지 확인합니다. Travel 이 꺼져 있으면 {@code false}. */
    public boolean inCombat(Player player, long nowMs) {
        return combatRemainingMs(player, nowMs) > 0L;
    }

    /** 남은 전투 제한 시간(ms). 전투 중이 아니거나 Travel 이 없으면 0. */
    public long combatRemainingMs(Player player, long nowMs) {
        if (player == null || COMBAT_UNTIL == null || !available()) {
            return 0L;
        }
        long until = player.getPersistentDataContainer().getOrDefault(COMBAT_UNTIL, PersistentDataType.LONG, 0L);
        return Math.max(0L, until - nowMs);
    }
}
