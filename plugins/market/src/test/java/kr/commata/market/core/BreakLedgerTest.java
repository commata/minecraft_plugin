package kr.commata.market.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class BreakLedgerTest {

    @Test
    void nestedBreakDoesNotOverwriteOuterBlock() {
        BreakLedger<String, String> ledger = new BreakLedger<>();
        // 바깥 블록 파괴 → 그 처리 안에서 연쇄 파괴로 안쪽 블록 파괴·드롭 → 바깥 블록 드롭
        ledger.put("0,64,0", 100, "outer");
        ledger.put("0,65,0", 100, "inner");
        assertEquals("inner", ledger.take("0,65,0"));
        assertEquals("outer", ledger.take("0,64,0"));
        assertEquals(0, ledger.size());
    }

    @Test
    void cancelledBreakRemovedAndStaleEntriesPurged() {
        BreakLedger<String, String> ledger = new BreakLedger<>();
        ledger.put("a", 10, "cancelled");
        ledger.remove("a");
        assertNull(ledger.take("a"));

        ledger.put("b", 10, "no-drop-event");
        ledger.put("c", 11, "this-tick");
        ledger.purgeBefore(11);
        assertNull(ledger.take("b"));
        assertEquals("this-tick", ledger.take("c"));
    }
}
