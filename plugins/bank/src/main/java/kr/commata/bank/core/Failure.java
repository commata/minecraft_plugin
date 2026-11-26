package kr.commata.bank.core;

/** 잔액 변경이 실패한 이유. 실패하면 잔액과 기록은 바뀌지 않습니다. */
public enum Failure {
    INVALID_AMOUNT("금액이 올바르지 않습니다."),
    INSUFFICIENT_FUNDS("잔액이 부족합니다."),
    NO_ACCOUNT("계좌가 없습니다. 서버에 한 번이라도 접속한 플레이어만 가능합니다."),
    SELF_TRANSFER("자기 자신에게는 보낼 수 없습니다."),
    LIMIT_EXCEEDED("잔액 한도를 넘습니다."),
    STORAGE_ERROR("저장에 실패해서 처리하지 않았습니다."),
    LOCKED("은행이 잠금 모드입니다. 관리자에게 문의해 주세요."),
    CLOSED("은행이 종료 중입니다."),
    TX_NOT_FOUND("그런 거래번호가 없습니다."),
    NOT_WITHDRAW("출금 거래만 환불할 수 있습니다."),
    NOT_SHOP("EconomyShopGUI 출금만 환불할 수 있습니다."),
    MARKET_ORDER("Market 출금은 /bank refund 가 아니라 /market resolve <거래 ID> refund 로 환불해야 합니다."),
    CLEAN_BOOT("비정상 종료된 기동의 거래가 아닙니다."),
    ALREADY_REFUNDED("이미 환불한 거래입니다.");

    private final String message;

    Failure(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
