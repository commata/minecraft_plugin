package kr.commata.bank.core;

import java.util.UUID;

/**
 * {@link BankStore#findTradeByExtRef} 조회 결과 (TRADE.md 4장).
 * 조회 오류({@link Status#ERROR})는 거래 없음({@link Status#NOT_FOUND})과 반드시 구분합니다.
 */
public record TradeLookup(Status status, TradeRecord record, String detail) {

    public enum Status {
        FOUND, NOT_FOUND, ERROR
    }

    /**
     * 원장에 커밋된 1:1 교환 정산 기록.
     *
     * @param extRef  외부 거래번호 (예: {@code "trade:<tradeId>"})
     * @param a       당사자 A UUID
     * @param aPays   A가 B에게 지급한 금액 (0 이상)
     * @param b       당사자 B UUID
     * @param bPays   B가 A에게 지급한 금액 (0 이상)
     * @param txAId   A→B {@code transactions.id} (aPays == 0 이면 0)
     * @param txBId   B→A {@code transactions.id} (bPays == 0 이면 0)
     * @param source  호출 플러그인 이름 (예: {@code "Trade"})
     * @param bootId  정산이 기록된 기동 ID
     * @param timeMs  기록 시각 (epoch ms)
     * @param context 거래 상황 문자열 (없으면 {@code null})
     */
    public record TradeRecord(
            String extRef,
            UUID a,
            long aPays,
            UUID b,
            long bPays,
            long txAId,
            long txBId,
            String source,
            String bootId,
            long timeMs,
            String context
    ) {
        /** 요청한 두 당사자·양쪽 금액이 기록과 정확히 일치하는지 확인합니다 (TRADE.md 4장 MISMATCH 판정). */
        public boolean matches(UUID reqA, long reqAPays, UUID reqB, long reqBPays) {
            return a.equals(reqA) && b.equals(reqB) && aPays == reqAPays && bPays == reqBPays;
        }
    }

    public static TradeLookup found(TradeRecord record) {
        return new TradeLookup(Status.FOUND, record, "");
    }

    public static TradeLookup notFound() {
        return new TradeLookup(Status.NOT_FOUND, null, "");
    }

    public static TradeLookup error(String detail) {
        return new TradeLookup(Status.ERROR, null, detail == null ? "" : detail);
    }
}
