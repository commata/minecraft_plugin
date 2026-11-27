package kr.commata.trade;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Trade 플러그인 설정 ({@code config.yml}, TRADE.md 2·7·10장).
 * 정수가 아니거나 범위를 벗어난 값, 알 수 없는 키는 경고 로그를 남기고 기본값을 씁니다 (AGENTS.md 8장).
 */
public record TradeConfig(
        int requestTimeoutSeconds,
        int countdownSeconds,
        int reRequestCooldownSeconds,
        int dialogTimeoutSeconds,
        ZoneId zone
) {

    public static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 30;
    public static final int DEFAULT_COUNTDOWN_SECONDS = 3;
    public static final int DEFAULT_RE_REQUEST_COOLDOWN_SECONDS = 10;
    public static final int DEFAULT_DIALOG_TIMEOUT_SECONDS = 60;
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Seoul");

    public static final TradeConfig DEFAULTS = new TradeConfig(
            DEFAULT_REQUEST_TIMEOUT_SECONDS,
            DEFAULT_COUNTDOWN_SECONDS,
            DEFAULT_RE_REQUEST_COOLDOWN_SECONDS,
            DEFAULT_DIALOG_TIMEOUT_SECONDS,
            DEFAULT_ZONE);

    private static final Set<String> KNOWN_KEYS = Set.of(
            "request-timeout-seconds",
            "countdown-seconds",
            "re-request-cooldown-seconds",
            "dialog-timeout-seconds",
            "time-zone"
    );

    public static TradeConfig load(FileConfiguration cfg, Logger logger) {
        for (String key : cfg.getKeys(false)) {
            if (!KNOWN_KEYS.contains(key)) {
                logger.warning("config.yml 에 알 수 없는 설정 키가 있어 무시합니다: " + key);
            }
        }
        int requestTimeout = readPositiveInt(cfg, "request-timeout-seconds", DEFAULT_REQUEST_TIMEOUT_SECONDS, 5, 300, logger);
        int countdown = readPositiveInt(cfg, "countdown-seconds", DEFAULT_COUNTDOWN_SECONDS, 1, 30, logger);
        int reRequestCooldown = readPositiveInt(cfg, "re-request-cooldown-seconds", DEFAULT_RE_REQUEST_COOLDOWN_SECONDS, 1, 300, logger);
        int dialogTimeout = readPositiveInt(cfg, "dialog-timeout-seconds", DEFAULT_DIALOG_TIMEOUT_SECONDS, 10, 300, logger);
        ZoneId zone = readZone(cfg, "time-zone", DEFAULT_ZONE, logger);
        return new TradeConfig(requestTimeout, countdown, reRequestCooldown, dialogTimeout, zone);
    }

    public long requestTimeoutMs() {
        return requestTimeoutSeconds * 1000L;
    }

    public long reRequestCooldownMs() {
        return reRequestCooldownSeconds * 1000L;
    }

    public long dialogTimeoutMs() {
        return dialogTimeoutSeconds * 1000L;
    }

    private static int readPositiveInt(FileConfiguration cfg, String key, int def, int min, int max, Logger logger) {
        if (!cfg.contains(key, true)) {
            return def;
        }
        Object raw = cfg.get(key);
        if (!(raw instanceof Integer value)) {
            logger.warning("config.yml '" + key + "' 값이 정수가 아닙니다(" + raw + "). 기본값 " + def + "초를 씁니다.");
            return def;
        }
        if (value < min || value > max) {
            logger.warning("config.yml '" + key + "' 값이 범위(" + min + "~" + max + ")를 벗어났습니다("
                    + value + "). 기본값 " + def + "초를 씁니다.");
            return def;
        }
        return value;
    }

    private static ZoneId readZone(FileConfiguration cfg, String key, ZoneId def, Logger logger) {
        if (!cfg.contains(key, true)) {
            return def;
        }
        String raw = cfg.getString(key);
        if (raw == null || raw.isBlank()) {
            logger.warning("config.yml '" + key + "' 값이 비어 있습니다. 기본값 " + def + "을 씁니다.");
            return def;
        }
        try {
            return ZoneId.of(raw.trim());
        } catch (DateTimeException e) {
            logger.warning("config.yml '" + key + "' 시간대 이름이 잘못되었습니다(" + raw + "). 기본값 " + def + "을 씁니다.");
            return def;
        }
    }
}
