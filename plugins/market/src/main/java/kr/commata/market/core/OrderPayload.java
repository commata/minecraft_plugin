package kr.commata.market.core;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code orders.payload}에 저장하는 지급 품목 직렬화 형식 (ECONOMY.md 13.6).
 *
 * <p>서버 재기동 뒤에도 {@code CHARGED} 주문을 정확히 같은 아이템(희귀 자원·책 또는 확정된 뽑기 책 목록)으로
 * 지급할 수 있도록 모든 정보를 텍스트 한 줄/여러 줄로 보관합니다.
 */
public final class OrderPayload {

    public record RareResource(String itemId, String material, int amount, String displayName) {
    }

    public record RareBook(String itemId, String enchantKey, int level, String displayName) {
    }

    public record GachaEntry(String tierId, String tierName, String tierColor,
                             String enchantKey, int level, String bookName,
                             int pityBefore, long unitPrice) {
    }

    private OrderPayload() {
    }

    public static String encodeRareResource(RareCatalog.Entry entry) {
        return "RES|" + escape(entry.id()) + "|" + escape(entry.materialName()) + "|1|" + escape(entry.displayName());
    }

    public static String encodeRareBook(RareCatalog.Entry entry) {
        return "BOOK|" + escape(entry.id()) + "|" + escape(entry.enchantKey()) + "|" + entry.enchantLevel()
                + "|" + escape(entry.displayName());
    }

    public static String encodeGacha(List<GachaCatalog.Draw> draws, long unitPrice) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < draws.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            GachaCatalog.Draw d = draws.get(i);
            sb.append("GACHA|")
                    .append(escape(d.tierId())).append('|')
                    .append(escape(d.tierName())).append('|')
                    .append(escape(d.tierColor())).append('|')
                    .append(escape(d.enchantKey())).append('|')
                    .append(d.level()).append('|')
                    .append(escape(d.bookName())).append('|')
                    .append(d.pityBefore()).append('|')
                    .append(unitPrice);
        }
        return sb.toString();
    }

    public static boolean isRareResource(String payload) {
        return payload != null && payload.startsWith("RES|");
    }

    public static boolean isRareBook(String payload) {
        return payload != null && payload.startsWith("BOOK|");
    }

    public static RareResource decodeRareResource(String payload) {
        String[] p = split(payload);
        if (p.length != 5 || !"RES".equals(p[0])) {
            throw new IllegalArgumentException("잘못된 RES payload: " + payload);
        }
        return new RareResource(unescape(p[1]), unescape(p[2]), Integer.parseInt(p[3]), unescape(p[4]));
    }

    public static RareBook decodeRareBook(String payload) {
        String[] p = split(payload);
        if (p.length != 5 || !"BOOK".equals(p[0])) {
            throw new IllegalArgumentException("잘못된 BOOK payload: " + payload);
        }
        return new RareBook(unescape(p[1]), unescape(p[2]), Integer.parseInt(p[3]), unescape(p[4]));
    }

    public static List<GachaEntry> decodeGacha(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("빈 GACHA payload");
        }
        String[] lines = payload.split("\n");
        List<GachaEntry> out = new ArrayList<>(lines.length);
        for (String line : lines) {
            String[] p = split(line);
            if (p.length != 9 || !"GACHA".equals(p[0])) {
                throw new IllegalArgumentException("잘못된 GACHA payload: " + line);
            }
            out.add(new GachaEntry(
                    unescape(p[1]), unescape(p[2]), unescape(p[3]),
                    unescape(p[4]), Integer.parseInt(p[5]), unescape(p[6]),
                    Integer.parseInt(p[7]), Long.parseLong(p[8])
            ));
        }
        return out;
    }

    /** 이 주문이 인벤토리 빈칸을 몇 칸 필요로 하는지. */
    public static int requiredSlots(String kind, String payload) {
        if ("GACHA_PULL".equals(kind)) {
            return decodeGacha(payload).size();
        }
        return 1;
    }

    /** 관리자 목록·로그용 한 줄 요약. */
    public static String summary(String kind, String payload) {
        try {
            if ("GACHA_PULL".equals(kind)) {
                List<GachaEntry> list = decodeGacha(payload);
                if (list.size() == 1) {
                    GachaEntry e = list.get(0);
                    return "뽑기 1회 (" + e.tierName() + " " + e.bookName() + ")";
                }
                return "뽑기 " + list.size() + "회";
            }
            if (isRareResource(payload)) {
                RareResource r = decodeRareResource(payload);
                return "희귀상점 " + r.displayName() + " x" + r.amount();
            }
            if (isRareBook(payload)) {
                RareBook b = decodeRareBook(payload);
                return "희귀상점 책(" + b.displayName() + ")";
            }
        } catch (RuntimeException ignored) {
            // fallback
        }
        return kind;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("|", "\\p").replace("\n", "\\n");
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                if (next == 'p') {
                    sb.append('|');
                } else if (next == 'n') {
                    sb.append('\n');
                } else {
                    sb.append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String[] split(String line) {
        return line.split("\\|", -1);
    }
}
