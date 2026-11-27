package kr.commata.trade.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import kr.commata.common.playerdata.PlayerDatReader;
import org.junit.jupiter.api.Test;

/**
 * TRADE.md 6장·13-1장·13-2장의 복구 결정표 전 조합을 검증합니다.
 */
final class RecoveryPolicyTest {

    @Test
    void takingSideEvaluationRespectsConnGenAndThreeRetryLock() {
        // 같은 접속 세션에서 ABSENT → 1·2회는 PENDING_IN_SESSION(재시도), 3회 연속은 lockPlayer=true
        var r1 = RecoveryPolicy.evaluateTakingSide(true, Journal.SideStatus.PENDING, true,
                PlayerDatReader.MarkerStatus.ABSENT, 0);
        assertEquals(RecoveryPolicy.TakingSideState.PENDING_IN_SESSION, r1.state());
        assertEquals(1, r1.nextSaveRetries());
        assertFalse(r1.lockPlayer());

        var r2 = RecoveryPolicy.evaluateTakingSide(true, Journal.SideStatus.PENDING, true,
                PlayerDatReader.MarkerStatus.ABSENT, 1);
        assertEquals(RecoveryPolicy.TakingSideState.PENDING_IN_SESSION, r2.state());
        assertEquals(2, r2.nextSaveRetries());
        assertFalse(r2.lockPlayer());

        var r3 = RecoveryPolicy.evaluateTakingSide(true, Journal.SideStatus.PENDING, true,
                PlayerDatReader.MarkerStatus.ABSENT, 2);
        assertEquals(RecoveryPolicy.TakingSideState.PENDING_IN_SESSION, r3.state());
        assertEquals(3, r3.nextSaveRetries());
        assertTrue(r3.lockPlayer());

        // 퇴장·재접속·기동(sameConnSession=false)에서 ABSENT → 확정 FINAL_ABSENT
        var offlineAbsent = RecoveryPolicy.evaluateTakingSide(true, Journal.SideStatus.PENDING, false,
                PlayerDatReader.MarkerStatus.ABSENT, 0);
        assertEquals(RecoveryPolicy.TakingSideState.FINAL_ABSENT, offlineAbsent.state());
        assertTrue(offlineAbsent.updatePersistedToAbsent());

        // UNREADABLE → 어떤 세션이든 UNREADABLE (절대 ABSENT 로 보지 않음)
        var unreadable = RecoveryPolicy.evaluateTakingSide(true, Journal.SideStatus.PENDING, false,
                PlayerDatReader.MarkerStatus.UNREADABLE, 0);
        assertEquals(RecoveryPolicy.TakingSideState.UNREADABLE, unreadable.state());

        // FOUND → FINAL_FOUND + 재시도 0 초기화
        var found = RecoveryPolicy.evaluateTakingSide(true, Journal.SideStatus.PENDING, true,
                PlayerDatReader.MarkerStatus.FOUND, 2);
        assertEquals(RecoveryPolicy.TakingSideState.FINAL_FOUND, found.state());
        assertEquals(0, found.nextSaveRetries());
        assertTrue(found.updatePersistedToFound());

        // 가져갈 아이템이 없는 쪽은 즉시 FINAL_FOUND
        var noItems = RecoveryPolicy.evaluateTakingSide(false, Journal.SideStatus.PENDING, false,
                PlayerDatReader.MarkerStatus.UNREADABLE, 0);
        assertEquals(RecoveryPolicy.TakingSideState.FINAL_FOUND, noItems.state());
    }

    @Test
    void exhaustiveTakingDecisionMatrixMatchesTradeMdChapter6() {
        // 4 × 4 × 2 × 5 = 160 조합 전체를 빠짐없이 검증 (TRADE.md 6·13-2·14장)
        for (RecoveryPolicy.TakingSideState a : RecoveryPolicy.TakingSideState.values()) {
            for (RecoveryPolicy.TakingSideState b : RecoveryPolicy.TakingSideState.values()) {
                for (boolean hasMoney : new boolean[]{false, true}) {
                    for (RecoveryPolicy.BankLookupState bank : RecoveryPolicy.BankLookupState.values()) {
                        RecoveryPolicy.TakingDecision actual = RecoveryPolicy.decideTaking(a, b, hasMoney, bank);
                        RecoveryPolicy.TakingDecision expected = expectedDecision(a, b, hasMoney, bank);
                        assertEquals(expected, actual,
                                "조합 불일치: sideA=" + a + ", sideB=" + b + ", hasMoney=" + hasMoney + ", bank=" + bank);
                    }
                }
            }
        }
    }

    @Test
    void deferredDecisionWhenOneSideFinalAbsentAndOtherPendingOrUnreadable() {
        // v4.1 재검증 Critical: 한쪽 재접속으로 FINAL_ABSENT 가 되어도 상대가 PENDING/UNREADABLE 이면 결정 유예!
        assertEquals(RecoveryPolicy.TakingDecision.DEFER, RecoveryPolicy.decideTaking(
                RecoveryPolicy.TakingSideState.FINAL_ABSENT,
                RecoveryPolicy.TakingSideState.PENDING_IN_SESSION,
                false, RecoveryPolicy.BankLookupState.NOT_CHECKED));
        assertEquals(RecoveryPolicy.TakingDecision.DEFER, RecoveryPolicy.decideTaking(
                RecoveryPolicy.TakingSideState.UNREADABLE,
                RecoveryPolicy.TakingSideState.FINAL_ABSENT,
                true, RecoveryPolicy.BankLookupState.NOT_FOUND));
        // 양쪽 모두 최종 확정(FINAL_ABSENT + FINAL_FOUND)이면 비로소 RETURNING
        assertEquals(RecoveryPolicy.TakingDecision.TRANSITION_TO_RETURNING, RecoveryPolicy.decideTaking(
                RecoveryPolicy.TakingSideState.FINAL_ABSENT,
                RecoveryPolicy.TakingSideState.FINAL_FOUND,
                true, RecoveryPolicy.BankLookupState.NOT_FOUND));
    }

