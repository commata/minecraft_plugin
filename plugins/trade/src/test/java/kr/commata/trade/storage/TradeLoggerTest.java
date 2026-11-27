package kr.commata.trade.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class TradeLoggerTest {

    @Test
    void formatsSingleLineJsonWithProperEscaping() {
        String line = TradeLogger.formatJsonLine(
                1_700_000_000_000L,
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), "Alice",
                UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"), "Bob",
                List.of("DIAMOND_SWORD x1 (\"명검\"\\1)"),
                List.of("EMERALD x10"),
                1_000L, 0L, 42L, 0L,
                "COMPLETED", "", true
        );
        assertFalse(line.contains("\n"));
        assertTrue(line.startsWith("{") && line.endsWith("}"));
        assertTrue(line.contains("\"aItems\":[\"DIAMOND_SWORD x1 (\\\"명검\\\"\\\\1)\"]"));
        assertTrue(line.contains("\"aPays\":1000"));
        assertTrue(line.contains("\"txAId\":42"));
        assertTrue(line.contains("\"result\":\"COMPLETED\""));
        assertTrue(line.contains("\"recovered\":true"));
    }
}
