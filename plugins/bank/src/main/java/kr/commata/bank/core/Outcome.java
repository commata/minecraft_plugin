package kr.commata.bank.core;

/**
 * 잔액 변경 결과. {@code ok} 는 DB 커밋이 끝난 뒤에만 true 입니다.
 *
 * @param balance 성공이면 변경 후 잔액(송금은 보낸 사람), 실패면 현재 잔액(모르면 0)
 * @param txId    성공이면 기록된 거래번호, 아니면 0
 * @param detail  실패 이유에 덧붙일 설명 (없으면 빈 문자열)
 */
public record Outcome(boolean ok, Failure failure, long balance, long txId, String detail) {

    static Outcome success(long balance, long txId) {
        return new Outcome(true, null, balance, txId, "");
    }

    static Outcome fail(Failure failure, long balance) {
        return new Outcome(false, failure, balance, 0, "");
    }

    static Outcome fail(Failure failure, long balance, String detail) {
        return new Outcome(false, failure, balance, 0, detail);
    }

    /** 사용자에게 보여 줄 실패 메시지. */
    public String message() {
        if (ok) {
            return "";
        }
        return detail.isEmpty() ? failure.message() : failure.message() + " (" + detail + ")";
    }
}
