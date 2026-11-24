package kr.commata.bank;

import java.time.DateTimeException;
import java.time.ZoneId;
import org.bukkit.configuration.file.FileConfiguration;

/** config.yml 값. */
public record BankSettings(long minPay, int baltopCacheSeconds, ZoneId zone) {

    public static final BankSettings DEFAULTS = new BankSettings(10, 60, ZoneId.of("Asia/Seoul"));

    public static BankSettings from(FileConfiguration c) {
        ZoneId zone;
        try {
            zone = ZoneId.of(c.getString("time-zone", "Asia/Seoul"));
        } catch (DateTimeException e) {
            zone = DEFAULTS.zone();
        }
        return new BankSettings(
                Math.max(1, c.getLong("pay.min-amount", DEFAULTS.minPay())),
                Math.max(0, c.getInt("baltop.cache-seconds", DEFAULTS.baltopCacheSeconds())),
                zone);
    }
}
