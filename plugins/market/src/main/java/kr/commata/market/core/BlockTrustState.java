package kr.commata.market.core;

/**
 * 청크 구역 PDC 에 기록된 설치·피스톤·유체 표시 비트맵을 읽은 판정 결과 (ECONOMY.md 14.2, 15.5).
 */
public enum BlockTrustState {

    /** 비트맵이 없거나 비트가 0인 확정 자연 블록 (판매 가능량 집계 대상). */
    NATURAL,

    /** 비트맵에 표시(1)된 설치·피스톤·유체 블록 (집계 제외). */
    MARKED,

    /** 비트맵이 존재하지만 길이가 64가 아니거나 타입이 다른 등 형식 불명 (fail-closed 로 집계 제외). */
    UNKNOWN;

    /** 판매 가능량 집계가 가능한 자연 블록인지 여부. */
    public boolean isNatural() {
        return this == NATURAL;
    }
}
