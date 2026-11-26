package kr.commata.market.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 직업별 서버 매입 품목 목록과 가격 검증 (ECONOMY.md 14.4, 14.7, 7.4.5).
 *
 * <p>배율을 런타임에 곱하지 않고 최종 정수 {@code basePrice}·{@code bonusPrice}·{@code maxPrice}를 직접 읽습니다.
 * {@code 0 < basePrice <= bonusPrice} 및 {@code maxPrice > 0}일 때 {@code bonusPrice <= maxPrice}를 검사하고,
 * 하나라도 어긋나면 매입을 잠급니다.
 */
public final class SellCatalog {

    /** 2–4줄 품목 칸 21개 (ECONOMY.md 7.4.5). */
    public static final int[] ITEM_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };

    public record Item(
            String id,
            String jobId,
            String displayName,
            int unit,
            long basePrice,
            long bonusPrice,
            long maxPrice
    ) {
    }

    public record Category(
            String jobId,
            String displayName,
            String bonusLabel,
            int tabSlot,
            String iconMaterial,
            String joinedIconMaterial,
            boolean glowWhenJoined,
            List<Item> items
    ) {
        public Category {
            items = List.copyOf(items);
        }
    }

    private final boolean enabled;
    private final long dailyLimit;
    private final List<Category> categories;
    private final Map<String, Category> byJob;
    private final Map<String, Item> byItemId;
    private final String lockReason;

    private SellCatalog(boolean enabled, long dailyLimit, List<Category> categories, String lockReason) {
        this.enabled = enabled;
        this.dailyLimit = dailyLimit;
        this.categories = List.copyOf(categories);
        Map<String, Category> jobMap = new LinkedHashMap<>();
        Map<String, Item> itemMap = new LinkedHashMap<>();
        for (Category category : categories) {
            jobMap.put(category.jobId(), category);
            for (Item item : category.items()) {
                itemMap.put(item.id(), item);
            }
        }
        this.byJob = Collections.unmodifiableMap(jobMap);
        this.byItemId = Collections.unmodifiableMap(itemMap);
        this.lockReason = lockReason;
    }

    public static SellCatalog of(boolean enabled, long dailyLimit, List<Category> categories) {
        if (!enabled) {
            return new SellCatalog(false, Math.max(0, dailyLimit), categories, "설정에 의해 비활성화됨");
        }
        if (dailyLimit <= 0) {
            return locked("sell.daily-limit 는 1 이상의 정수여야 합니다: " + dailyLimit);
        }
        if (categories.isEmpty()) {
            return locked("sell.categories 가 비어 있습니다.");
        }
        Set<String> seenJobs = new HashSet<>();
        Set<Integer> seenTabs = new HashSet<>();
        Set<String> seenItems = new HashSet<>();
        for (Category cat : categories) {
            if (cat.jobId() == null || cat.jobId().isBlank()) {
                return locked("매입 카테고리 직업 ID가 비어 있습니다.");
            }
            if (!seenJobs.add(cat.jobId())) {
                return locked("매입 카테고리 직업 ID 중복: " + cat.jobId());
            }
            if (cat.tabSlot() < 0 || cat.tabSlot() >= 9) {
                return locked("매입 탭 슬롯은 1줄(0~8)이어야 합니다: " + cat.jobId());
            }
            if (!seenTabs.add(cat.tabSlot())) {
                return locked("매입 탭 슬롯 중복: " + cat.tabSlot());
            }
            if (cat.items().isEmpty()) {
                return locked("매입 카테고리에 품목이 없습니다: " + cat.jobId());
            }
            if (cat.items().size() > ITEM_SLOTS.length) {
                return locked("매입 카테고리 품목이 21칸을 넘습니다: " + cat.jobId() + " (" + cat.items().size() + ")");
            }
            for (Item item : cat.items()) {
                if (item.id() == null || item.id().isBlank()) {
                    return locked("매입 품목 ID가 비어 있습니다.");
                }
                if (!seenItems.add(item.id())) {
                    return locked("매입 품목 ID 중복: " + item.id());
                }
                if (item.unit() < 1) {
                    return locked("매입 단위는 1 이상이어야 합니다: " + item.id());
                }
                if (item.basePrice() <= 0) {
                    return locked("매입 기본가는 1 이상의 정수여야 합니다: " + item.id());
                }
                if (item.bonusPrice() < item.basePrice()) {
                    return locked("매입 보너스가는 기본가 이상이어야 합니다: " + item.id()
                            + " (base=" + item.basePrice() + ", bonus=" + item.bonusPrice() + ")");
                }
                if (item.maxPrice() < 0) {
                    return locked("매입 상한은 0 이상이어야 합니다: " + item.id());
                }
                if (item.maxPrice() > 0 && item.bonusPrice() > item.maxPrice()) {
                    return locked("매입 보너스가가 상한을 넘습니다: " + item.id()
                            + " (bonus=" + item.bonusPrice() + ", max=" + item.maxPrice() + ")");
                }
            }
        }
        return new SellCatalog(true, dailyLimit, categories, null);
    }

    public static SellCatalog locked(String reason) {
        return new SellCatalog(false, 0, List.of(), reason);
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

    public long dailyLimit() {
        return dailyLimit;
    }

    public List<Category> categories() {
        return categories;
    }

    public Category category(String jobId) {
        return byJob.get(jobId);
    }

    public Item item(String id) {
        return byItemId.get(id);
    }

    public List<String> itemIdsOfJob(String jobId) {
        Category cat = byJob.get(jobId);
        if (cat == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(cat.items().size());
        for (Item item : cat.items()) {
            ids.add(item.id());
        }
        return ids;
    }

    /** 보유하지 않은 직업 카테고리의 모든 품목 ID 목록 (탈퇴 이관 보정용, ECONOMY.md 14.2). */
    public List<String> itemIdsNotInJobs(Set<String> joinedJobs) {
        List<String> ids = new ArrayList<>();
        for (Category cat : categories) {
            if (!joinedJobs.contains(cat.jobId())) {
                for (Item item : cat.items()) {
                    ids.add(item.id());
                }
            }
        }
        return ids;
    }
}
