package kr.commata.bank.core;

/** transactions.type 값. DB 에 이름 그대로 저장하므로 바꾸지 말고 추가만 합니다. */
public enum TxType {
    /** Vault 입금 (직업 보상 등) */
    DEPOSIT,
    /** Vault 출금 (상점 구매 등) */
    WITHDRAW,
    /** /pay 송금. from → to, 한 줄 */
    PAY,
    /** /bank give */
    ADMIN_GIVE,
    /** /bank take */
    ADMIN_TAKE,
    /** /bank set. 증가면 to, 감소면 from 에 대상이 들어가고 amount 는 변화량 */
    ADMIN_SET,
    /** /bank refund. ref = 환불한 출금 거래번호 */
    REFUND;

    public String label() {
        return switch (this) {
            case DEPOSIT -> "입금";
            case WITHDRAW -> "출금";
            case PAY -> "송금";
            case ADMIN_GIVE -> "관리자 지급";
            case ADMIN_TAKE -> "관리자 회수";
            case ADMIN_SET -> "관리자 설정";
            case REFUND -> "환불";
        };
    }
}
