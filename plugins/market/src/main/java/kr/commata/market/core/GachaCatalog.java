package kr.commata.market.core;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 인첸트 책 뽑기 설정·검증과 추첨 로직 (ECONOMY.md 13.5, 13.6).
 *
 * <p>모든 등급의 가중치(weight) 합은 정확히 {@value #WEIGHT_SUM}(100.00%)이어야 하고,
 * 각 등급에 책이 1권 이상 있으며 천장 대상 등급(전설)이 정확히 하나 있어야 합니다.
 * 하나라도 어긋나면 뽑기를 잠급니다.
 */
public final class GachaCatalog {

    /** 100.00% 에 해당하는 정수 가중치 합 (ECONOMY.md 13.6). */
    public static final int WEIGHT_SUM = 10_000;

    public record Book(String enchantKey, int level, String displayName) {
    }

    public record Tier(
            String id,
            String displayName,
            int weight,
            String color,
            String iconMaterial,
            int slot,
            boolean pityTarget,
            List<Book> books
    ) {
        public Tier {
            books = List.copyOf(books);
        }

        /** 가중치를 "60%" 또는 "9.5%" 형식으로 표기. */
        public String percentText() {
            if (weight % 100 == 0) {
                return (weight / 100) + "%";
            }
            if (weight % 10 == 0) {
                return String.format(Locale.ROOT, "%.1f%%", weight / 100.0);
            }
            return String.format(Locale.ROOT, "%.2f%%", weight / 100.0);
        }
    }

    /** 뽑기 1회 결과. */
    public record Draw(
            String tierId,
            String tierName,
            String tierColor,
            String enchantKey,
            int level,
            String bookName,
            int pityBefore,
            int pityAfter,
            boolean pityTriggered
    ) {
    }

    private final boolean enabled;
    private final long price;
    private final int dailyLimit;
    private final int pityThreshold;
    private final List<Tier> tiers;
    private final Tier pityTier;
    private final String lockReason;

    private GachaCatalog(boolean enabled, long price, int dailyLimit, int pityThreshold,
                         List<Tier> tiers, Tier pityTier, String lockReason) {
        this.enabled = enabled;
        this.price = price;
        this.dailyLimit = dailyLimit;
        this.pityThreshold = pityThreshold;
        this.tiers = List.copyOf(tiers);
        this.pityTier = pityTier;
        this.lockReason = lockReason;
    }

    public static GachaCatalog of(boolean enabled, long price, int dailyLimit, int pityThreshold, List<Tier> tiers) {
        if (!enabled) {
            return new GachaCatalog(false, Math.max(0, price), Math.max(0, dailyLimit),
                    Math.max(1, pityThreshold), tiers, null, "설정에 의해 비활성화됨");
        }
        if (price <= 0) {
            return locked("gacha.price 는 1 이상의 정수여야 합니다: " + price);
        }
        if (dailyLimit < 1) {
            return locked("gacha.daily-limit 는 1 이상이어야 합니다: " + dailyLimit);
        }
        if (pityThreshold < 1) {
            return locked("gacha.pity-threshold 는 1 이상이어야 합니다: " + pityThreshold);
        }
        if (tiers.isEmpty()) {
            return locked("gacha.tiers 가 비어 있습니다.");
        }
        int sum = 0;
        Tier pity = null;
        for (Tier tier : tiers) {
            if (tier.id() == null || tier.id().isBlank()) {
                return locked("뽑기 등급 ID가 비어 있습니다.");
            }
            if (tier.weight() <= 0) {
                return locked("뽑기 등급 가중치는 1 이상이어야 합니다: " + tier.id());
            }
            if (tier.books().isEmpty()) {
                return locked("뽑기 등급에 책이 없습니다: " + tier.id());
            }
            for (Book book : tier.books()) {
                if (book.enchantKey() == null || book.enchantKey().isBlank() || book.level() < 1) {
                    return locked("뽑기 책 정의가 올바르지 않습니다: " + tier.id());
                }
            }
            if (tier.pityTarget()) {
                if (pity != null) {
                    return locked("천장 대상 등급(pity-target)은 하나만 지정해야 합니다.");
                }
                pity = tier;
            }
            sum += tier.weight();
        }
        if (sum != WEIGHT_SUM) {
            return locked("뽑기 등급 weight 합이 " + WEIGHT_SUM + " 이 아닙니다 (현재 " + sum + ").");
        }
        if (pity == null) {
            return locked("천장 대상 등급(pity-target: true)이 없습니다.");
        }
        return new GachaCatalog(true, price, dailyLimit, pityThreshold, tiers, pity, null);
    }

    public static GachaCatalog locked(String reason) {
        return new GachaCatalog(false, 0, 0, 1, List.of(), null, reason);
    }

    /**
     * {@code count}회 뽑기를 순차 추첨합니다. 천장 카운터는 전설을 뽑았을 때만 0으로 초기화됩니다 (ECONOMY.md 13.5).
     * {@code pityBefore == pityThreshold - 1}이면 이번 회차는 천장 확정입니다(예: 79회 누적 후 80번째 전설 확정).
     */
    public List<Draw> roll(int count, int initialPity, Random random) {
        if (!available()) {
            throw new IllegalStateException("뽑기가 잠겨 있습니다: " + lockReason);
        }
        if (count < 1) {
            throw new IllegalArgumentException("뽑기 횟수는 1 이상이어야 합니다: " + count);
        }
        Random rng = random == null ? new SecureRandom() : random;
        List<Draw> results = new ArrayList<>(count);
        int pity = Math.max(0, initialPity);
        for (int i = 0; i < count; i++) {
            boolean guaranteed = pity >= pityThreshold - 1;
            Tier tier = guaranteed ? pityTier : pickTier(rng.nextInt(WEIGHT_SUM));
            Book book = tier.books().get(rng.nextInt(tier.books().size()));
            int nextPity = tier.pityTarget() ? 0 : pity + 1;
            results.add(new Draw(
                    tier.id(), tier.displayName(), tier.color(),
                    book.enchantKey(), book.level(), book.displayName(),
                    pity, nextPity, guaranteed
            ));
            pity = nextPity;
        }
        return results;
    }

    private Tier pickTier(int roll) {
        int acc = 0;
        for (Tier tier : tiers) {
            acc += tier.weight();
            if (roll < acc) {
                return tier;
            }
        }
        return tiers.get(tiers.size() - 1);
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

    public long price() {
        return price;
    }

    public int dailyLimit() {
        return dailyLimit;
    }

    public int pityThreshold() {
        return pityThreshold;
    }

    public List<Tier> tiers() {
        return tiers;
    }

    public Tier pityTier() {
        return pityTier;
    }
}
