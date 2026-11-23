package kr.commata.home.storage;

import java.util.Optional;
import kr.commata.home.logic.HomeLocation;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * 집 위치, 귀환 쿨타임, 전투 제한을 플레이어 PDC 에 저장합니다.
 * PDC 는 플레이어 데이터 파일에 같이 저장되므로 재접속·서버 재시작 후에도 유지됩니다.
 * 시각은 모두 epoch 밀리초 (서버 재시작 중에도 시간이 흐름).
 */
public final class HomeStore {

    private final NamespacedKey homeKey;
    private final NamespacedKey readyAtKey;
    private final NamespacedKey combatUntilKey;

    public HomeStore(Plugin plugin) {
        this.homeKey = new NamespacedKey(plugin, "home");
        this.readyAtKey = new NamespacedKey(plugin, "ready_at");
        this.combatUntilKey = new NamespacedKey(plugin, "combat_until");
    }

    public Optional<HomeLocation> home(Player player) {
        return HomeLocation.parse(pdc(player).get(homeKey, PersistentDataType.STRING));
    }

    public void setHome(Player player, HomeLocation home) {
        pdc(player).set(homeKey, PersistentDataType.STRING, home.serialize());
    }

    /** 이 시각 이후에 다시 귀환 가능. 없으면 0. */
    public long readyAt(Player player) {
        return pdc(player).getOrDefault(readyAtKey, PersistentDataType.LONG, 0L);
    }

    public void setReadyAt(Player player, long epochMillis) {
        pdc(player).set(readyAtKey, PersistentDataType.LONG, epochMillis);
    }

    /** 이 시각까지 전투 중이라 귀환 불가. 없으면 0. */
    public long combatUntil(Player player) {
        return pdc(player).getOrDefault(combatUntilKey, PersistentDataType.LONG, 0L);
    }

    public void setCombatUntil(Player player, long epochMillis) {
        pdc(player).set(combatUntilKey, PersistentDataType.LONG, epochMillis);
    }

    private static PersistentDataContainer pdc(Player player) {
        return player.getPersistentDataContainer();
    }
}
