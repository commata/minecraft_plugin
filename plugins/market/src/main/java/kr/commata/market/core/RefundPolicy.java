package kr.commata.market.core;

/**
 * 관리자 환불(/market resolve &lt;tx_id&gt; refund) 결과 판정 규칙 (ECONOMY.md 13.6).
 *
 * <p>이전 시도 중단 등으로 이미 {@code REFUNDING} 상태였던 재시도 주문은, Bank 가 환불 기록이 없음을
 * 확정적으로 답한 경우({@code REJECTED})가 아니면 절대 {@code CHARGED}로 되돌리지 않고 {@code REFUNDING}을 유지합니다.
 */
public final class RefundPolicy {

    public enum BankStatus {
        /** Bank 에서 환불이 새로 정상 반영됨. */
        OK,
        /** Bank 에 이미 동일한 ext_ref 환불 기록이 존재함 (이전 시도 반영 성공). */
        ALREADY_REFUNDED,
        /** Bank 가 환불 불가/거부를 확정적으로 판정함 (Bank 정상 작동, 출금 기록 없음·출금 아님·잔액 한도 초과 등). */
        REJECTED,
        /** Bank 꺼짐, 잠김, 스토리지 오류 등으로 Bank 가 환불 여부를 판단하지 못함. */
        UNKNOWN
    }

    public enum Action {
        /** 환불 완료 처리 (finishRefund, 한도·천장 복구). */
        COMPLETE,
        /** 환불 취소 및 주문 복원 (cancelRefund, CHARGED 로 복원). */
        RESTORE,
        /** 환불 보류 유지 (REFUNDING 유지, 관리자 재시도/확인 안내). */
        KEEP_REFUNDING
    }

    public record Decision(Action action, String message) {
    }

    private RefundPolicy() {}

    /**
     * Bank 환불 결과와 시도 상태(처음 시도 / 재시도)를 바탕으로 주문 처리 결정을 내립니다.
     *
     * @param isRetry 이번 명령 호출 전에 이미 REFUNDING 상태였던 주문인지 여부
     * @param bankStatus Bank 가 반환한 환불 상태
     * @param bankMessage Bank 오류/거부 메시지
     * @return 결정(액션 및 사용자 안내 메시지)
     */
    public static Decision decide(boolean isRetry, BankStatus bankStatus, String bankMessage) {
        String msg = bankMessage == null ? "" : bankMessage;
        return switch (bankStatus) {
            case OK -> new Decision(Action.COMPLETE, "환불이 정상 완료되었습니다.");
            case ALREADY_REFUNDED -> new Decision(Action.COMPLETE, "Bank 에 이미 환불 기록이 있습니다.");
            case REJECTED -> new Decision(Action.RESTORE,
                    "Bank 환불 거부: " + msg + " (주문은 다시 지급 대기 CHARGED)");
            case UNKNOWN -> new Decision(Action.KEEP_REFUNDING,
                    "Bank 환불 결과를 확인하지 못했습니다: " + msg
                            + ". 주문은 REFUNDING 으로 잠가 두었으니 같은 명령을 다시 실행해 주세요.");
        };
    }
}
