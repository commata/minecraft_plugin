package kr.commata.market.core;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 희귀 상점 상품 목록과 설정 검증 (ECONOMY.md 13.4, 7.4.3).
 * 자원은 품목별 주간 한도, 인첸트 책은 모든 책이 공유하는 주간 한도(기본 5권)를 씁니다.
 */
public final class RareCatalog {

    /** 인첸트 책이 공유하는 주간 한도 키 (weekly_limits.limit_key). */
    public static final String BOOK_LIMIT_KEY = "ENCHANTED_BOOK";

    public enum Kind { RESOURCE, BOOK }

    public record Entry(
            String id,
            Kind kind,
            String displayName,
            String materialName,
            String enchantKey,
            int enchantLevel,
            int slot,
            long price,
            int weeklyLimit
    ) {
        public String limitKey() {
            return kind == Kind.BOOK ? BOOK_LIMIT_KEY : id;
        }
    }

    private final boolean enabled;
    private final int bookWeeklyLimit;
    private final List<Entry> entries;
    private final Map<String, Entry> byId;
    private final String lockReason;

    private RareCatalog(boolean enabled, int bookWeeklyLimit, List<Entry> entries, String lockReason) {
        this.enabled = enabled;
        this.bookWeeklyLimit = bookWeeklyLimit;
        this.entries = List.copyOf(entries);
        Map<String, Entry> map = new LinkedHashMap<>();
        for (Entry entry : entries) {
            map.put(entry.id(), entry);
        }
        this.byId = Collections.unmodifiableMap(map);
        this.lockReason = lockReason;
    }

    public static RareCatalog of(boolean enabled, int bookWeeklyLimit, List<Entry> entries) {
        if (!enabled) {
            return new RareCatalog(false, Math.max(0, bookWeeklyLimit), entries, "설정에 의해 비활성화됨");
        }
        String problem = validate(bookWeeklyLimit, entries);
        return new RareCatalog(true, bookWeeklyLimit, entries, problem);
    }

    public static RareCatalog locked(String reason) {
        return new RareCatalog(false, 0, List.of(), reason);
    }

    private static String validate(int bookWeeklyLimit, List<Entry> entries) {
        if (bookWeeklyLimit < 1) {
            return "rare-shop.book-weekly-limit 는 1 이상이어야 합니다: " + bookWeeklyLimit;
        }
        if (entries.isEmpty()) {
            return "희귀 상점 품목이 비어 있습니다.";
        }
        Set<String> seenIds = new HashSet<>();
        Set<Integer> seenSlots = new HashSet<>();
        for (Entry e : entries) {
            if (e.id() == null || e.id().isBlank()) {
                return "희귀 상점 품목 ID가 비어 있습니다.";
            }
            if (!seenIds.add(e.id())) {
                return "희귀 상점 품목 ID 중복: " + e.id();
            }
            if (e.slot() < 0 || e.slot() >= 45) {
                return "희귀 상점 슬롯 범위(0~44) 벗어남: " + e.id() + " (" + e.slot() + ")";
            }
            if (!seenSlots.add(e.slot())) {
                return "희귀 상점 슬롯 중복: " + e.slot();
            }
            if (e.price() <= 0) {
                return "희귀 상점 가격은 1 이상의 정수여야 합니다: " + e.id() + " (" + e.price() + ")";
            }
            if (e.kind() == Kind.RESOURCE) {
                if (e.weeklyLimit() < 1) {
                    return "희귀 상점 주간 한도는 1 이상이어야 합니다: " + e.id();
                }
                if (e.materialName() == null || e.materialName().isBlank()) {
                    return "희귀 상점 자원 재질이 비어 있습니다: " + e.id();
                }
            } else {
                if (e.enchantKey() == null || e.enchantKey().isBlank() || e.enchantLevel() < 1) {
                    return "희귀 상점 인첸트 책 정의가 올바르지 않습니다: " + e.id();
                }
                if (e.weeklyLimit() != bookWeeklyLimit) {
                    return "희귀 상점 책 한도 불일치: " + e.id();
                }
            }
        }
        return null;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean available() {
        return enabled && lockReason == null;
    }

    public String lockReason() {
        return lockReason;
    }

    public int bookWeeklyLimit() {
        return bookWeeklyLimit;
    }

    public List<Entry> entries() {
        return entries;
    }

    public Entry get(String id) {
        return byId.get(id);
    }
}
