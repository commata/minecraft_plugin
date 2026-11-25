package kr.commata.bank.core;

import java.util.List;
import java.util.UUID;

/**
 * 기동 시작 때 이전 기동을 판정한 결과.
 *
 * @param previousBootId    이전 기동 ID (첫 기동이면 null)
 * @param previousUnclean   이전 기동을 비정상 종료로 판정했는지
 * @param crashEstimateMs   비정상 종료일 때 장애 추정 시각. 정상 종료 기록이 없으면 마지막 heartbeat
 *                          (실제 종료는 그 뒤 heartbeat 간격 안), 플레이어 저장이 확인되지 않으면 Bank 종료 시각
 * @param reason            {@link #NO_CLEAN_SHUTDOWN} / {@link #PLAYER_SAVE_MISSING} / 정상이면 null
 * @param unsavedPlayers    PLAYER_SAVE_MISSING 일 때 Bank 종료 뒤 파일이 저장되지 않은 플레이어
 */
public record BootReport(String previousBootId, boolean previousUnclean, long crashEstimateMs, String reason,
                         List<UUID> unsavedPlayers) {

    /** Bank 가 정상 종료 기록(clean_shutdown)을 남기지 못함 */
    public static final String NO_CLEAN_SHUTDOWN = "no_clean_shutdown";
    /** Bank 는 정상 종료했지만, 그 뒤 서버가 접속자 플레이어 파일을 저장하지 못함 */
    public static final String PLAYER_SAVE_MISSING = "player_save_missing";

    public String describe() {
        if (!previousUnclean) {
            return "정상 종료";
        }
        return PLAYER_SAVE_MISSING.equals(reason)
                ? "Bank 종료 뒤 플레이어 " + unsavedPlayers.size() + "명의 저장이 확인되지 않음"
                : "정상 종료 기록 없음";
    }
}
