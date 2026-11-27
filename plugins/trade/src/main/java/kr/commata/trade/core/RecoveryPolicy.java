package kr.commata.trade.core;

import kr.commata.common.playerdata.PlayerDatReader;

/**
 * 거래 복구 결정표 (TRADE.md 6장, 13-1장, 13-2장).
 *
 * <p>Bukkit 에 의존하지 않는 순수 판정 로직으로, 다음 모든 조합을 결정적으로 판정합니다:
 * <ul>
 *   <li>{@code TAKING} 쪽별 판정: 아이템 유무 × 저널 저장 상태 × {@code connGen} 일치 여부 × 디스크 상태 × 연속 실패 횟수</li>
 *   <li>{@code TAKING} 거래 전체 결정: 쪽 A × 쪽 B × 금액 유무 × Bank 조회 결과(없음/있음 일치/불일치/모름)</li>
 *   <li>{@code COMMITTED} 지급 판정 및 {@code RETURNING} 돌려주기 판정(정산 절대 재시도 금지)</li>
 * </ul>
 */
public final class RecoveryPolicy {

    /** 같은 접속 세션에서 saveData 후 디스크 ABSENT 가 이 횟수 연속이면 플레이어 Trade 잠금 + severe (TRADE.md 6장) */
    public static final int MAX_TAKE_SAVE_RETRIES = 3;

    /** {@code TAKING} 단계에서 한쪽의 디스크 확인 뒤 얻은 상태 */
    public enum TakingSideState {
        /** 디스크에 take 토큰 확인(또는 가져갈 아이템 없음) */
        FINAL_FOUND,
        /** 퇴장/재접속/기동으로 세션이 끊겼고 디스크에 take 토큰 없음 확정 */
        FINAL_ABSENT,
        /** 가져오기를 실행한 같은 접속 세션에서 아직 저장 확인 중 (결정 유예 대상) */
        PENDING_IN_SESSION,
        /** 디스크 .dat 읽기 불가 (결정 유예 대상) */
        UNREADABLE
    }

    /** {@code TAKING} 단계에서 한쪽을 평가한 결과 */
    public record TakingSideEvaluation(
            TakingSideState state,
            int nextSaveRetries,
            boolean lockPlayer,
            boolean updatePersistedToFound,
            boolean updatePersistedToAbsent
    ) {
    }

    /** Bank {@code findTradeByExtRef} 조회 결과 */
    public enum BankLookupState {
        NOT_CHECKED,
        FOUND_MATCH,
        FOUND_MISMATCH,
        NOT_FOUND,
        ERROR
    }

    /** {@code TAKING} 거래 전체 결정 */
    public enum TakingDecision {
        /** 금액 거래: 먼저 Bank {@code findTradeByExtRef} 를 조회해야 함 */
        QUERY_BANK,
        /** 양쪽 take 확정 FOUND → Bank {@code settleTrade} 실행 */
        SETTLE_BANK,
        /** Bank 에 일치하는 정산 기록이 이미 있거나, 0원 거래 양쪽 take FOUND → 저널 {@code COMMITTED} */
        PROMOTE_TO_COMMITTED,
        /** Bank 미정산 확정(또는 0원) + 한쪽 이상 확정 ABSENT + 다른 쪽 PENDING/UNREADABLE 아님 → 저널 {@code RETURNING} */
        TRANSITION_TO_RETURNING,
        /** 어느 쪽이든 아직 PENDING_IN_SESSION 또는 UNREADABLE, 또는 Bank 조회 오류 → 결정 유예(기다림) */
        DEFER,
        /** Bank 기록과 거래 내용 불일치(MISMATCH) → 거래 봉인 + 양쪽 잠금 + severe */
        SEAL_MISMATCH
    }

    /** {@code COMMITTED} 지급 / {@code RETURNING} 돌려주기 한쪽 결정 */
    public enum DeliveryDecision {
        /** 이미 완료(아이템 없음 / 저널 FOUND / NONE / 디스크에 임의 시도 번호 토큰 FOUND) */
        ALREADY_DONE,
        /** 디스크 확정 ABSENT + 온라인 + 인벤토리 전부 들어감 → 지금 지급/반환 시도 */
        DELIVER_NOW,
        /** 디스크 확정 ABSENT + 온라인 + 빈칸 부족 → 대기 (/trade claim 안내) */
        WAIT_INVENTORY_FULL,
        /** 디스크 확정 ABSENT + 오프라인 → 접속 대기 */
        WAIT_OFFLINE,
        /** 디스크 UNREADABLE → 대기 */
        WAIT_UNREADABLE
    }

