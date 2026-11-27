package kr.commata.bank.core;

import java.util.UUID;

/**
 * {@link BankStore#settleTrade} 결과 (TRADE.md 4장).
 *
 * <p>{@code status == Status.OK} 는 DB 커밋이 끝났거나 같은 {@code extRef}·같은 내용의 기존 기록을 확인한 경우뿐입니다.
 * {@link Status#MISMATCH} 와 {@link Status#UNKNOWN} 은 자동 취소·재시도하지 않고 봉인·재확인해야 합니다.
 *
 * @param status         상위 결과 ({@code OK / REJECTED / MISMATCH / UNKNOWN})
 * @param failure        실패 사유 ({@code OK} 이면 {@code null})
 * @param affectedPlayer {@link TradeFailure#INSUFFICIENT}·{@link TradeFailure#BALANCE_LIMIT}·{@link TradeFailure#SELF_TRADE} 대상 UUID (없으면 {@code null})
 * @param txAId          A→B 거래번호 (A 지급액이 0이거나 실패면 0)
 * @param txBId          B→A 거래번호 (B 지급액이 0이거나 실패면 0)
 * @param idempotent     이미 커밋된 같은 {@code extRef} 기록을 그대로 반환했으면 {@code true}
 * @param detail         부가 설명 (없으면 빈 문자열)
 */
public record TradeOutcome(
        Status status,
        TradeFailure failure,
        UUID affectedPlayer,
        long txAId,
        long txBId,
        boolean idempotent,
        String detail
) {

    public enum Status {
        OK, REJECTED, MISMATCH, UNKNOWN
    }

    public boolean ok() {
        return status == Status.OK;
    }

    /** 사용자·관리자에게 보여 줄 실패 메시지. 성공이면 빈 문자열. */
    public String message() {
        if (ok()) {
            return "";
        }
        String base = failure == null ? status.name() : failure.message();
        return detail == null || detail.isEmpty() ? base : base + " (" + detail + ")";
    }

    public static TradeOutcome ok(long txAId, long txBId, boolean idempotent) {
        return new TradeOutcome(Status.OK, null, null, txAId, txBId, idempotent, "");
    }

    public static TradeOutcome rejected(TradeFailure failure, UUID affectedPlayer, String detail) {
        return new TradeOutcome(Status.REJECTED, failure, affectedPlayer, 0L, 0L, false,
                detail == null ? "" : detail);
    }

    public static TradeOutcome mismatch(String detail) {
        return new TradeOutcome(Status.MISMATCH, TradeFailure.MISMATCH, null, 0L, 0L, false,
                detail == null ? "" : detail);
    }

    public static TradeOutcome unknown(String detail) {
        return new TradeOutcome(Status.UNKNOWN, TradeFailure.UNKNOWN, null, 0L, 0L, false,
                detail == null ? "" : detail);
    }
}
