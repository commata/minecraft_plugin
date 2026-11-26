package kr.commata.market.core;

/**
 * EconomyHub 연동 및 설치 블록 기록 규격(v1) 지원 상태 (ECONOMY.md 14.2).
 * Bukkit 에 의존하지 않는 순수 상태 enum 입니다.
 */
public enum HubStatus {
    /** EconomyHub 가 활성화되어 있고 economyhub-block-marks-v1 규격을 지원함. */
    AVAILABLE,
    /** EconomyHub 가 설치되지 않았거나 꺼져 있음. */
    DISABLED,
    /** EconomyHub 가 켜져 있으나 economyhub-block-marks-v1 규격을 지원하지 않음 (구버전). */
    UNSUPPORTED_SPEC;

    public boolean isAvailable() {
        return this == AVAILABLE;
    }
}