    private RecoveryPolicy() {
    }

    /**
     * {@code TAKING} 단계에서 한쪽 플레이어의 take 상태를 평가합니다 (TRADE.md 6장).
     *
     * <p>핵심:
     * <ul>
     *   <li>{@code sameConnSession == true}(가져오기를 실행한 그 접속 세션이 계속되는 동안)에는
     *       디스크 {@code ABSENT} 를 확정 ABSENT 로 보지 않고 저장 재시도({@code PENDING_IN_SESSION})로 둡니다.
     *       3회 연속이면 플레이어 잠금({@code lockPlayer = true})을 표시합니다.</li>
     *   <li>{@code sameConnSession == false}(퇴장·재접속·서버 기동)이면 그 플레이어 쪽만
     *       메모리 상태를 버리고 디스크 결과({@code FOUND / ABSENT / UNREADABLE})로 판정합니다.</li>
     * </ul>
     */
    public static TakingSideEvaluation evaluateTakingSide(
            boolean hasItemsToTake,
            Journal.SideStatus persistedTake,
            boolean sameConnSession,
            PlayerDatReader.MarkerStatus diskStatus,
            int consecutiveAbsentRetries
    ) {
        if (!hasItemsToTake || persistedTake == Journal.SideStatus.FOUND) {
            return new TakingSideEvaluation(TakingSideState.FINAL_FOUND, consecutiveAbsentRetries, false,
                    persistedTake != Journal.SideStatus.FOUND, false);
        }
        if (persistedTake == Journal.SideStatus.ABSENT && !sameConnSession) {
            return new TakingSideEvaluation(TakingSideState.FINAL_ABSENT, consecutiveAbsentRetries, false, false, false);
        }
        if (diskStatus == PlayerDatReader.MarkerStatus.FOUND) {
            return new TakingSideEvaluation(TakingSideState.FINAL_FOUND, 0, false, true, false);
        }
        if (diskStatus == PlayerDatReader.MarkerStatus.UNREADABLE) {
            return new TakingSideEvaluation(TakingSideState.UNREADABLE, consecutiveAbsentRetries, false, false, false);
        }
        // diskStatus == ABSENT
        if (sameConnSession) {
            int nextRetries = consecutiveAbsentRetries + 1;
            boolean lock = nextRetries >= MAX_TAKE_SAVE_RETRIES;
            return new TakingSideEvaluation(TakingSideState.PENDING_IN_SESSION, nextRetries, lock, false, false);
        }
        // 퇴장·재접속·기동: 확정 ABSENT
        return new TakingSideEvaluation(TakingSideState.FINAL_ABSENT, consecutiveAbsentRetries, false, false, true);
    }

    /**
     * {@code TAKING} 거래 전체의 다음 조치를 결정합니다 (TRADE.md 6장, 13-2장).
     *
     * <p>한쪽이 확정 {@code FINAL_ABSENT} 여도 다른 쪽이 {@code PENDING_IN_SESSION} 또는 {@code UNREADABLE} 이면
     * <b>결정 유예({@link TakingDecision#DEFER})</b> 합니다 (v4.1 재검증 Critical).
     */
    public static TakingDecision decideTaking(
            TakingSideState sideA,
            TakingSideState sideB,
            boolean hasMoney,
            BankLookupState bankLookup
    ) {
        if (hasMoney) {
            if (bankLookup == BankLookupState.FOUND_MATCH) {
                return TakingDecision.PROMOTE_TO_COMMITTED;
            }
            if (bankLookup == BankLookupState.FOUND_MISMATCH) {
                return TakingDecision.SEAL_MISMATCH;
            }
            if (bankLookup == BankLookupState.ERROR) {
                return TakingDecision.DEFER;
            }
            if (bankLookup == BankLookupState.NOT_CHECKED) {
                // 금액 있는 TAKING 은 Bank 정산 여부를 먼저 확인해야 합니다
                // (settleTrade 직후 저널 COMMITTED 기록 전에 서버가 죽었을 수 있음).
                return TakingDecision.QUERY_BANK;
            }
            // bankLookup == NOT_FOUND
        }

        // 어느 쪽이든 아직 진행 중(PENDING_IN_SESSION)이거나 읽기 불가(UNREADABLE)면 결정 유예
        if (isUndecided(sideA) || isUndecided(sideB)) {
            return TakingDecision.DEFER;
        }

        // 양쪽 모두 최종 확정(FINAL_FOUND 또는 FINAL_ABSENT)
        if (sideA == TakingSideState.FINAL_FOUND && sideB == TakingSideState.FINAL_FOUND) {
            return hasMoney ? TakingDecision.SETTLE_BANK : TakingDecision.PROMOTE_TO_COMMITTED;
        }
        // 한쪽 이상 FINAL_ABSENT 이고 다른 쪽도 최종 확정(FINAL_FOUND 또는 FINAL_ABSENT) → 단방향 RETURNING 취소
        return TakingDecision.TRANSITION_TO_RETURNING;
    }

