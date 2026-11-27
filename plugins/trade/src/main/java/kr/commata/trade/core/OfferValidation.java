package kr.commata.trade.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 거래 제시(아이템 칸 상태·금액·최종 잔액 상한) 순수 검증 규칙 (TRADE.md 3·4·5장).
 */
public final class OfferValidation {

    /** 금액·잔액 상한 (2^53 - 1, Bank {@code Amounts.MAX} 와 동일, ECONOMY.md 2.2) */
    public static final long MAX_AMOUNT = (1L << 53) - 1;

    /** 한쪽 최대 제시 칸 수 (4×4 = 16칸, ECONOMY.md 7.4.6) */
    public static final int MAX_OFFER_SLOTS = 16;

    /** 플레이어 보관함 슬롯 수 (0..35) */
    public static final int STORAGE_SIZE = 36;

    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,16}");

    public enum MoneyCheck {
        OK,
        INVALID_FORMAT,
        NEGATIVE,
        EXCEEDS_MAX,
        EXCEEDS_BALANCE
    }

    public record MoneyParseResult(MoneyCheck check, long amount) {
        public boolean ok() {
            return check == MoneyCheck.OK;
        }
    }

    /**
     * 제시 시점 스냅샷과 현재 인벤토리 해당 칸을 비교한 결과 값 객체.
     *
     * @param invSlot        보관함 칸 번호 (0..35)
     * @param present        현재 칸에 공기 아닌 아이템이 있는지
     * @param similar        제시 시점 깊은 스냅샷과 {@code isSimilar} 일치하는지 (내구도·꾸러미·인첸트 등 포함)
     * @param currentAmount  현재 칸의 수량
     */
    public record SlotState(int invSlot, boolean present, boolean similar, int currentAmount) {
    }

    public record OfferCheckResult(boolean valid, List<Integer> invalidSlots) {
    }

    public enum BalanceCheck {
        OK,
        INSUFFICIENT,
        BALANCE_LIMIT
    }

    private OfferValidation() {
    }

    /**
     * 금액 입력 문자열을 검증합니다. 정수(0 이상, 소수점·부호·쉼표·공백 거부), 상한 이하, 잔액 이하만 허용합니다.
     */
    public static MoneyParseResult parseAndCheckMoney(String raw, long currentBalance) {
        if (raw == null) {
            return new MoneyParseResult(MoneyCheck.INVALID_FORMAT, -1L);
        }
        String trimmed = raw.trim();
        if (!DIGITS.matcher(trimmed).matches()) {
            return new MoneyParseResult(MoneyCheck.INVALID_FORMAT, -1L);
        }
        long value;
        try {
            value = Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            return new MoneyParseResult(MoneyCheck.EXCEEDS_MAX, -1L);
        }
        return checkMoneyValue(value, currentBalance);
    }

    /** 이미 정수로 받은 금액이 0 이상, 상한 이하, 잔액 이하인지 확인합니다. */
    public static MoneyParseResult checkMoneyValue(long value, long currentBalance) {
        if (value < 0) {
            return new MoneyParseResult(MoneyCheck.NEGATIVE, -1L);
        }
        if (value > MAX_AMOUNT) {
            return new MoneyParseResult(MoneyCheck.EXCEEDS_MAX, -1L);
        }
        if (currentBalance < 0 || value > currentBalance) {
            return new MoneyParseResult(MoneyCheck.EXCEEDS_BALANCE, -1L);
        }
        return new MoneyParseResult(MoneyCheck.OK, value);
    }

    /**
     * 정산 직전 한쪽의 잔액과 최종 잔액 상한({@link #MAX_AMOUNT})을 오버플로 없이 검사합니다 (TRADE.md 4·5장).
     */
    public static BalanceCheck checkSettlementBalance(long currentBalance, long pays, long receives) {
        if (pays < 0 || receives < 0 || pays > MAX_AMOUNT || receives > MAX_AMOUNT) {
            return BalanceCheck.BALANCE_LIMIT;
        }
        if (currentBalance < 0 || currentBalance < pays) {
            return BalanceCheck.INSUFFICIENT;
        }
        long afterPay = currentBalance - pays;
        if (receives > MAX_AMOUNT - afterPay) {
            return BalanceCheck.BALANCE_LIMIT;
        }
        return BalanceCheck.OK;
    }

    /**
     * 제시 목록의 모든 칸이 현재 인벤토리에서 조건(존재, {@code isSimilar} 일치, 수량 >= 제시 개수)을 만족하는지 검사합니다.
     */
    public static OfferCheckResult validateOffers(Map<Integer, Integer> offers, Map<Integer, SlotState> currentSlots) {
        List<Integer> invalid = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : offers.entrySet()) {
            int invSlot = entry.getKey();
            int offeredAmount = entry.getValue();
            SlotState state = currentSlots.get(invSlot);
            if (offeredAmount <= 0 || invSlot < 0 || invSlot >= STORAGE_SIZE
                    || state == null || !state.present() || !state.similar() || state.currentAmount() < offeredAmount) {
                invalid.add(invSlot);
            }
        }
        return new OfferCheckResult(invalid.isEmpty(), List.copyOf(invalid));
    }

    /** 양쪽 모두 제시한 아이템과 금액이 비어 있는지 확인합니다. */
    public static boolean isCompletelyEmpty(int itemsFromA, long aPays, int itemsFromB, long bPays) {
        return itemsFromA <= 0 && aPays <= 0 && itemsFromB <= 0 && bPays <= 0;
    }

    /** {@code 12340} → {@code "12,340원"} */
    public static String formatWon(long amount) {
        return String.format(Locale.ROOT, "%,d원", amount);
    }
}
