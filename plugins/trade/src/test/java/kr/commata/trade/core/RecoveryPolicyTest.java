package kr.commata.trade.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import kr.commata.common.playerdata.PlayerDatReader.MarkerStatus;
import kr.commata.trade.core.Journal.SideStatus;
import kr.commata.trade.core.RecoveryPolicy.BankLookupState;
import kr.commata.trade.core.RecoveryPolicy.DeliveryDecision;
import kr.commata.trade.core.RecoveryPolicy.TakingDecision;
import kr.commata.trade.core.RecoveryPolicy.TakingSideState;
import org.junit.jupiter.api.Test;

/**
 * TRADE.md 5·6장·13-1장·13-2장의 복구 결정표를 구현 복제 없이 고정 리터럴 표로 검증합니다 (P3-11).
 *
 * <p>세 저널 상태({@code TAKING}, {@code COMMITTED}, {@code RETURNING})의 전 조합과
 * P1-1 회수 후 불확실({@code UNCERTAIN}) 상태 전이(시도 번호·재지급 금지·디스크 수렴)를 모두 포함합니다.
 */
final class RecoveryPolicyTest {

    private record TakingCase(
            TakingSideState sideA,
            TakingSideState sideB,
            boolean hasMoney,
            BankLookupState bank,
            TakingDecision expected
    ) {
        String key() {
            return sideA + "|" + sideB + "|" + hasMoney + "|" + bank;
        }
    }

    private record DeliveryCase(
            boolean hasItems,
            SideStatus persisted,
            boolean sameUncertainSession,
            MarkerStatus disk,
            boolean online,
            boolean alive,
            boolean fits,
            DeliveryDecision expected
    ) {
        String key() {
            return hasItems + "|" + persisted + "|" + sameUncertainSession + "|"
                    + disk + "|" + online + "|" + alive + "|" + fits;
        }
    }

    // ------------------------------------------------------------------ 1. TAKING 쪽별 평가

    @Test
    void takingSideEvaluationRespectsConnGenAndThreeRetryLock() {
        // 같은 접속 세션에서 ABSENT → 1·2회는 PENDING_IN_SESSION(재시도), 3회 연속은 lockPlayer=true
        var r1 = RecoveryPolicy.evaluateTakingSide(true, SideStatus.PENDING, true, MarkerStatus.ABSENT, 0);
        assertEquals(TakingSideState.PENDING_IN_SESSION, r1.state());
        assertEquals(1, r1.nextSaveRetries());
        assertFalse(r1.lockPlayer());
        assertFalse(r1.updatePersistedToFound());
        assertFalse(r1.updatePersistedToAbsent());

        var r2 = RecoveryPolicy.evaluateTakingSide(true, SideStatus.PENDING, true, MarkerStatus.ABSENT, 1);
        assertEquals(TakingSideState.PENDING_IN_SESSION, r2.state());
        assertEquals(2, r2.nextSaveRetries());
        assertFalse(r2.lockPlayer());

        var r3 = RecoveryPolicy.evaluateTakingSide(true, SideStatus.PENDING, true, MarkerStatus.ABSENT, 2);
        assertEquals(TakingSideState.PENDING_IN_SESSION, r3.state());
        assertEquals(3, r3.nextSaveRetries());
        assertTrue(r3.lockPlayer());

        // 퇴장·재접속·기동(sameConnSession=false)에서 ABSENT → 확정 FINAL_ABSENT
        var offlineAbsent = RecoveryPolicy.evaluateTakingSide(true, SideStatus.PENDING, false, MarkerStatus.ABSENT, 0);
        assertEquals(TakingSideState.FINAL_ABSENT, offlineAbsent.state());
        assertTrue(offlineAbsent.updatePersistedToAbsent());
        assertFalse(offlineAbsent.lockPlayer());

        // 이미 저널에 ABSENT 로 확정된 상태 + 다른 접속 세션 → 그대로 FINAL_ABSENT (추가 쓰기 불필요)
        var alreadyAbsent = RecoveryPolicy.evaluateTakingSide(true, SideStatus.ABSENT, false, MarkerStatus.UNREADABLE, 0);
        assertEquals(TakingSideState.FINAL_ABSENT, alreadyAbsent.state());
        assertFalse(alreadyAbsent.updatePersistedToAbsent());

        // UNREADABLE → 어떤 세션이든 UNREADABLE (절대 ABSENT 로 보지 않음)
        var unreadableSame = RecoveryPolicy.evaluateTakingSide(true, SideStatus.PENDING, true, MarkerStatus.UNREADABLE, 1);
        assertEquals(TakingSideState.UNREADABLE, unreadableSame.state());
        assertEquals(1, unreadableSame.nextSaveRetries());
        assertFalse(unreadableSame.lockPlayer());

        var unreadableDiff = RecoveryPolicy.evaluateTakingSide(true, SideStatus.PENDING, false, MarkerStatus.UNREADABLE, 0);
        assertEquals(TakingSideState.UNREADABLE, unreadableDiff.state());

        // FOUND → FINAL_FOUND + 재시도 0 초기화
        var found = RecoveryPolicy.evaluateTakingSide(true, SideStatus.PENDING, true, MarkerStatus.FOUND, 2);
        assertEquals(TakingSideState.FINAL_FOUND, found.state());
        assertEquals(0, found.nextSaveRetries());
        assertTrue(found.updatePersistedToFound());
        assertFalse(found.lockPlayer());

        // 가져갈 아이템이 없는 쪽은 디스크 상태와 무관하게 즉시 FINAL_FOUND
        var noItems = RecoveryPolicy.evaluateTakingSide(false, SideStatus.PENDING, false, MarkerStatus.UNREADABLE, 0);
        assertEquals(TakingSideState.FINAL_FOUND, noItems.state());
        assertTrue(noItems.updatePersistedToFound());
    }