    /** {@code COMMITTED} 단계에서 한쪽의 물건 지급 결정을 내립니다 (TRADE.md 5·6장). */
    public static DeliveryDecision decideGiveSide(
            boolean hasIncomingItems,
            Journal.SideStatus persistedGive,
            PlayerDatReader.MarkerStatus diskStatus,
            boolean online,
            boolean fitsInInventory
    ) {
        if (!hasIncomingItems || persistedGive == Journal.SideStatus.FOUND) {
            return DeliveryDecision.ALREADY_DONE;
        }
        return decideDiskTransfer(diskStatus, online, fitsInInventory);
    }

    /** {@code RETURNING} 단계에서 한쪽의 물건 돌려주기 결정을 내립니다 (TRADE.md 5·6장, 정산 절대 재시도 없음). */
    public static DeliveryDecision decideReturnSide(
            boolean hasOwnItems,
            Journal.SideStatus persistedReturn,
            PlayerDatReader.MarkerStatus diskStatus,
            boolean online,
            boolean fitsInInventory
    ) {
        if (!hasOwnItems || persistedReturn == Journal.SideStatus.NONE || persistedReturn == Journal.SideStatus.FOUND) {
            return DeliveryDecision.ALREADY_DONE;
        }
        return decideDiskTransfer(diskStatus, online, fitsInInventory);
    }

    /** {@code COMMITTED} 거래가 양쪽 지급 완료로 종료(저널 삭제) 가능한지. */
    public static boolean isCommittedComplete(Journal.SideStatus giveA, Journal.SideStatus giveB) {
        return giveA == Journal.SideStatus.FOUND && giveB == Journal.SideStatus.FOUND;
    }

    /** {@code RETURNING} 거래가 양쪽 반환 완료로 종료(저널 삭제) 가능한지. */
    public static boolean isReturningComplete(Journal.SideStatus returnA, Journal.SideStatus returnB) {
        return isReturnSideDone(returnA) && isReturnSideDone(returnB);
    }

    /** {@code TAKING → RETURNING} 전환 시 각 쪽의 초기 {@code return} 상태를 계산합니다. */
    public static Journal.SideStatus initialReturnStatus(boolean hasOwnItems, TakingSideState finalTakeState) {
        return (hasOwnItems && finalTakeState == TakingSideState.FINAL_FOUND)
                ? Journal.SideStatus.PENDING
                : Journal.SideStatus.NONE;
    }

    private static boolean isUndecided(TakingSideState s) {
        return s == TakingSideState.PENDING_IN_SESSION || s == TakingSideState.UNREADABLE;
    }

    private static boolean isReturnSideDone(Journal.SideStatus s) {
        return s == Journal.SideStatus.NONE || s == Journal.SideStatus.FOUND;
    }

    private static DeliveryDecision decideDiskTransfer(
            PlayerDatReader.MarkerStatus diskStatus,
            boolean online,
            boolean fitsInInventory
    ) {
        if (diskStatus == PlayerDatReader.MarkerStatus.FOUND) {
            return DeliveryDecision.ALREADY_DONE;
        }
        if (diskStatus == PlayerDatReader.MarkerStatus.UNREADABLE) {
            return DeliveryDecision.WAIT_UNREADABLE;
        }
        // ABSENT
        if (!online) {
            return DeliveryDecision.WAIT_OFFLINE;
        }
        return fitsInInventory ? DeliveryDecision.DELIVER_NOW : DeliveryDecision.WAIT_INVENTORY_FULL;
    }
}
