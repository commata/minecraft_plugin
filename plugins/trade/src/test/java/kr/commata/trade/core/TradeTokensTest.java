package kr.commata.trade.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class TradeTokensTest {

    private static final UUID TRADE_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TRADE_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PLAYER_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID PLAYER_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Test
    void tokenFormatMatchesCanonicalSpec() {
        assertEquals(TRADE_1 + ":take:" + PLAYER_A, TradeTokens.takeToken(TRADE_1, PLAYER_A));
        assertEquals(TRADE_1 + ":give:" + PLAYER_A + ":1", TradeTokens.giveToken(TRADE_1, PLAYER_A, 1));
        assertEquals(TRADE_1 + ":return:" + PLAYER_B + ":3", TradeTokens.returnToken(TRADE_1, PLAYER_B, 3));
        assertThrows(IllegalArgumentException.class, () -> TradeTokens.giveToken(TRADE_1, PLAYER_A, 0));
        assertThrows(IllegalArgumentException.class, () -> TradeTokens.returnToken(TRADE_1, PLAYER_A, -1));
    }

    @Test
    void anyAttemptMatchesAcrossAttemptsAndDistinguishesPurposeAndRecipient() {
        String raw = "";
        raw = TradeTokens.append(raw, TradeTokens.takeToken(TRADE_1, PLAYER_A));
        raw = TradeTokens.append(raw, TradeTokens.giveToken(TRADE_1, PLAYER_A, 4));
        raw = TradeTokens.append(raw, TradeTokens.returnToken(TRADE_2, PLAYER_B, 2));

        assertTrue(TradeTokens.containsTake(raw, TRADE_1, PLAYER_A));
        assertFalse(TradeTokens.containsTake(raw, TRADE_1, PLAYER_B));

        assertTrue(TradeTokens.containsGiveExact(raw, TRADE_1, PLAYER_A, 4));
        assertFalse(TradeTokens.containsGiveExact(raw, TRADE_1, PLAYER_A, 1));
        // 어느 시도 번호로든 FOUND 면 이미 반영됨 (TRADE.md 5·13-2장)
        assertTrue(TradeTokens.containsGiveAnyAttempt(raw, TRADE_1, PLAYER_A));
        assertFalse(TradeTokens.containsGiveAnyAttempt(raw, TRADE_1, PLAYER_B));
        assertFalse(TradeTokens.containsReturnAnyAttempt(raw, TRADE_1, PLAYER_A));

        assertTrue(TradeTokens.containsReturnAnyAttempt(raw, TRADE_2, PLAYER_B));
        assertFalse(TradeTokens.containsGiveAnyAttempt(raw, TRADE_2, PLAYER_B));
    }

    @Test
    void removeDeletesExactTokenForSameTickRollback() {
        String t1 = TradeTokens.giveToken(TRADE_1, PLAYER_A, 1);
        String t2 = TradeTokens.giveToken(TRADE_1, PLAYER_A, 2);
        String raw = TradeTokens.append(TradeTokens.append("", t1), t2);

        String afterRemove = TradeTokens.remove(raw, t2);
        assertTrue(TradeTokens.containsGiveExact(afterRemove, TRADE_1, PLAYER_A, 1));
        assertFalse(TradeTokens.containsGiveExact(afterRemove, TRADE_1, PLAYER_A, 2));

        String empty = TradeTokens.remove(afterRemove, t1);
        assertFalse(TradeTokens.containsGiveAnyAttempt(empty, TRADE_1, PLAYER_A));
    }

    @Test
    void invalidAttemptSuffixIsNotMatched() {
        String bogus = TRADE_1 + ":give:" + PLAYER_A + ":abc,"
                + TRADE_1 + ":give:" + PLAYER_A + ":0,"
                + TRADE_1 + ":give:" + PLAYER_A + ":-1";
        assertFalse(TradeTokens.containsGiveAnyAttempt(bogus, TRADE_1, PLAYER_A));
    }

    @Test
    void appendCapsAtMaxEntriesKeepingMostRecent() {
        String raw = "";
        for (int i = 1; i <= TradeTokens.MAX_ENTRIES + 10; i++) {
            raw = TradeTokens.append(raw, TradeTokens.giveToken(TRADE_1, PLAYER_A, i));
        }
        assertEquals(TradeTokens.MAX_ENTRIES, TradeTokens.parse(raw).size());
        assertFalse(TradeTokens.containsGiveExact(raw, TRADE_1, PLAYER_A, 1));
        assertTrue(TradeTokens.containsGiveExact(raw, TRADE_1, PLAYER_A, TradeTokens.MAX_ENTRIES + 10));
    }
}
