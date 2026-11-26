package kr.commata.market.core;

/**
 * 구매품 지급(deliverOrderInTick) 상태 판정 규칙 (ECONOMY.md 13.6).
 *
 * <p>디스크에 이미 해당 주문의 토큰이 존재하면({@code alreadyDeliveredOnDisk}) 아이템을 절대 중복 지급하지 않고
 * DB 갱신만 진행하며, 디스크 미확인/예외 시 회수 성공 여부에 따라 재시도 또는 긴급 격리를 결정합니다.
 */
public final class DeliveryPolicy {

    public enum Action {
        /** 이미 디스크에 지급 기록이 존재함: 아이템 지급 건너뛰고 DB 만 DELIVERED 갱신. */
        ALREADY_DELIVERED,
        /** 이번 지급 후 디스크에 저장 확인됨: 지급 확정, 재지급 플래그 해제, DB DELIVERED 갱신. */
        CONFIRMED,
        /** 디스크 미검출/저장 예외 후 정상 회수됨: attempt 증가 및 재시도 대기. */
        REVERT_AND_RETRY,
        /** 인벤토리 회수 실패: 인벤토리 오염, 긴급 격리 및 자동 재시도 차단. */
        CORRUPTED
    }

    public record Decision(Action action, String reason) {
    }

    private DeliveryPolicy() {}

    /**
     * 지급 시도 전 디스크 사전 확인 결과 판정.
     *
     * @param alreadyOnDisk 지급 전 디스크 확인에서 이미 토큰이 검출되었는지 여부
     * @return 이미 디스크에 있으면 ALREADY_DELIVERED, 아니면 null (지급 계속 진행)
     */
    public static Decision checkPreDelivery(boolean alreadyOnDisk) {
        if (alreadyOnDisk) {
            return new Decision(Action.ALREADY_DELIVERED, "이미 디스크에 지급 표시가 존재함 (중복 지급 방지)");
        }
        return null;
    }

    /**
     * 지급 시도 후 디스크 저장 확인 및 회수 결과 판정.
     *
     * @param markerFound 디스크 확인 결과 FOUND 인지 여부
     * @param saveException saveData 또는 디스크 확인 중 예외 발생 여부
     * @param revertSuccess 미확인/예외 시 인벤토리 및 PDC 회수 성공 여부
     * @param checkReason 디스크 미확인 사유
     * @return 판정 결과
     */
    public static Decision decidePostDelivery(boolean markerFound, boolean saveException, boolean revertSuccess, String checkReason) {
        if (!saveException && markerFound) {
            return new Decision(Action.CONFIRMED, "디스크 저장 확인 완료");
        }
        if (!revertSuccess) {
            return new Decision(Action.CORRUPTED, "아이템 회수 실패로 인한 인벤토리 오염");
        }
        String reason = checkReason != null ? checkReason : (saveException ? "저장 중 예외" : "저장 확인 미검출");
        return new Decision(Action.REVERT_AND_RETRY, reason);
    }
}
