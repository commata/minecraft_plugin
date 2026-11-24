package kr.commata.travel.storage;

import java.util.Optional;
import kr.commata.travel.logic.HomeLocation;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * 집 위치, 쿨타임, 전투 제한, /tpa 설정을 플레이어 PDC 에 저장합니다.
 * PDC 는 플레이어 데이터 파일에 같이 저장되므로 재접속·서버 재시작 후에도 유지됩니다.
 * 시각은 모두 epoch 밀리초 (서버 재시작 중에도 시간이 흐름).
 *
 * <p><b>키 이름을 바꾸지 마세요.</b> 예전 Home 플러그인이 쓰던 {@code home:*} 키를 그대로 써서
 * Home → Travel 로 바뀌어도 집 위치·쿨타임·전투 제한이 그대로 이어집니다.
 * /tpa 에서 새로 생긴 값만 {@code travel:*} 키를 씁니다.
 */
public final class TravelStore {

    // Home 시절 키 (NamespacedKey(plugin, ...) 의 네임스페이스는 플러그인 이름 소문자 = "home")
    private static final NamespacedKey HOME = key("home:home");
    private static final NamespacedKey HOME_READY_AT = key("home:ready_at");
    private static final NamespacedKey COMBAT_UNTIL = key("home:combat_until");

    private static final NamespacedKey TPA_READY_AT = key("travel:tpa_ready_at");
    private static final NamespacedKey TPA_OFF = key("travel:tpa_off");

    private static NamespacedKey key(String raw) {
        NamespacedKey k = NamespacedKey.fromString(raw);
        if (k == null) {
            throw new IllegalStateException("잘못된 키: " + raw);
        }
        return k;
    }

    public Optional<HomeLocation> home(Player player) {
        return HomeLocation.parse(pdc(player).get(HOME, PersistentDataType.STRING));
    }

    public void setHome(Player player, HomeLocation home) {
        pdc(player).set(HOME, PersistentDataType.STRING, home.serialize());
    }

    /** 이 시각 이후에 다시 /home 가능. 없으면 0. */
    public long homeReadyAt(Player player) {
        return pdc(player).getOrDefault(HOME_READY_AT, PersistentDataType.LONG, 0L);
    }

    public void setHomeReadyAt(Player player, long epochMillis) {
        pdc(player).set(HOME_READY_AT, PersistentDataType.LONG, epochMillis);
    }

    /** 이 시각 이후에 다시 /tpa 로 이동 가능. /home 쿨타임과 따로 셉니다. 없으면 0. */
    public long tpaReadyAt(Player player) {
        return pdc(player).getOrDefault(TPA_READY_AT, PersistentDataType.LONG, 0L);
    }

    public void setTpaReadyAt(Player player, long epochMillis) {
        pdc(player).set(TPA_READY_AT, PersistentDataType.LONG, epochMillis);
    }

    /** 이 시각까지 전투 중이라 /home, /tpa 모두 불가. 없으면 0. */
    public long combatUntil(Player player) {
        return pdc(player).getOrDefault(COMBAT_UNTIL, PersistentDataType.LONG, 0L);
    }

    public void setCombatUntil(Player player, long epochMillis) {
        pdc(player).set(COMBAT_UNTIL, PersistentDataType.LONG, epochMillis);
    }

    /** /tpatoggle 로 요청 받기를 껐는지. */
    public boolean tpaOff(Player player) {
        return pdc(player).getOrDefault(TPA_OFF, PersistentDataType.BOOLEAN, false);
    }

    public void setTpaOff(Player player, boolean off) {
        if (off) {
            pdc(player).set(TPA_OFF, PersistentDataType.BOOLEAN, true);
        } else {
            pdc(player).remove(TPA_OFF);
        }
    }

    private static PersistentDataContainer pdc(Player player) {
        return player.getPersistentDataContainer();
    }
}
