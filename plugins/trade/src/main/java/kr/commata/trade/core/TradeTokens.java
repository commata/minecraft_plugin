package kr.commata.trade.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kr.commata.common.playerdata.PlayerDatReader;

/**
 * 플레이어 PDC {@code trade:applied} 에 기록·조회하는 유일한 토큰 규격 (TRADE.md 5장, 13-2장).
 *
 * <p>형식은 세 가지뿐입니다:
 * <ul>
 *   <li>가져오기: {@code <tradeId>:take:<uuid>}</li>
 *   <li>지급: {@code <tradeId>:give:<uuid>:<n>} ({@code n >= 1})</li>
 *   <li>돌려주기: {@code <tradeId>:return:<uuid>:<n>} ({@code n >= 1})</li>
 * </ul>
 *
 * <p>판정 규칙: 같은 목적·받는 사람의 토큰이 디스크에 <b>어느 시도 번호로든</b> {@code FOUND} 면
 * 이미 반영된 것이므로 다시 지급·반환하지 않습니다. 쓰는 곳과 읽는 곳 모두 이 클래스만 사용합니다.
 */
public final class TradeTokens {

    /** 플레이어 PDC 네임스페이스·키 이름 ({@code trade:applied}) */
    public static final String NAMESPACE = "trade";
    public static final String KEY_NAME = "applied";
    public static final PlayerDatReader.Keys DAT_KEYS = PlayerDatReader.Keys.ofMarker(NAMESPACE, KEY_NAME);

    /** PDC 문자열에 보관하는 최근 토큰 최대 개수 */
    public static final int MAX_ENTRIES = 64;

    public enum Purpose {
        TAKE("take"),
        GIVE("give"),
        RETURN("return");

        private final String tag;

        Purpose(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    private TradeTokens() {
    }

    /** 가져오기 토큰: {@code <tradeId>:take:<uuid>} */
    public static String takeToken(UUID tradeId, UUID playerId) {
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(playerId, "playerId");
        return tradeId + ":take:" + playerId;
    }

    /** 지급 토큰: {@code <tradeId>:give:<uuid>:<attempt>} ({@code attempt >= 1}) */
    public static String giveToken(UUID tradeId, UUID playerId, int attempt) {
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(playerId, "playerId");
        if (attempt < 1) {
            throw new IllegalArgumentException("시도 번호는 1 이상이어야 합니다: " + attempt);
        }
        return tradeId + ":give:" + playerId + ":" + attempt;
    }

    /** 돌려주기 토큰: {@code <tradeId>:return:<uuid>:<attempt>} ({@code attempt >= 1}) */
    public static String returnToken(UUID tradeId, UUID playerId, int attempt) {
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(playerId, "playerId");
        if (attempt < 1) {
            throw new IllegalArgumentException("시도 번호는 1 이상이어야 합니다: " + attempt);
        }
        return tradeId + ":return:" + playerId + ":" + attempt;
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

    /** 토큰을 뒤에 추가하고 최근 {@value #MAX_ENTRIES}개만 남깁니다. */
    public static String append(String raw, String token) {
        Objects.requireNonNull(token, "token");
        if (token.isBlank() || token.contains(",")) {
            throw new IllegalArgumentException("잘못된 토큰: " + token);
        }
        List<String> list = new ArrayList<>(parse(raw));
        list.remove(token);
        list.add(token);
        if (list.size() > MAX_ENTRIES) {
            list = list.subList(list.size() - MAX_ENTRIES, list.size());
        }
        return String.join(",", list);
    }

    /** 같은 틱 회수 시 방금 넣은 토큰을 정확히 제거합니다. */
    public static String remove(String raw, String token) {
        Objects.requireNonNull(token, "token");
        List<String> list = new ArrayList<>(parse(raw));
        list.remove(token);
        return String.join(",", list);
    }

    /** 디스크 PDC 문자열에 가져오기 토큰({@code <tradeId>:take:<uuid>})이 있는지 확인합니다. */
    public static boolean containsTake(String raw, UUID tradeId, UUID playerId) {
        String target = takeToken(tradeId, playerId);
        for (String entry : parse(raw)) {
            if (entry.equals(target)) {
                return true;
            }
        }
        return false;
    }

    /** 특정 시도 번호의 지급 토큰이 정확히 있는지 확인합니다. */
    public static boolean containsGiveExact(String raw, UUID tradeId, UUID playerId, int attempt) {
        String target = giveToken(tradeId, playerId, attempt);
        for (String entry : parse(raw)) {
            if (entry.equals(target)) {
                return true;
            }
        }
        return false;
    }

    /** 어느 시도 번호로든 지급 토큰({@code <tradeId>:give:<uuid>:<n>})이 있는지 확인합니다 (TRADE.md 5·13-2장). */
    public static boolean containsGiveAnyAttempt(String raw, UUID tradeId, UUID playerId) {
        return containsAnyAttempt(raw, tradeId, Purpose.GIVE, playerId);
    }

    /** 특정 시도 번호의 돌려주기 토큰이 정확히 있는지 확인합니다. */
    public static boolean containsReturnExact(String raw, UUID tradeId, UUID playerId, int attempt) {
        String target = returnToken(tradeId, playerId, attempt);
        for (String entry : parse(raw)) {
            if (entry.equals(target)) {
                return true;
            }
        }
        return false;
    }

    /** 어느 시도 번호로든 돌려주기 토큰({@code <tradeId>:return:<uuid>:<n>})이 있는지 확인합니다 (TRADE.md 5·13-2장). */
    public static boolean containsReturnAnyAttempt(String raw, UUID tradeId, UUID playerId) {
        return containsAnyAttempt(raw, tradeId, Purpose.RETURN, playerId);
    }

    /** 목적·받는 사람 기준으로 반영 토큰 존재 여부를 판정합니다. */
    public static boolean containsAnyAttempt(String raw, UUID tradeId, Purpose purpose, UUID playerId) {
        return findAnyAttemptToken(raw, tradeId, purpose, playerId) != null;
    }

    /** 목적·받는 사람 기준으로 디스크에 기록된 실제 토큰 문자열을 찾아 반환합니다. 없으면 {@code null}. */
    public static String findAnyAttemptToken(String raw, UUID tradeId, Purpose purpose, UUID playerId) {
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(playerId, "playerId");
        if (purpose == Purpose.TAKE) {
            String target = takeToken(tradeId, playerId);
            for (String entry : parse(raw)) {
                if (entry.equals(target)) {
                    return entry;
                }
            }
            return null;
        }
        String prefix = tradeId + ":" + purpose.tag() + ":" + playerId + ":";
        for (String entry : parse(raw)) {
            if (entry.startsWith(prefix) && isPositiveInteger(entry.substring(prefix.length()))) {
                return entry;
            }
        }
        return null;
    }

    private static boolean isPositiveInteger(String s) {
        if (s.isEmpty() || s.length() > 10) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        try {
            return Integer.parseInt(s) >= 1;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