    @Test
    void giveAndReturnDeliveryDecisionsCoverAllDiskAndOnlineStates() {
        // 디스크에 토큰이 있으면(어느 시도 번호든) 오프라인·빈칸부족이어도 ALREADY_DONE
        assertEquals(RecoveryPolicy.DeliveryDecision.ALREADY_DONE,
                RecoveryPolicy.decideGiveSide(true, Journal.SideStatus.PENDING,
                        PlayerDatReader.MarkerStatus.FOUND, false, false));
        assertEquals(RecoveryPolicy.DeliveryDecision.DELIVER_NOW,
                RecoveryPolicy.decideGiveSide(true, Journal.SideStatus.PENDING,
                        PlayerDatReader.MarkerStatus.ABSENT, true, true));
        assertEquals(RecoveryPolicy.DeliveryDecision.WAIT_INVENTORY_FULL,
                RecoveryPolicy.decideGiveSide(true, Journal.SideStatus.PENDING,
                        PlayerDatReader.MarkerStatus.ABSENT, true, false));
        assertEquals(RecoveryPolicy.DeliveryDecision.WAIT_OFFLINE,
                RecoveryPolicy.decideGiveSide(true, Journal.SideStatus.PENDING,
                        PlayerDatReader.MarkerStatus.ABSENT, false, true));
        assertEquals(RecoveryPolicy.DeliveryDecision.WAIT_UNREADABLE,
                RecoveryPolicy.decideGiveSide(true, Journal.SideStatus.PENDING,
                        PlayerDatReader.MarkerStatus.UNREADABLE, true, true));

        // RETURNING: NONE 인 쪽(원래 take=ABSENT 였던 쪽)은 돌려줄 필요 없음
        assertEquals(RecoveryPolicy.DeliveryDecision.ALREADY_DONE,
                RecoveryPolicy.decideReturnSide(true, Journal.SideStatus.NONE,
                        PlayerDatReader.MarkerStatus.ABSENT, true, true));
        assertEquals(RecoveryPolicy.DeliveryDecision.DELIVER_NOW,
                RecoveryPolicy.decideReturnSide(true, Journal.SideStatus.PENDING,
                        PlayerDatReader.MarkerStatus.ABSENT, true, true));

        assertTrue(RecoveryPolicy.isCommittedComplete(Journal.SideStatus.FOUND, Journal.SideStatus.FOUND));
        assertFalse(RecoveryPolicy.isCommittedComplete(Journal.SideStatus.FOUND, Journal.SideStatus.PENDING));
        assertTrue(RecoveryPolicy.isReturningComplete(Journal.SideStatus.NONE, Journal.SideStatus.FOUND));
        assertFalse(RecoveryPolicy.isReturningComplete(Journal.SideStatus.NONE, Journal.SideStatus.PENDING));
    }

    private static RecoveryPolicy.TakingDecision expectedDecision(
            RecoveryPolicy.TakingSideState a,
            RecoveryPolicy.TakingSideState b,
            boolean hasMoney,
            RecoveryPolicy.BankLookupState bank
    ) {
        if (hasMoney) {
            if (bank == RecoveryPolicy.BankLookupState.FOUND_MATCH) {
                return RecoveryPolicy.TakingDecision.PROMOTE_TO_COMMITTED;
            }
            if (bank == RecoveryPolicy.BankLookupState.FOUND_MISMATCH) {
                return RecoveryPolicy.TakingDecision.SEAL_MISMATCH;
            }
            if (bank == RecoveryPolicy.BankLookupState.ERROR) {
                return RecoveryPolicy.TakingDecision.DEFER;
            }
            if (bank == RecoveryPolicy.BankLookupState.NOT_CHECKED) {
                return RecoveryPolicy.TakingDecision.QUERY_BANK;
            }
        }
        boolean aUndecided = a == RecoveryPolicy.TakingSideState.PENDING_IN_SESSION
                || a == RecoveryPolicy.TakingSideState.UNREADABLE;
        boolean bUndecided = b == RecoveryPolicy.TakingSideState.PENDING_IN_SESSION
                || b == RecoveryPolicy.TakingSideState.UNREADABLE;
        if (aUndecided || bUndecided) {
            return RecoveryPolicy.TakingDecision.DEFER;
        }
        if (a == RecoveryPolicy.TakingSideState.FINAL_FOUND && b == RecoveryPolicy.TakingSideState.FINAL_FOUND) {
            return hasMoney ? RecoveryPolicy.TakingDecision.SETTLE_BANK : RecoveryPolicy.TakingDecision.PROMOTE_TO_COMMITTED;
        }
        return RecoveryPolicy.TakingDecision.TRANSITION_TO_RETURNING;
    }
}
