package kr.commata.market.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 플레이어 PDC {@code market:applied_tx}에 저장하는 거래 확인 토큰 목록 (ECONOMY.md 13.6, 14.3).
 *
 * <p>형식은 {@code "<tx_id>#<attempt>"}를 쉼표로 이어 최근 {@value #MAX_ENTRIES}개만 유지합니다.
 * Bukkit에 의존하지 않아 단위 테스트와 {@link PlayerDatReader}에서 함께 씁니다.
 */
public final class AppliedTxTokens {

    public static final int MAX_ENTRIES = 32;

    private AppliedTxTokens() {
    }

    public static String token(String txId, int attempt) {
        return txId + "#" + attempt;
    }

    public static List<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String[] parts = raw.split(",");
        List<String> out = new ArrayList<>(parts.length);
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /** 토큰을 뒤에 추가하고 최근 32개만 남깁니다. */
    public static String append(String raw, String token) {
        List<String> list = new ArrayList<>(parse(raw));
        list.remove(token);
        list.add(token);
        if (list.size() > MAX_ENTRIES) {
            list = list.subList(list.size() - MAX_ENTRIES, list.size());
        }
        return String.join(",", list);
    }

    /** 같은 틱 회수 시 방금 넣은 토큰을 제거합니다. */
    public static String remove(String raw, String token) {
        List<String> list = new ArrayList<>(parse(raw));
        list.remove(token);
        return String.join(",", list);
    }

    /** 특정 시도 토큰({@code tx_id#attempt})이 정확히 들어 있는지. */
    public static boolean containsExact(String raw, String txId, int attempt) {
        String target = token(txId, attempt);
        for (String entry : parse(raw)) {
            if (entry.equals(target)) {
                return true;
            }
        }
        return false;
    }

    /** 기동·접속 복구용: 어느 시도든 {@code tx_id#}로 시작하는 토큰이 있는지. */
    public static boolean containsAnyAttempt(String raw, String txId) {
        String prefix = txId + "#";
        for (String entry : parse(raw)) {
            if (entry.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
