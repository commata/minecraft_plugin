package kr.commata.trade.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class OfferValidationTest {

    @Test
    void parseAndCheckMoneyValidatesIntegersBoundsAndBalance() {
        var okZero = OfferValidation.parseAndCheckMoney("0", 0L);
        assertTrue(okZero.ok());
        assertEquals(0L, okZero.amount());

        var ok = OfferValidation.parseAndCheckMoney(" 12500 ", 50_000L);
        assertTrue(ok.ok());
        assertEquals(12_500L, ok.amount());

        assertEquals(OfferValidation.MoneyCheck.EXCEEDS_BALANCE,
                OfferValidation.parseAndCheckMoney("50001", 50_000L).check());
        assertEquals(OfferValidation.MoneyCheck.INVALID_FORMAT,
                OfferValidation.parseAndCheckMoney("-100", 50_000L).check());
        assertEquals(OfferValidation.MoneyCheck.INVALID_FORMAT,
                OfferValidation.parseAndCheckMoney("10.5", 50_000L).check());
        assertEquals(OfferValidation.MoneyCheck.INVALID_FORMAT,
                OfferValidation.parseAndCheckMoney("1,000", 50_000L).check());
        assertEquals(OfferValidation.MoneyCheck.INVALID_FORMAT,
                OfferValidation.parseAndCheckMoney("1e3", 50_000L).check());
        assertEquals(OfferValidation.MoneyCheck.EXCEEDS_MAX,
                OfferValidation.parseAndCheckMoney(String.valueOf(OfferValidation.MAX_AMOUNT + 1), Long.MAX_VALUE).check());
    }

    @Test
    void checkSettlementBalanceDetectsInsufficientAndOverflowLimit() {
        assertEquals(OfferValidation.BalanceCheck.OK,
                OfferValidation.checkSettlementBalance(10_000L, 10_000L, 5_000L));
        assertEquals(OfferValidation.BalanceCheck.INSUFFICIENT,
                OfferValidation.checkSettlementBalance(9_999L, 10_000L, 5_000L));
        assertEquals(OfferValidation.BalanceCheck.OK,
                OfferValidation.checkSettlementBalance(OfferValidation.MAX_AMOUNT, 100L, 100L));
        assertEquals(OfferValidation.BalanceCheck.BALANCE_LIMIT,
                OfferValidation.checkSettlementBalance(OfferValidation.MAX_AMOUNT, 0L, 1L));
    }

    @Test
    void validateOffersDetectsMissingDissimilarAndReducedSlots() {
        Map<Integer, Integer> offers = Map.of(
                0, 10, // 정상 (줍기로 12개 되어도 10개 이상·동일이면 OK)
                1, 5,  // 수량 감소 (4개)
                2, 1,  // 내구도·꾸러미 내용 변화로 isSimilar=false
                3, 1   // 빈 칸으로 바뀜
        );
        Map<Integer, OfferValidation.SlotState> current = Map.of(
                0, new OfferValidation.SlotState(0, true, true, 12),
                1, new OfferValidation.SlotState(1, true, true, 4),
                2, new OfferValidation.SlotState(2, true, false, 1),
                3, new OfferValidation.SlotState(3, false, false, 0)
        );

        var result = OfferValidation.validateOffers(offers, current);
        assertFalse(result.valid());
        assertEquals(3, result.invalidSlots().size());
        assertTrue(result.invalidSlots().containsAll(List.of(1, 2, 3)));
        assertFalse(result.invalidSlots().contains(0));
    }

    @Test
    void formatWonUsesKoreanCommaNotation() {
        assertEquals("0원", OfferValidation.formatWon(0L));
        assertEquals("12,340원", OfferValidation.formatWon(12_340L));
    }
}
