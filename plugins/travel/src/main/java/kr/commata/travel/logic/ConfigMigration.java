package kr.commata.travel.logic;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 예전 Home 플러그인 설정(plugins/Home/config.yml)의 값을 Travel 설정 경로로 옮깁니다.
 * 처음 Travel 이 켜질 때 한 번만 사용합니다.
 */
public final class ConfigMigration {

    /** 예전 경로 → 새 경로. */
    public static final Map<String, String> HOME_TO_TRAVEL = Map.of(
            "cast-seconds", "home.cast-seconds",
            "cooldown-seconds", "home.cooldown-seconds",
            "combat.mob-seconds", "combat.mob-seconds",
            "combat.pvp-seconds", "combat.pvp-seconds",
            "safety.max-fall-distance", "safety.max-fall-distance");

    private ConfigMigration() {
    }

    /**
     * @param oldValues 예전 설정의 경로 → 값 (Bukkit 의 {@code getValues(true)})
     * @return 새 설정에 넣을 경로 → 값. 숫자가 아닌 값이나 섹션은 옮기지 않습니다.
     */
    public static Map<String, Object> fromHome(Map<String, Object> oldValues) {
        Map<String, Object> out = new LinkedHashMap<>();
        HOME_TO_TRAVEL.forEach((oldPath, newPath) -> {
            Object v = oldValues.get(oldPath);
            if (v instanceof Number) {
                out.put(newPath, v);
            }
        });
        return out;
    }
}
