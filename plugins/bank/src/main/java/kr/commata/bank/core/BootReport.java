package kr.commata.bank.core;

/**
 * 기동 시작 때 이전 기동을 판정한 결과.
 *
 * @param previousBootId    이전 기동 ID (첫 기동이면 null)
 * @param previousUnclean   이전 기동이 정상 종료(clean_shutdown)를 남기지 않았는지
 * @param crashEstimateMs   비정상 종료일 때 장애 추정 시각 = 이전 기동의 마지막 heartbeat (실제 종료는 그 뒤 heartbeat 간격 안)
 */
public record BootReport(String previousBootId, boolean previousUnclean, long crashEstimateMs) {
}
