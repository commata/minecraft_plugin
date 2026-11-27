package kr.commata.bank.core;

/**
 * {@link BankStore#settleTrade} 가 실패·거절·보류된 사유 (TRADE.md 4장).
 *
 * <p>{@link #MISMATCH} 와 {@link #UNKNOWN} 은 자동 취소·재시도하지 않고 봉인·재확인 대상으로 다룹니다.
 */
public enum TradeFailure {
    INVALID_AMOUNT("금액이 올바르지 않습니다."),
    INVALID_REQUEST("거래 요청 정보가 올바르지 않습니다."),
    SELF_TRADE("자기 자신과는 거래할 수 없습니다."),
    INSUFFICIENT("잔액이 부족합니다."),
    BALANCE_LIMIT("잔액 한도를 넘습니다."),
    MISMATCH("같은 거래번호에 다른 거래 기록이 있습니다."),
    LOCKED("은행이 잠금 모드입니다. 관리자에게 문의해 주세요."),
    CLOSED("은행이 종료 중입니다."),
    STORAGE_ERROR("저장에 실패해서 처리하지 않았습니다."),
    UNKNOWN("거래 반영 여부를 확인할 수 없습니다.");

    private final String message;

    TradeFailure(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