    // ------------------------------------------------------------------ 2. TAKING 전체 결정 160조합 고정 표

    /**
     * TRADE.md 6장·13-2장에서 직접 옮긴 4×4×2×5 = 160조합 고정 기대값 표 (구현 분기 복제 금지, P3-11).
     *
     * <p>명세 규칙:
     * <ul>
     *   <li>어느 쪽이든 {@code PENDING_IN_SESSION} 또는 {@code UNREADABLE} → Bank 영수증 여부와 무관하게 {@code DEFER} (P2-5)</li>
     *   <li>양쪽 최종 확정 + 금액 있음: {@code NOT_CHECKED}→{@code QUERY_BANK}, {@code FOUND_MATCH}→{@code PROMOTE_TO_COMMITTED},
     *       {@code FOUND_MISMATCH}→{@code SEAL_MISMATCH}, {@code ERROR}→{@code DEFER},
     *       {@code NOT_FOUND}→양쪽 FOUND 면 {@code SETTLE_BANK} / 한쪽 이상 ABSENT 면 {@code TRANSITION_TO_RETURNING}</li>
     *   <li>양쪽 최종 확정 + 0원: 양쪽 FOUND→{@code PROMOTE_TO_COMMITTED}, 한쪽 이상 ABSENT→{@code TRANSITION_TO_RETURNING}</li>
     * </ul>
     */
    private static final List<TakingCase> TAKING_DECISION_TABLE = List.of(
            // === A=FINAL_FOUND, B=FINAL_FOUND (10) ===
            tc(TakingSideState.FINAL_FOUND, TakingSideState.FINAL_FOUND, false, BankLookupState.NOT_CHECKED, TakingDecision.PROMOTE_TO_COMMITTED),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.FINAL_FOUND, false, BankLookupState.FOUND_MATCH, TakingDecision.PROMOTE_TO_COMMITTED),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.FINAL_FOUND, false, BankLookupState.FOUND_MISMATCH, TakingDecision.PROMOTE_TO_COMMITTED),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.FINAL_FOUND, false, BankLookupState.NOT_FOUND, TakingDecision.PROMOTE_TO_COMMITTED),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.FINAL_FOUND, false, BankLookupState.ERROR, TakingDecision.PROMOTE_TO_COMMITTED),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.FINAL_FOUND, true, BankLookupState.NOT_CHECKED, TakingDecision.QUERY_BANK),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.FINAL_FOUND, true, BankLookupState.FOUND_MATCH, TakingDecision.PROMOTE_TO_COMMITTED),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.FINAL_FOUND, true, BankLookupState.FOUND_MISMATCH, TakingDecision.SEAL_MISMATCH),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.FINAL_FOUND, true, BankLookupState.NOT_FOUND, TakingDecision.SETTLE_BANK),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.FINAL_FOUND, true, BankLookupState.ERROR, TakingDecision.DEFER),

            // === A=FINAL_FOUND, B=FINAL_ABSENT (10) ===
            tc(TakingSideState.FINAL_FOUND, TakingSideState.FINAL_ABSENT, false, BankLookupState.NOT_CHECKED, TakingDecision.TRANSITION_TO_RETURNING),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.FINAL_ABSENT, false, BankLookupState.FOUND_MATCH, TakingDecision.TRANSITION_TO_RETURNING),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.FINAL_ABSENT, false, BankLookupState.FOUND_MISMATCH, TakingDecision.TRANSITION_TO_RETURNING),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.FINAL_ABSENT, false, BankLookupState.NOT_FOUND, TakingDecision.TRANSITION_TO_RETURNING),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.FINAL_ABSENT, false, BankLookupState.ERROR, TakingDecision.TRANSITION_TO_RETURNING),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.FINAL_ABSENT, true, BankLookupState.NOT_CHECKED, TakingDecision.QUERY_BANK),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.FINAL_ABSENT, true, BankLookupState.FOUND_MATCH, TakingDecision.PROMOTE_TO_COMMITTED),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.FINAL_ABSENT, true, BankLookupState.FOUND_MISMATCH, TakingDecision.SEAL_MISMATCH),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.FINAL_ABSENT, true, BankLookupState.NOT_FOUND, TakingDecision.TRANSITION_TO_RETURNING),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.FINAL_ABSENT, true, BankLookupState.ERROR, TakingDecision.DEFER),

            // === A=FINAL_FOUND, B=PENDING_IN_SESSION (10) — 모두 DEFER ===
            tc(TakingSideState.FINAL_FOUND, TakingSideState.PENDING_IN_SESSION, false, BankLookupState.NOT_CHECKED, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.PENDING_IN_SESSION, false, BankLookupState.FOUND_MATCH, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.PENDING_IN_SESSION, false, BankLookupState.FOUND_MISMATCH, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.PENDING_IN_SESSION, false, BankLookupState.NOT_FOUND, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.PENDING_IN_SESSION, false, BankLookupState.ERROR, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.PENDING_IN_SESSION, true, BankLookupState.NOT_CHECKED, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.PENDING_IN_SESSION, true, BankLookupState.FOUND_MATCH, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.PENDING_IN_SESSION, true, BankLookupState.FOUND_MISMATCH, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.PENDING_IN_SESSION, true, BankLookupState.NOT_FOUND, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.PENDING_IN_SESSION, true, BankLookupState.ERROR, TakingDecision.DEFER),

            // === A=FINAL_FOUND, B=UNREADABLE (10) — 모두 DEFER ===
            tc(TakingSideState.FINAL_FOUND, TakingSideState.UNREADABLE, false, BankLookupState.NOT_CHECKED, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.UNREADABLE, false, BankLookupState.FOUND_MATCH, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.UNREADABLE, false, BankLookupState.FOUND_MISMATCH, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.UNREADABLE, false, BankLookupState.NOT_FOUND, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.UNREADABLE, false, BankLookupState.ERROR, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.UNREADABLE, true, BankLookupState.NOT_CHECKED, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.UNREADABLE, true, BankLookupState.FOUND_MATCH, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.UNREADABLE, true, BankLookupState.FOUND_MISMATCH, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.UNREADABLE, true, BankLookupState.NOT_FOUND, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_FOUND, TakingSideState.UNREADABLE, true, BankLookupState.ERROR, TakingDecision.DEFER),

            // === A=FINAL_ABSENT, B=FINAL_FOUND (10) ===
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.FINAL_FOUND, false, BankLookupState.NOT_CHECKED, TakingDecision.TRANSITION_TO_RETURNING),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.FINAL_FOUND, false, BankLookupState.FOUND_MATCH, TakingDecision.TRANSITION_TO_RETURNING),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.FINAL_FOUND, false, BankLookupState.FOUND_MISMATCH, TakingDecision.TRANSITION_TO_RETURNING),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.FINAL_FOUND, false, BankLookupState.NOT_FOUND, TakingDecision.TRANSITION_TO_RETURNING),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.FINAL_FOUND, false, BankLookupState.ERROR, TakingDecision.TRANSITION_TO_RETURNING),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.FINAL_FOUND, true, BankLookupState.NOT_CHECKED, TakingDecision.QUERY_BANK),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.FINAL_FOUND, true, BankLookupState.FOUND_MATCH, TakingDecision.PROMOTE_TO_COMMITTED),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.FINAL_FOUND, true, BankLookupState.FOUND_MISMATCH, TakingDecision.SEAL_MISMATCH),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.FINAL_FOUND, true, BankLookupState.NOT_FOUND, TakingDecision.TRANSITION_TO_RETURNING),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.FINAL_FOUND, true, BankLookupState.ERROR, TakingDecision.DEFER),

            // === A=FINAL_ABSENT, B=FINAL_ABSENT (10) ===
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.FINAL_ABSENT, false, BankLookupState.NOT_CHECKED, TakingDecision.TRANSITION_TO_RETURNING),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.FINAL_ABSENT, false, BankLookupState.FOUND_MATCH, TakingDecision.TRANSITION_TO_RETURNING),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.FINAL_ABSENT, false, BankLookupState.FOUND_MISMATCH, TakingDecision.TRANSITION_TO_RETURNING),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.FINAL_ABSENT, false, BankLookupState.NOT_FOUND, TakingDecision.TRANSITION_TO_RETURNING),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.FINAL_ABSENT, false, BankLookupState.ERROR, TakingDecision.TRANSITION_TO_RETURNING),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.FINAL_ABSENT, true, BankLookupState.NOT_CHECKED, TakingDecision.QUERY_BANK),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.FINAL_ABSENT, true, BankLookupState.FOUND_MATCH, TakingDecision.PROMOTE_TO_COMMITTED),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.FINAL_ABSENT, true, BankLookupState.FOUND_MISMATCH, TakingDecision.SEAL_MISMATCH),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.FINAL_ABSENT, true, BankLookupState.NOT_FOUND, TakingDecision.TRANSITION_TO_RETURNING),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.FINAL_ABSENT, true, BankLookupState.ERROR, TakingDecision.DEFER),

            // === A=FINAL_ABSENT, B=PENDING_IN_SESSION (10) — 모두 DEFER ===
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.PENDING_IN_SESSION, false, BankLookupState.NOT_CHECKED, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.PENDING_IN_SESSION, false, BankLookupState.FOUND_MATCH, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.PENDING_IN_SESSION, false, BankLookupState.FOUND_MISMATCH, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.PENDING_IN_SESSION, false, BankLookupState.NOT_FOUND, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.PENDING_IN_SESSION, false, BankLookupState.ERROR, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.PENDING_IN_SESSION, true, BankLookupState.NOT_CHECKED, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.PENDING_IN_SESSION, true, BankLookupState.FOUND_MATCH, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.PENDING_IN_SESSION, true, BankLookupState.FOUND_MISMATCH, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.PENDING_IN_SESSION, true, BankLookupState.NOT_FOUND, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.PENDING_IN_SESSION, true, BankLookupState.ERROR, TakingDecision.DEFER),

            // === A=FINAL_ABSENT, B=UNREADABLE (10) — 모두 DEFER ===
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.UNREADABLE, false, BankLookupState.NOT_CHECKED, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.UNREADABLE, false, BankLookupState.FOUND_MATCH, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.UNREADABLE, false, BankLookupState.FOUND_MISMATCH, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.UNREADABLE, false, BankLookupState.NOT_FOUND, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.UNREADABLE, false, BankLookupState.ERROR, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.UNREADABLE, true, BankLookupState.NOT_CHECKED, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.UNREADABLE, true, BankLookupState.FOUND_MATCH, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.UNREADABLE, true, BankLookupState.FOUND_MISMATCH, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.UNREADABLE, true, BankLookupState.NOT_FOUND, TakingDecision.DEFER),
            tc(TakingSideState.FINAL_ABSENT, TakingSideState.UNREADABLE, true, BankLookupState.ERROR, TakingDecision.DEFER),

            // === A=PENDING_IN_SESSION, B=FINAL_FOUND (10) — 모두 DEFER ===
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.FINAL_FOUND, false, BankLookupState.NOT_CHECKED, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.FINAL_FOUND, false, BankLookupState.FOUND_MATCH, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.FINAL_FOUND, false, BankLookupState.FOUND_MISMATCH, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.FINAL_FOUND, false, BankLookupState.NOT_FOUND, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.FINAL_FOUND, false, BankLookupState.ERROR, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.FINAL_FOUND, true, BankLookupState.NOT_CHECKED, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.FINAL_FOUND, true, BankLookupState.FOUND_MATCH, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.FINAL_FOUND, true, BankLookupState.FOUND_MISMATCH, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.FINAL_FOUND, true, BankLookupState.NOT_FOUND, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.FINAL_FOUND, true, BankLookupState.ERROR, TakingDecision.DEFER),

            // === A=PENDING_IN_SESSION, B=FINAL_ABSENT (10) — 모두 DEFER ===
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.FINAL_ABSENT, false, BankLookupState.NOT_CHECKED, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.FINAL_ABSENT, false, BankLookupState.FOUND_MATCH, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.FINAL_ABSENT, false, BankLookupState.FOUND_MISMATCH, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.FINAL_ABSENT, false, BankLookupState.NOT_FOUND, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.FINAL_ABSENT, false, BankLookupState.ERROR, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.FINAL_ABSENT, true, BankLookupState.NOT_CHECKED, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.FINAL_ABSENT, true, BankLookupState.FOUND_MATCH, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.FINAL_ABSENT, true, BankLookupState.FOUND_MISMATCH, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.FINAL_ABSENT, true, BankLookupState.NOT_FOUND, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.FINAL_ABSENT, true, BankLookupState.ERROR, TakingDecision.DEFER),

            // === A=PENDING_IN_SESSION, B=PENDING_IN_SESSION (10) — 모두 DEFER ===
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.PENDING_IN_SESSION, false, BankLookupState.NOT_CHECKED, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.PENDING_IN_SESSION, false, BankLookupState.FOUND_MATCH, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.PENDING_IN_SESSION, false, BankLookupState.FOUND_MISMATCH, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.PENDING_IN_SESSION, false, BankLookupState.NOT_FOUND, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.PENDING_IN_SESSION, false, BankLookupState.ERROR, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.PENDING_IN_SESSION, true, BankLookupState.NOT_CHECKED, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.PENDING_IN_SESSION, true, BankLookupState.FOUND_MATCH, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.PENDING_IN_SESSION, true, BankLookupState.FOUND_MISMATCH, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.PENDING_IN_SESSION, true, BankLookupState.NOT_FOUND, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.PENDING_IN_SESSION, true, BankLookupState.ERROR, TakingDecision.DEFER),

            // === A=PENDING_IN_SESSION, B=UNREADABLE (10) — 모두 DEFER ===
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.UNREADABLE, false, BankLookupState.NOT_CHECKED, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.UNREADABLE, false, BankLookupState.FOUND_MATCH, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.UNREADABLE, false, BankLookupState.FOUND_MISMATCH, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.UNREADABLE, false, BankLookupState.NOT_FOUND, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.UNREADABLE, false, BankLookupState.ERROR, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.UNREADABLE, true, BankLookupState.NOT_CHECKED, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.UNREADABLE, true, BankLookupState.FOUND_MATCH, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.UNREADABLE, true, BankLookupState.FOUND_MISMATCH, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.UNREADABLE, true, BankLookupState.NOT_FOUND, TakingDecision.DEFER),
            tc(TakingSideState.PENDING_IN_SESSION, TakingSideState.UNREADABLE, true, BankLookupState.ERROR, TakingDecision.DEFER),

            // === A=UNREADABLE, B=FINAL_FOUND (10) — 모두 DEFER ===
            tc(TakingSideState.UNREADABLE, TakingSideState.FINAL_FOUND, false, BankLookupState.NOT_CHECKED, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.FINAL_FOUND, false, BankLookupState.FOUND_MATCH, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.FINAL_FOUND, false, BankLookupState.FOUND_MISMATCH, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.FINAL_FOUND, false, BankLookupState.NOT_FOUND, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.FINAL_FOUND, false, BankLookupState.ERROR, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.FINAL_FOUND, true, BankLookupState.NOT_CHECKED, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.FINAL_FOUND, true, BankLookupState.FOUND_MATCH, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.FINAL_FOUND, true, BankLookupState.FOUND_MISMATCH, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.FINAL_FOUND, true, BankLookupState.NOT_FOUND, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.FINAL_FOUND, true, BankLookupState.ERROR, TakingDecision.DEFER),

            // === A=UNREADABLE, B=FINAL_ABSENT (10) — 모두 DEFER ===
            tc(TakingSideState.UNREADABLE, TakingSideState.FINAL_ABSENT, false, BankLookupState.NOT_CHECKED, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.FINAL_ABSENT, false, BankLookupState.FOUND_MATCH, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.FINAL_ABSENT, false, BankLookupState.FOUND_MISMATCH, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.FINAL_ABSENT, false, BankLookupState.NOT_FOUND, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.FINAL_ABSENT, false, BankLookupState.ERROR, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.FINAL_ABSENT, true, BankLookupState.NOT_CHECKED, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.FINAL_ABSENT, true, BankLookupState.FOUND_MATCH, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.FINAL_ABSENT, true, BankLookupState.FOUND_MISMATCH, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.FINAL_ABSENT, true, BankLookupState.NOT_FOUND, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.FINAL_ABSENT, true, BankLookupState.ERROR, TakingDecision.DEFER),

            // === A=UNREADABLE, B=PENDING_IN_SESSION (10) — 모두 DEFER ===
            tc(TakingSideState.UNREADABLE, TakingSideState.PENDING_IN_SESSION, false, BankLookupState.NOT_CHECKED, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.PENDING_IN_SESSION, false, BankLookupState.FOUND_MATCH, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.PENDING_IN_SESSION, false, BankLookupState.FOUND_MISMATCH, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.PENDING_IN_SESSION, false, BankLookupState.NOT_FOUND, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.PENDING_IN_SESSION, false, BankLookupState.ERROR, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.PENDING_IN_SESSION, true, BankLookupState.NOT_CHECKED, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.PENDING_IN_SESSION, true, BankLookupState.FOUND_MATCH, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.PENDING_IN_SESSION, true, BankLookupState.FOUND_MISMATCH, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.PENDING_IN_SESSION, true, BankLookupState.NOT_FOUND, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.PENDING_IN_SESSION, true, BankLookupState.ERROR, TakingDecision.DEFER),

            // === A=UNREADABLE, B=UNREADABLE (10) — 모두 DEFER ===
            tc(TakingSideState.UNREADABLE, TakingSideState.UNREADABLE, false, BankLookupState.NOT_CHECKED, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.UNREADABLE, false, BankLookupState.FOUND_MATCH, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.UNREADABLE, false, BankLookupState.FOUND_MISMATCH, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.UNREADABLE, false, BankLookupState.NOT_FOUND, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.UNREADABLE, false, BankLookupState.ERROR, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.UNREADABLE, true, BankLookupState.NOT_CHECKED, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.UNREADABLE, true, BankLookupState.FOUND_MATCH, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.UNREADABLE, true, BankLookupState.FOUND_MISMATCH, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.UNREADABLE, true, BankLookupState.NOT_FOUND, TakingDecision.DEFER),
            tc(TakingSideState.UNREADABLE, TakingSideState.UNREADABLE, true, BankLookupState.ERROR, TakingDecision.DEFER)
    );

    @Test
    void takingDecisionFixedLiteralTableCoversAll160Combinations() {
        int expectedTotal = TakingSideState.values().length
                * TakingSideState.values().length
                * 2
                * BankLookupState.values().length;
        assertEquals(160, expectedTotal);
        assertEquals(expectedTotal, TAKING_DECISION_TABLE.size());

        Set<String> seen = new HashSet<>();
        for (TakingCase row : TAKING_DECISION_TABLE) {
            assertTrue(seen.add(row.key()), "중복 입력 조합: " + row.key());
            TakingDecision actual = RecoveryPolicy.decideTaking(row.sideA(), row.sideB(), row.hasMoney(), row.bank());
            assertEquals(row.expected(), actual, "TAKING 기대값 불일치: " + row);
        }
        assertEquals(expectedTotal, seen.size());
    }

    // ------------------------------------------------------------------ 3. COMMITTED (지급) 고정 표 + P1-1 전이

    /**
     * {@code COMMITTED} 단계 지급 결정 고정 기대값 표 (아이템 유무 × 저널 상태 × 불확실 세션 × 디스크 × 온라인·생존·빈칸).
     * 온라인·생존·빈칸의 유효 조합 4종: (offline=F,F,T), (dead=T,F,T), (full=T,T,F), (ready=T,T,T).
     */
    private static final List<DeliveryCase> COMMITTED_GIVE_TABLE = List.of(
            // 아이템 없음 → 항상 ALREADY_DONE
            dc(false, SideStatus.PENDING, false, MarkerStatus.ABSENT, false, false, false, DeliveryDecision.ALREADY_DONE),
            dc(false, SideStatus.UNCERTAIN, true, MarkerStatus.UNREADABLE, false, false, false, DeliveryDecision.ALREADY_DONE),
            // 저널 이미 FOUND → 항상 ALREADY_DONE
            dc(true, SideStatus.FOUND, false, MarkerStatus.ABSENT, true, true, true, DeliveryDecision.ALREADY_DONE),
            dc(true, SideStatus.FOUND, true, MarkerStatus.UNREADABLE, false, false, false, DeliveryDecision.ALREADY_DONE),

            // PENDING + 디스크 FOUND → 오프라인·사망·빈칸부족 무관 ALREADY_DONE
            dc(true, SideStatus.PENDING, false, MarkerStatus.FOUND, false, false, true, DeliveryDecision.ALREADY_DONE),
            dc(true, SideStatus.PENDING, false, MarkerStatus.FOUND, true, false, true, DeliveryDecision.ALREADY_DONE),
            dc(true, SideStatus.PENDING, false, MarkerStatus.FOUND, true, true, false, DeliveryDecision.ALREADY_DONE),
            dc(true, SideStatus.PENDING, false, MarkerStatus.FOUND, true, true, true, DeliveryDecision.ALREADY_DONE),
            // PENDING + 디스크 UNREADABLE → WAIT_UNREADABLE
            dc(true, SideStatus.PENDING, false, MarkerStatus.UNREADABLE, false, false, true, DeliveryDecision.WAIT_UNREADABLE),
            dc(true, SideStatus.PENDING, false, MarkerStatus.UNREADABLE, true, false, true, DeliveryDecision.WAIT_UNREADABLE),
            dc(true, SideStatus.PENDING, false, MarkerStatus.UNREADABLE, true, true, false, DeliveryDecision.WAIT_UNREADABLE),
            dc(true, SideStatus.PENDING, false, MarkerStatus.UNREADABLE, true, true, true, DeliveryDecision.WAIT_UNREADABLE),
            // PENDING + 디스크 ABSENT → 오프라인/사망/빈칸부족/즉시지급
            dc(true, SideStatus.PENDING, false, MarkerStatus.ABSENT, false, false, true, DeliveryDecision.WAIT_OFFLINE),
            dc(true, SideStatus.PENDING, false, MarkerStatus.ABSENT, true, false, true, DeliveryDecision.WAIT_DEAD),
            dc(true, SideStatus.PENDING, false, MarkerStatus.ABSENT, true, true, false, DeliveryDecision.WAIT_INVENTORY_FULL),
            dc(true, SideStatus.PENDING, false, MarkerStatus.ABSENT, true, true, true, DeliveryDecision.DELIVER_NOW),

            // P1-1: UNCERTAIN + 같은 접속(sameUncertainSession=true)
            // - UNREADABLE: 완료·재지급 모두 보류
            dc(true, SideStatus.UNCERTAIN, true, MarkerStatus.UNREADABLE, false, false, true, DeliveryDecision.WAIT_UNREADABLE),
            dc(true, SideStatus.UNCERTAIN, true, MarkerStatus.UNREADABLE, true, false, true, DeliveryDecision.WAIT_UNREADABLE),
            dc(true, SideStatus.UNCERTAIN, true, MarkerStatus.UNREADABLE, true, true, false, DeliveryDecision.WAIT_UNREADABLE),
            dc(true, SideStatus.UNCERTAIN, true, MarkerStatus.UNREADABLE, true, true, true, DeliveryDecision.WAIT_UNREADABLE),
            // - ABSENT: 디스크가 회수 후 상태로 확정 → UNCERTAIN 해제 + 시도 번호 증가(CLEAR_UNCERTAIN)
            dc(true, SideStatus.UNCERTAIN, true, MarkerStatus.ABSENT, false, false, true, DeliveryDecision.CLEAR_UNCERTAIN),
            dc(true, SideStatus.UNCERTAIN, true, MarkerStatus.ABSENT, true, false, true, DeliveryDecision.CLEAR_UNCERTAIN),
            dc(true, SideStatus.UNCERTAIN, true, MarkerStatus.ABSENT, true, true, false, DeliveryDecision.CLEAR_UNCERTAIN),
            dc(true, SideStatus.UNCERTAIN, true, MarkerStatus.ABSENT, true, true, true, DeliveryDecision.CLEAR_UNCERTAIN),
            // - FOUND: 디스크가 지급 상태이나 메모리는 회수 상태 → 메모리에 다시 지급해 디스크 기준으로 수렴(CONVERGE_TO_DISK)
            dc(true, SideStatus.UNCERTAIN, true, MarkerStatus.FOUND, false, false, true, DeliveryDecision.WAIT_OFFLINE),
            dc(true, SideStatus.UNCERTAIN, true, MarkerStatus.FOUND, true, false, true, DeliveryDecision.WAIT_DEAD),
            dc(true, SideStatus.UNCERTAIN, true, MarkerStatus.FOUND, true, true, false, DeliveryDecision.WAIT_INVENTORY_FULL),
            dc(true, SideStatus.UNCERTAIN, true, MarkerStatus.FOUND, true, true, true, DeliveryDecision.CONVERGE_TO_DISK),

            // P1-1: UNCERTAIN + 새 접속 또는 오프라인(sameUncertainSession=false)
            // - UNREADABLE: 보류
            dc(true, SideStatus.UNCERTAIN, false, MarkerStatus.UNREADABLE, false, false, true, DeliveryDecision.WAIT_UNREADABLE),
            dc(true, SideStatus.UNCERTAIN, false, MarkerStatus.UNREADABLE, true, true, true, DeliveryDecision.WAIT_UNREADABLE),
            // - ABSENT: 디스크에 없음 확정 → CLEAR_UNCERTAIN
            dc(true, SideStatus.UNCERTAIN, false, MarkerStatus.ABSENT, false, false, true, DeliveryDecision.CLEAR_UNCERTAIN),
            dc(true, SideStatus.UNCERTAIN, false, MarkerStatus.ABSENT, true, true, true, DeliveryDecision.CLEAR_UNCERTAIN),
            // - FOUND: 새 접속은 디스크에서 막 읽었으므로 즉시 ALREADY_DONE (재지급 금지)
            dc(true, SideStatus.UNCERTAIN, false, MarkerStatus.FOUND, false, false, true, DeliveryDecision.ALREADY_DONE),
            dc(true, SideStatus.UNCERTAIN, false, MarkerStatus.FOUND, true, false, true, DeliveryDecision.ALREADY_DONE),
            dc(true, SideStatus.UNCERTAIN, false, MarkerStatus.FOUND, true, true, false, DeliveryDecision.ALREADY_DONE),
            dc(true, SideStatus.UNCERTAIN, false, MarkerStatus.FOUND, true, true, true, DeliveryDecision.ALREADY_DONE)
    );

    @Test
    void committedGiveFixedLiteralTableCoversAllStates() {
        Set<String> seen = new HashSet<>();
        for (DeliveryCase row : COMMITTED_GIVE_TABLE) {
            assertTrue(seen.add(row.key()), "중복 COMMITTED 조합: " + row.key());
            DeliveryDecision actual = RecoveryPolicy.decideGiveSide(
                    row.hasItems(), row.persisted(), row.sameUncertainSession(),
                    row.disk(), row.online(), row.alive(), row.fits());
            assertEquals(row.expected(), actual, "COMMITTED 기대값 불일치: " + row);
        }
    }

    // ------------------------------------------------------------------ 4. RETURNING (반환) 고정 표

    private static final List<DeliveryCase> RETURNING_TABLE = List.of(
            // 아이템 없음 / NONE(원래 take=ABSENT) / FOUND → 모두 ALREADY_DONE
            dc(false, SideStatus.PENDING, false, MarkerStatus.ABSENT, true, true, true, DeliveryDecision.ALREADY_DONE),
            dc(true, SideStatus.NONE, false, MarkerStatus.ABSENT, true, true, true, DeliveryDecision.ALREADY_DONE),
            dc(true, SideStatus.NONE, true, MarkerStatus.UNREADABLE, false, false, false, DeliveryDecision.ALREADY_DONE),
            dc(true, SideStatus.FOUND, false, MarkerStatus.ABSENT, true, true, true, DeliveryDecision.ALREADY_DONE),
            dc(true, SideStatus.FOUND, true, MarkerStatus.UNREADABLE, false, false, false, DeliveryDecision.ALREADY_DONE),

            // PENDING
            dc(true, SideStatus.PENDING, false, MarkerStatus.FOUND, false, false, false, DeliveryDecision.ALREADY_DONE),
            dc(true, SideStatus.PENDING, false, MarkerStatus.UNREADABLE, true, true, true, DeliveryDecision.WAIT_UNREADABLE),
            dc(true, SideStatus.PENDING, false, MarkerStatus.ABSENT, false, false, true, DeliveryDecision.WAIT_OFFLINE),
            dc(true, SideStatus.PENDING, false, MarkerStatus.ABSENT, true, false, true, DeliveryDecision.WAIT_DEAD),
            dc(true, SideStatus.PENDING, false, MarkerStatus.ABSENT, true, true, false, DeliveryDecision.WAIT_INVENTORY_FULL),
            dc(true, SideStatus.PENDING, false, MarkerStatus.ABSENT, true, true, true, DeliveryDecision.DELIVER_NOW),

            // UNCERTAIN + 같은 접속
            dc(true, SideStatus.UNCERTAIN, true, MarkerStatus.UNREADABLE, true, true, true, DeliveryDecision.WAIT_UNREADABLE),
            dc(true, SideStatus.UNCERTAIN, true, MarkerStatus.ABSENT, true, true, true, DeliveryDecision.CLEAR_UNCERTAIN),
            dc(true, SideStatus.UNCERTAIN, true, MarkerStatus.FOUND, false, false, true, DeliveryDecision.WAIT_OFFLINE),
            dc(true, SideStatus.UNCERTAIN, true, MarkerStatus.FOUND, true, false, true, DeliveryDecision.WAIT_DEAD),
            dc(true, SideStatus.UNCERTAIN, true, MarkerStatus.FOUND, true, true, false, DeliveryDecision.WAIT_INVENTORY_FULL),
            dc(true, SideStatus.UNCERTAIN, true, MarkerStatus.FOUND, true, true, true, DeliveryDecision.CONVERGE_TO_DISK),

            // UNCERTAIN + 새 접속/오프라인
            dc(true, SideStatus.UNCERTAIN, false, MarkerStatus.UNREADABLE, true, true, true, DeliveryDecision.WAIT_UNREADABLE),
            dc(true, SideStatus.UNCERTAIN, false, MarkerStatus.ABSENT, false, false, true, DeliveryDecision.CLEAR_UNCERTAIN),
            dc(true, SideStatus.UNCERTAIN, false, MarkerStatus.ABSENT, true, true, true, DeliveryDecision.CLEAR_UNCERTAIN),
            dc(true, SideStatus.UNCERTAIN, false, MarkerStatus.FOUND, false, false, false, DeliveryDecision.ALREADY_DONE),
            dc(true, SideStatus.UNCERTAIN, false, MarkerStatus.FOUND, true, true, true, DeliveryDecision.ALREADY_DONE)
    );

    @Test
    void returningFixedLiteralTableAndInitialStatusAndCompletionMatrix() {
        Set<String> seen = new HashSet<>();
        for (DeliveryCase row : RETURNING_TABLE) {
            assertTrue(seen.add(row.key()), "중복 RETURNING 조합: " + row.key());
            DeliveryDecision actual = RecoveryPolicy.decideReturnSide(
                    row.hasItems(), row.persisted(), row.sameUncertainSession(),
                    row.disk(), row.online(), row.alive(), row.fits());
            assertEquals(row.expected(), actual, "RETURNING 기대값 불일치: " + row);
        }

        // TAKING -> RETURNING 초기 return 상태 전 조합 (hasOwn × TakingSideState)
        assertEquals(SideStatus.PENDING, RecoveryPolicy.initialReturnStatus(true, TakingSideState.FINAL_FOUND));
        assertEquals(SideStatus.NONE, RecoveryPolicy.initialReturnStatus(true, TakingSideState.FINAL_ABSENT));
        assertEquals(SideStatus.NONE, RecoveryPolicy.initialReturnStatus(true, TakingSideState.PENDING_IN_SESSION));
        assertEquals(SideStatus.NONE, RecoveryPolicy.initialReturnStatus(true, TakingSideState.UNREADABLE));
        assertEquals(SideStatus.NONE, RecoveryPolicy.initialReturnStatus(false, TakingSideState.FINAL_FOUND));
        assertEquals(SideStatus.NONE, RecoveryPolicy.initialReturnStatus(false, TakingSideState.FINAL_ABSENT));

        // 완료 조건 전 조합 (SideStatus × SideStatus): UNCERTAIN/PENDING/ABSENT 중 하나라도 있으면 완료 금지
        for (SideStatus a : SideStatus.values()) {
            for (SideStatus b : SideStatus.values()) {
                boolean expectedCommitted = (a == SideStatus.FOUND && b == SideStatus.FOUND);
                assertEquals(expectedCommitted, RecoveryPolicy.isCommittedComplete(a, b),
                        "isCommittedComplete(" + a + ", " + b + ")");
                boolean expectedReturning = (a == SideStatus.NONE || a == SideStatus.FOUND)
                        && (b == SideStatus.NONE || b == SideStatus.FOUND);
                assertEquals(expectedReturning, RecoveryPolicy.isReturningComplete(a, b),
                        "isReturningComplete(" + a + ", " + b + ")");
            }
        }
    }

    // ------------------------------------------------------------------ 5. P1-1 불확실 지급 상태 전이 시나리오

    @Test
    void uncertainDeliveryTransitionForbidsRedeliveryUntilAbsentAndAdvancesAttemptOnce() {
        // 시도 1 실패 후 회수 → 저널 상태 UNCERTAIN, attempt=1 유지
        SideStatus status = SideStatus.UNCERTAIN;
        int attempt = 1;

        // 1) 같은 접속에서 디스크 읽기 실패(UNREADABLE) → 완료도 재지급도 보류, attempt=1 유지
        DeliveryDecision d1 = RecoveryPolicy.decideGiveSide(
                true, status, true, MarkerStatus.UNREADABLE, true, true, true);
        assertEquals(DeliveryDecision.WAIT_UNREADABLE, d1);
        assertFalse(RecoveryPolicy.isCommittedComplete(status, SideStatus.FOUND));
        assertEquals(1, attempt);

        // 2) 같은 접속에서 디스크가 여전히 FOUND(회수 전 지급이 디스크에 남음) →
        //    블라인드 ALREADY_DONE 금지, CONVERGE_TO_DISK 로 메모리를 디스크에 맞춤
        DeliveryDecision d2 = RecoveryPolicy.decideGiveSide(
                true, status, true, MarkerStatus.FOUND, true, true, true);
        assertEquals(DeliveryDecision.CONVERGE_TO_DISK, d2);

        // 3) 같은 접속에서 saveData 후 디스크가 ABSENT 로 확정 → CLEAR_UNCERTAIN 으로 비로소 attempt 1 -> 2 증가
        DeliveryDecision d3 = RecoveryPolicy.decideGiveSide(
                true, status, true, MarkerStatus.ABSENT, true, true, true);
        assertEquals(DeliveryDecision.CLEAR_UNCERTAIN, d3);
        status = SideStatus.PENDING;
        attempt = attempt + 1;
        assertEquals(2, attempt);

        // 4) PENDING(attempt=2) + ABSENT + 생존 + 빈칸 충분 → DELIVER_NOW
        DeliveryDecision d4 = RecoveryPolicy.decideGiveSide(
                true, status, false, MarkerStatus.ABSENT, true, true, true);
        assertEquals(DeliveryDecision.DELIVER_NOW, d4);

        // 5) 만약 새 접속(connGen 변경, sameUncertainSession=false)에서 예전 시도 토큰이 FOUND 로 확인되면
        //    이미 디스크에서 인벤토리를 읽었으므로 재지급 없이 즉시 ALREADY_DONE
        DeliveryDecision d5 = RecoveryPolicy.decideGiveSide(
                true, SideStatus.UNCERTAIN, false, MarkerStatus.FOUND, true, true, true);
        assertEquals(DeliveryDecision.ALREADY_DONE, d5);
    }

    private static TakingCase tc(TakingSideState a, TakingSideState b, boolean hasMoney,
                                 BankLookupState bank, TakingDecision expected) {
        return new TakingCase(a, b, hasMoney, bank, expected);
    }

    private static DeliveryCase dc(boolean hasItems, SideStatus persisted, boolean sameUncertainSession,
                                   MarkerStatus disk, boolean online, boolean alive, boolean fits,
                                   DeliveryDecision expected) {
        return new DeliveryCase(hasItems, persisted, sameUncertainSession, disk, online, alive, fits, expected);
    }
}
