package kr.commata.home.logic;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * 저장된 집 위치. PDC 에 문자열 한 줄로 저장합니다: {@code 월드UUID;x;y;z;yaw;pitch}
 */
public record HomeLocation(UUID world, double x, double y, double z, float yaw, float pitch) {

    public String serialize() {
        return String.format(Locale.ROOT, "%s;%.3f;%.3f;%.3f;%.2f;%.2f", world, x, y, z, yaw, pitch);
    }

    public static Optional<HomeLocation> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String[] parts = raw.split(";");
        if (parts.length != 6) {
            return Optional.empty();
        }
        try {
            return Optional.of(new HomeLocation(
                    UUID.fromString(parts[0]),
                    Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3]),
                    Float.parseFloat(parts[4]),
                    Float.parseFloat(parts[5])));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
