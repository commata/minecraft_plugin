package kr.commata.market.core;

/**
 * 매입 묶음 수량·금액 정수 계산과 일일 한도 게이지 계산 (ECONOMY.md 14.3, 7.4.5).
 *
 * <p>보너스분부터, 묶음 단위로 따로 계산합니다. 한 묶음 안에서 보너스 가능량과 기본 가능량을 섞지 않습니다.
 */
public final class SellMath {

    /** {@code /판매} 클릭별 판매량 (ECONOMY.md 7.4.5): 좌클릭 1묶음, Shift+좌클릭 한 칸, 우클릭 최대. */
    public enum Amount {
        ONE, STACK, MAX
    }

    /**
     * 한 번에 팔 묶음 수 상한. {@code STACK} 은 그 품목 한 칸 최대치({@code maxStackSize}, 대부분 64) 안에 들어가는 묶음 수이며
     * 판매 단위가 한 칸보다 커도 최소 1묶음.
     */
    public static int maxBundles(Amount amount, int maxStackSize, int unit) {
        return switch (amount) {
            case ONE -> 1;
            case MAX -> Integer.MAX_VALUE;
            case STACK -> unit <= 0 ? 0 : Math.max(1, maxStackSize / unit);
        };
    }

    /** 일일 한도 게이지 칸 수 (5줄 슬롯 37–43). */
    public static final int GAUGE_SLOTS = 7;

    public record Plan(
            int bonusBundles,
            int baseBundles,
            int bonusAmount,
            int baseAmount,
            long bonusTotal,
            long baseTotal
    ) {
        public int totalAmount() {
            return bonusAmount + baseAmount;
        }

        public long totalMoney() {
            return bonusTotal + baseTotal;
        }

        public boolean isEmpty() {
            return bonusBundles == 0 && baseBundles == 0;
        }
    }

    private SellMath() {
    }

    /**
     * @param inventoryCount      인벤토리에 있는 판매 가능 수량
     * @param jobAmount           직업 보너스 판매 가능량 (직업 보유 중일 때만 &gt; 0)
     * @param baseAmount          기본가 판매 가능량
     * @param unit                묶음 단위 (1, 2, 16 등)
     * @param basePrice           1묶음 기본가 (정수)
     * @param bonusPrice          1묶음 보너스가 (정수)
     * @param remainingDailyLimit 오늘 남은 하루 판매 한도 (원)
     * @param maxBundles          한 번에 팔 최대 묶음 수 (좌클릭=1, 우클릭=Integer.MAX_VALUE)
     */
    public static Plan calculate(
            int inventoryCount,
            long jobAmount,
            long baseAmount,
            int unit,
            long basePrice,
            long bonusPrice,
            long remainingDailyLimit,
            int maxBundles
    ) {
        if (inventoryCount <= 0 || unit <= 0 || basePrice <= 0 || bonusPrice <= 0
                || remainingDailyLimit <= 0 || maxBundles <= 0) {
            return new Plan(0, 0, 0, 0, 0, 0);
        }
        long invBundles = inventoryCount / unit;
        long bonusQuotaBundles = Math.max(0L, jobAmount) / unit;
        long bonusLimitBundles = remainingDailyLimit / bonusPrice;
        int bonusBundles = (int) Math.min(maxBundles, Math.min(invBundles, Math.min(bonusQuotaBundles, bonusLimitBundles)));
        int bonusAmount = bonusBundles * unit;
        long bonusTotal = bonusBundles * bonusPrice;

        int remMax = maxBundles - bonusBundles;
        long remInvBundles = (inventoryCount - bonusAmount) / unit;
        long remLimit = remainingDailyLimit - bonusTotal;
        long baseQuotaBundles = Math.max(0L, baseAmount) / unit;
        long baseLimitBundles = remLimit / basePrice;
        int baseBundles = remMax <= 0 ? 0
                : (int) Math.min(remMax, Math.min(remInvBundles, Math.min(baseQuotaBundles, baseLimitBundles)));
        int baseAmountSold = baseBundles * unit;
        long baseTotal = baseBundles * basePrice;

        return new Plan(bonusBundles, baseBundles, bonusAmount, baseAmountSold, bonusTotal, baseTotal);
    }

    /** 일일 한도 게이지 사용 칸 수: {@code floor(todayTotal * 7 / dailyLimit)}, 0~7 (ECONOMY.md 7.4.5). */
    public static int gaugeFilled(long todayTotal, long dailyLimit) {
        if (dailyLimit <= 0 || todayTotal <= 0) {
            return 0;
        }
        if (todayTotal >= dailyLimit) {
            return GAUGE_SLOTS;
        }
        return (int) ((todayTotal * GAUGE_SLOTS) / dailyLimit);
    }
}
