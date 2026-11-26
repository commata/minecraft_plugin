package kr.commata.market.core;

import kr.commata.market.core.RefundPolicy.Action;
import kr.commata.market.core.RefundPolicy.BankStatus;
import kr.commata.market.core.RefundPolicy.Decision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RefundPolicy 순수 단위 테스트.
 * 처음 시도(isRetry = false) / 재시도(isRetry = true) × (OK, ALREADY_REFUNDED, REJECTED, UNKNOWN/Bank 불가)
 * 모든 경우에 대해 판정 액션 및 안내 문구를 검증합니다.
 */
class RefundPolicyTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("OK 이면 처음 시도/재시도 모두 COMPLETE 판정")
    void okShouldComplete(boolean isRetry) {
        Decision d = RefundPolicy.decide(isRetry, BankStatus.OK, "환불 성공");
        assertEquals(Action.COMPLETE, d.action());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("ALREADY_REFUNDED 이면 처음 시도/재시도 모두 COMPLETE 판정")
    void alreadyRefundedShouldComplete(boolean isRetry) {
        Decision d = RefundPolicy.decide(isRetry, BankStatus.ALREADY_REFUNDED, "이미 환불됨");
        assertEquals(Action.COMPLETE, d.action());
        assertTrue(d.message().contains("Bank 에 이미 환불 기록이 있습니다"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("Bank 가 정상적으로 환불 없음을 확정하고 REJECTED 응답 시 RESTORE (CHARGED 복원)")
    void rejectedShouldRestore(boolean isRetry) {
        Decision d = RefundPolicy.decide(isRetry, BankStatus.REJECTED, "잔액 한도 초과");
        assertEquals(Action.RESTORE, d.action());
        assertTrue(d.message().contains("다시 지급 대기 CHARGED"));
        assertTrue(d.message().contains("잔액 한도 초과"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("UNKNOWN(Bank 꺼짐·잠김·저장 오류 등)이면 절대 RESTORE 하지 않고 REFUNDING 유지")
    void unknownShouldKeepRefunding(boolean isRetry) {
        Decision d1 = RefundPolicy.decide(isRetry, BankStatus.UNKNOWN, "Bank 플러그인이 꺼져 있습니다.");
        assertEquals(Action.KEEP_REFUNDING, d1.action(), "Bank 꺼짐 시 REFUNDING 유지해야 함");
        assertTrue(d1.message().contains("주문은 REFUNDING 으로 잠가 두었으니"));

        Decision d2 = RefundPolicy.decide(isRetry, BankStatus.UNKNOWN, "Bank 플러그인이 잠금 모드입니다.");
        assertEquals(Action.KEEP_REFUNDING, d2.action(), "Bank 잠김 시 REFUNDING 유지해야 함");

        Decision d3 = RefundPolicy.decide(isRetry, BankStatus.UNKNOWN, "스토리지 오류");
        assertEquals(Action.KEEP_REFUNDING, d3.action(), "Bank 오류 시 REFUNDING 유지해야 함");
    }

    @Test
    @DisplayName("재시도(isRetry=true) 상황에서 Bank 꺼짐/오류 시 절대 CHARGED 로 되돌아가지 않음을 특별 검증 (H1 핵심 시나리오)")
    void retryWithBankUnavailableMustNeverRestore() {
        // 이전 시도에서 Bank 환불이 반영되었으나 finishRefund 직전 중단되어 주문이 REFUNDING 으로 남은 상황.
        // 재시도 시점에 Bank 가 꺼져 있거나 잠겨 있으면:
        Decision decision = RefundPolicy.decide(true, BankStatus.UNKNOWN, "Bank 플러그인이 꺼져 있습니다.");

        // 검증: 절대 RESTORE 여서는 안 됨
        assertEquals(Action.KEEP_REFUNDING, decision.action());
    }
}
