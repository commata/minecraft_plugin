package kr.commata.trade.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import kr.commata.common.playerdata.PlayerDatReader.MarkerStatus;

/**
 * 저널 캐시·보호 상태 영속화 잠금·재로딩 병합·지급/반환 장애 경계 코디네이터(순수 로직, Bukkit 비의존).
 *
 * <p>TRADE.md 5·6장의 다음 규칙을 한 곳에 모아 {@code TradeService} 와 단위 테스트가 같은 실행 경로를 공유합니다:
 * <ul>
 *   <li>보호 상태({@code UNCERTAIN}·봉인·3회 저장 실패 잠금) 저널 쓰기 실패 시 메모리 플래그로 Trade 전체 잠금 + severe</li>
 *   <li>미영속 보호 상태가 하나라도 남아 있으면 {@code /trade reload}·재검증·{@code /trade resolve unlock} 거부</li>
 *   <li>재로딩 병합 시 {@link JournalCacheMerge} 로 디스크의 덜 보호적인 상태가 메모리 보호 상태를 덮지 못하게 차단</li>
 *   <li>지급·반환 확인 실패 시 같은 틱 회수(인벤토리·PDC 토큰) → {@code UNCERTAIN} → 같은 접속 수렴 / 새 접속 판정</li>
 * </ul>
 */
public final class DeliveryCoordinator {

    // ------------------------------------------------------------------ 외부 경계 포트

    /** 저널 디스크 저장소 포트({@link Journal.Store} 어댑터 또는 테스트용 가짜 저장소). */
    public interface JournalPort {
        void write(Journal.Entry entry) throws IOException;

        Journal.LoadReport loadAll(long nowMs) throws IOException;

        Journal.UnlockResult unlock(String adminName, long nowMs) throws IOException;

        boolean isStoreLocked();

        String storeLockReason();

        boolean isLoadSucceeded();
    }

    /** 수령자 인벤토리·PDC·{@code saveData}·아이템 역직렬화 포트. */
    public interface RecipientPort {
        boolean isOnline(UUID playerId);

        boolean isAlive(UUID playerId);

        String playerName(UUID playerId, String fallbackName);

        SlotCheck inspectItemsAndSlots(UUID playerId, List<byte[]> itemBytes);

        boolean saveData(UUID playerId);

        Snapshot snapshotInventoryAndPdc(UUID playerId);

        boolean placeAllItems(UUID playerId, Object preparedItems);

        boolean writePdcAndSave(UUID playerId, String newApplied);

        boolean restoreInventoryAndPdc(UUID playerId, Snapshot snapshot);
    }

    /** 플레이어 {@code .dat} 디스크 토큰 판독 포트. */
    public interface DiskTokenReader {
        DiskCheck checkAnyAttemptToken(UUID playerId, UUID tradeId, TradeTokens.Purpose purpose);

        MarkerStatus checkExactAttemptToken(UUID playerId, UUID tradeId, TradeTokens.Purpose purpose, int attempt);
    }

    /** 사용자 알림·로그 출력 포트. */
    public interface Feedback {
        void notifyInventoryFull(UUID playerId, int missingSlots);

        void notifyUncertainPending(UUID playerId);

        void notifyRollbackFailedSealed(UUID playerId, UUID tradeId);

        void logInfo(String message);

        void logWarning(String message);

        void logSevere(String message);
    }

    // ------------------------------------------------------------------ 포트 값 객체

    public record SlotCheck(boolean deserializeOk, String errorMessage, int missingSlots, Object preparedItems) {
        public static SlotCheck ok(int missingSlots, Object preparedItems) {
            return new SlotCheck(true, "", missingSlots, preparedItems);
        }

        public static SlotCheck deserializeFailed(String errorMessage) {
            return new SlotCheck(false, errorMessage == null ? "역직렬화 실패" : errorMessage, 0, null);
        }
    }

    public record Snapshot(Object inventorySnapshot, String prevApplied) {
        public Snapshot {
            prevApplied = prevApplied == null ? "" : prevApplied;
        }
    }

    public record DiskCheck(MarkerStatus status, String foundToken) {
        public DiskCheck {
            Objects.requireNonNull(status, "status");
        }
    }

    public record UnpersistedProtective(UUID tradeId, String context, String errorMessage) {
    }

    public record RetryResult(int succeededCount, int remainingCount, String lastError) {
    }

    public record SingleRetryResult(boolean wasUnpersisted, boolean succeeded, String message) {
    }

    public enum ReloadStatus {
        SKIPPED_ALREADY_OK,
        REFUSED_UNPERSISTED,
        RELOADED_UNLOCKED,
        RELOADED_STILL_LOCKED,
        FAILED_IO
    }

    public record ReloadResult(ReloadStatus status, int openJournalCount, String message) {
    }

    public record StartupResult(boolean ok, Journal.LoadReport report, String errorMessage) {
    }

    public record UnlockOutcome(Journal.UnlockResult result, boolean shouldReconcile) {
    }

    // ------------------------------------------------------------------ 상태

    private final JournalPort journalPort;
    private final RecipientPort recipientPort;
    private final DiskTokenReader diskTokenReader;
    private final Feedback feedback;

    /** 미완료 저널 메모리 캐시: tradeId -> Entry */
    private final Map<UUID, Journal.Entry> openJournals = new HashMap<>();
    /** 디스크에 아직 영속화되지 못한 보호 상태(UNCERTAIN·봉인·3회 저장 실패 잠금): tradeId -> 사유 */
    private final Map<UUID, UnpersistedProtective> unpersistedProtective = new LinkedHashMap<>();
    /** 같은 접속에서 saveData 후 ABSENT 가 3회 연속 발생하거나 회수 실패로 Trade 가 잠긴 플레이어 */
    private final Set<UUID> saveFailedLockedPlayers = new HashSet<>();

    /** 접속 세션 세대 번호 (접속할 때마다 새 값, 퇴장 시 제거 — TRADE.md 6·13-1장) */
    private final AtomicLong connGenSeq = new AtomicLong(0L);
    private final Map<UUID, Long> activeConnGen = new HashMap<>();
    /** 이번 기동에서 지급/반환 회수 후 UNCERTAIN 이 된 시점의 connGen: tradeId -> connGen (P1-1) */
    private final Map<UUID, Long> giveUncertainConnGenA = new HashMap<>();
    private final Map<UUID, Long> giveUncertainConnGenB = new HashMap<>();
    private final Map<UUID, Long> returnUncertainConnGenA = new HashMap<>();
    private final Map<UUID, Long> returnUncertainConnGenB = new HashMap<>();

    public DeliveryCoordinator(
            JournalPort journalPort,
            RecipientPort recipientPort,
            DiskTokenReader diskTokenReader,
            Feedback feedback
    ) {
        this.journalPort = Objects.requireNonNull(journalPort, "journalPort");
        this.recipientPort = Objects.requireNonNull(recipientPort, "recipientPort");
        this.diskTokenReader = Objects.requireNonNull(diskTokenReader, "diskTokenReader");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
    }

    // ------------------------------------------------------------------ 조회·잠금 상태

    public Collection<Journal.Entry> openJournals() {
        return List.copyOf(openJournals.values());
    }

    public List<UUID> openJournalIds() {
        return new ArrayList<>(openJournals.keySet());
    }

    public Journal.Entry openJournal(UUID tradeId) {
        return openJournals.get(tradeId);
    }

    public void putOpenJournal(Journal.Entry entry) {
        openJournals.put(entry.tradeId(), entry);
    }

    public boolean hasOpenJournal(UUID playerId) {
        for (Journal.Entry e : openJournals.values()) {
            if (e.involves(playerId)) {
                return true;
            }
        }
        return false;
    }

    public List<UUID> relatedJournalIds(UUID playerId) {
        List<UUID> ids = new ArrayList<>();
        for (Journal.Entry e : openJournals.values()) {
            if (e.involves(playerId)) {
                ids.add(e.tradeId());
            }
        }
        return ids;
    }

    public Set<UUID> saveFailedLockedPlayers() {
        return Set.copyOf(saveFailedLockedPlayers);
    }

    public boolean isPlayerSaveFailedLocked(UUID playerId) {
        return saveFailedLockedPlayers.contains(playerId);
    }

    public boolean lockPlayerForSaveFailure(UUID playerId) {
        return saveFailedLockedPlayers.add(playerId);
    }

    public void unlockPlayerForSaveFailure(UUID playerId) {
        saveFailedLockedPlayers.remove(playerId);
    }

    /** 보호 상태(UNCERTAIN·봉인·잠금)가 메모리에만 있고 디스크에 영속화되지 못한 항목이 있는지 여부. */
    public boolean hasUnpersistedProtectiveStates() {
        return !unpersistedProtective.isEmpty();
    }

    public Set<UUID> unpersistedProtectiveIds() {
        return Set.copyOf(unpersistedProtective.keySet());
    }

    /** 저널 저장소 잠금 또는 미영속 보호 상태 메모리 잠금 여부 (복구·지급·반환·신규 거래 모두 잠금). */
    public boolean isJournalOrMemoryLocked() {
        return journalPort.isStoreLocked() || !unpersistedProtective.isEmpty();
    }

    public String journalOrMemoryLockReason() {
        if (!unpersistedProtective.isEmpty()) {
            return "미영속 보호 상태(UNCERTAIN/봉인/잠금) " + unpersistedProtective.size()
                    + "건 — 디스크 영속화 성공 전까지 전체 잠금 " + unpersistedProtective.keySet();
        }
        if (journalPort.isStoreLocked()) {
            return journalPort.storeLockReason();
        }
        return null;
    }

    // ------------------------------------------------------------------ 접속 세대(connGen) 관리

    public long onPlayerJoin(UUID playerId) {
        long gen = connGenSeq.incrementAndGet();
        activeConnGen.put(playerId, gen);
        return gen;
    }

    public void ensureOnlineConnGen(UUID playerId) {
        activeConnGen.putIfAbsent(playerId, connGenSeq.incrementAndGet());
    }

    public void onPlayerQuit(UUID playerId) {
        activeConnGen.remove(playerId);
    }

    public Long currentConnGen(UUID playerId) {
        return activeConnGen.get(playerId);
    }

    // ------------------------------------------------------------------ 저널 영속화 및 재시도

    /** 일반 진행 상태 저널을 디스크에 쓰고 성공 시에만 메모리 캐시를 갱신합니다. */
    public boolean persistNormal(Journal.Entry entry) {
        try {
            journalPort.write(entry);
            openJournals.put(entry.tradeId(), entry);
            return true;
        } catch (IOException e) {
            feedback.logSevere("거래 저널 쓰기 실패 (" + entry.tradeId() + "): " + e.getMessage());
            return false;
        }
    }

    /**
     * 보호 상태({@code UNCERTAIN}·봉인·3회 저장 실패 잠금) 저널을 메모리 캐시에 먼저 고정한 뒤 디스크에 기록합니다.
     *
     * <p>디스크 쓰기가 실패하면 {@link #unpersistedProtective} 에 등록해 Trade 전체를 메모리 플래그로 잠그고
     * {@code severe} 를 남깁니다. 영속화 재시도가 성공할 때까지 재로딩·재검증·{@code unlock} 이 거부됩니다.
     */
    public boolean persistProtective(Journal.Entry entry, String context) {
        Objects.requireNonNull(entry, "entry");
        openJournals.put(entry.tradeId(), entry);
        try {
            journalPort.write(entry);
            unpersistedProtective.remove(entry.tradeId());
            return true;
        } catch (IOException e) {
            String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            unpersistedProtective.put(entry.tradeId(), new UnpersistedProtective(entry.tradeId(), context, err));
            feedback.logSevere("거래 " + entry.tradeId() + " 보호 상태(" + context
                    + ") 저널 쓰기 실패 — Trade 전체 잠금 모드로 전환합니다: " + err
                    + ". 영속화 재시도가 성공할 때까지 /trade reload·재검증·unlock 이 거부됩니다.");
            return false;
        }
    }

    /**
     * 미영속 보호 상태 저널들의 디스크 기록을 재시도합니다.
     * 전부 성공하면 미영속 보호 상태 메모리 잠금이 해제됩니다.
     */
    public RetryResult retryUnpersistedProtectiveWrites() {
        if (unpersistedProtective.isEmpty()) {
            return new RetryResult(0, 0, null);
        }
        int succeeded = 0;
        String lastError = null;
        for (UUID tradeId : new ArrayList<>(unpersistedProtective.keySet())) {
            Journal.Entry entry = openJournals.get(tradeId);
            if (entry == null) {
                unpersistedProtective.remove(tradeId);
                continue;
            }
            try {
                journalPort.write(entry);
                unpersistedProtective.remove(tradeId);
                succeeded++;
                feedback.logInfo("미영속 보호 상태 저널 영속화 재시도 성공: " + tradeId);
            } catch (IOException e) {
                lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                UnpersistedProtective prev = unpersistedProtective.get(tradeId);
                String ctx = prev != null ? prev.context() : "보호 상태";
                unpersistedProtective.put(tradeId, new UnpersistedProtective(tradeId, ctx, lastError));
                feedback.logSevere("미영속 보호 상태 저널 영속화 재시도 실패 (" + tradeId + ", " + ctx + "): " + lastError);
            }
        }
        int remaining = unpersistedProtective.size();
        if (succeeded > 0 && remaining == 0) {
            feedback.logInfo("모든 미영속 보호 상태 저널(" + succeeded + "건)이 디스크에 영속화되어 메모리 잠금이 해제되었습니다.");
        }
        return new RetryResult(succeeded, remaining, lastError);
    }

    /** 특정 거래 1건의 미영속 보호 상태 저널 영속화를 재시도합니다({@code /trade resolve <tradeId>}). */
    public SingleRetryResult retrySingleUnpersistedWrite(UUID tradeId) {
        UnpersistedProtective pending = unpersistedProtective.get(tradeId);
        if (pending == null) {
            return new SingleRetryResult(false, true, null);
        }
        Journal.Entry entry = openJournals.get(tradeId);
        if (entry == null) {
            unpersistedProtective.remove(tradeId);
            return new SingleRetryResult(true, true, "거래 " + tradeId + " 의 미영속 기록이 정리되었습니다.");
        }
        try {
            journalPort.write(entry);
            unpersistedProtective.remove(tradeId);
            feedback.logInfo("관리자 재시도로 미영속 보호 상태 저널 영속화 성공: " + tradeId);
            return new SingleRetryResult(true, true, "거래 " + tradeId + " 보호 상태 저널을 디스크에 영속화했습니다.");
        } catch (IOException e) {
            String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            unpersistedProtective.put(tradeId, new UnpersistedProtective(tradeId, pending.context(), err));
            feedback.logSevere("관리자 재시도에서 거래 " + tradeId + " 보호 상태 저널 영속화 실패: " + err);
            return new SingleRetryResult(true, false,
                    "거래 " + tradeId + " 보호 상태(" + pending.context()
                            + ") 저널 영속화 실패로 잠금이 유지됩니다: " + err);
        }
    }

    // ------------------------------------------------------------------ 기동·재로딩·잠금 해제

    /** 기동 시 저널 전체 로드 + 방어적 병합. */
    public StartupResult startupLoad(long nowMs) {
        try {
            Journal.LoadReport report = journalPort.loadAll(nowMs);
            applyMergedReload(report.entries(), "기동 로드 병합");
            return new StartupResult(true, report, null);
        } catch (IOException e) {
            preserveProtectiveMemoryOnly("기동 저널 로드 실패");
            return new StartupResult(false, null, e.getMessage());
        }
    }

    /**
     * {@code /trade reload} 시 저널 재로딩·검증.
     *
     * <p>메모리에 영속화되지 않은 보호 상태가 하나라도 있으면 재로딩을 <b>즉시 거부</b>합니다.
     * 재로딩이 허용된 경우에도 {@link JournalCacheMerge} 로 병합해 디스크의 덜 보호적인 상태가
     * 메모리 보호 상태를 덮지 못하게 합니다.
     */
    public ReloadResult reloadJournalsIfLocked(long nowMs) {
        if (!unpersistedProtective.isEmpty()) {
            String msg = "설정은 다시 불러왔으나 메모리에 영속화되지 않은 보호 상태(UNCERTAIN/봉인/잠금) "
                    + unpersistedProtective.size() + "건" + unpersistedProtective.keySet()
                    + " 이 있어 저널 재로딩을 거부했습니다. 디스크 문제를 해결한 뒤 1분 타이머 또는 "
                    + "/trade resolve <tradeId> 로 영속화에 성공해야 잠금이 해제됩니다.";
            feedback.logWarning("Trade 저널 재로딩 거부: " + msg);
            return new ReloadResult(ReloadStatus.REFUSED_UNPERSISTED, openJournals.size(), msg);
        }
        if (journalPort.isLoadSucceeded() && !journalPort.isStoreLocked()) {
            return new ReloadResult(ReloadStatus.SKIPPED_ALREADY_OK, openJournals.size(), null);
        }
        try {
            Journal.LoadReport report = journalPort.loadAll(nowMs);
            Set<UUID> preserved = applyMergedReload(report.entries(), "재로딩 병합");
            if (!report.newlyQuarantined().isEmpty()) {
                feedback.logSevere("재로딩 중 손상 거래 저널 격리 + LOCKED 생성: " + report.newlyQuarantined());
            }
            if (!preserved.isEmpty()) {
                String msg = "설정은 다시 불러왔으나 디스크 상태가 메모리 보호 상태보다 덜 보호적이어서 "
                        + preserved.size() + "건" + preserved + " 을 보존하고 전체 잠금을 유지합니다.";
                feedback.logSevere(msg);
                return new ReloadResult(ReloadStatus.RELOADED_STILL_LOCKED, openJournals.size(), msg);
            }
            if (report.locked() || journalPort.isStoreLocked()) {
                String reason = journalPort.storeLockReason();
                feedback.logWarning("Trade 저널 재로딩 완료 (여전히 잠금 모드: " + reason + ")");
                return new ReloadResult(ReloadStatus.RELOADED_STILL_LOCKED, openJournals.size(),
                        "설정을 다시 불러왔으나 저널 잠금 상태가 유지됩니다: " + reason);
            }
            feedback.logInfo("Trade 저널 재로딩·검증 완료 (잠금 해제, 미완료 저널 " + openJournals.size() + "건).");
            return new ReloadResult(ReloadStatus.RELOADED_UNLOCKED, openJournals.size(),
                    "Trade 설정 및 저널(" + openJournals.size() + "건)을 다시 불러와 잠금이 해제되었습니다.");
        } catch (IOException e) {
            preserveProtectiveMemoryOnly("재로딩 실패");
            feedback.logSevere("Trade 저널 재로딩 실패 (잠금 유지): " + e.getMessage());
            return new ReloadResult(ReloadStatus.FAILED_IO, openJournals.size(),
                    "설정은 다시 불러왔으나 저널 재로딩 실패로 잠금이 유지됩니다: " + e.getMessage());
        }
    }

    /**
     * 관리자 {@code /trade resolve unlock}.
     *
     * <p>메모리에 영속화되지 않은 보호 상태가 하나라도 있으면 잠금 해제를 <b>즉시 거부</b>합니다.
     */
    public UnlockOutcome unlockByAdmin(String adminName, long nowMs) {
        if (!unpersistedProtective.isEmpty()) {
            String msg = "메모리에 영속화되지 않은 보호 상태(UNCERTAIN/봉인/잠금) "
                    + unpersistedProtective.size() + "건" + unpersistedProtective.keySet()
                    + " 이 있어 잠금 해제를 거부했습니다. 1분 타이머 또는 /trade resolve <tradeId> 로 "
                    + "영속화가 성공한 뒤 다시 시도하세요.";
            feedback.logWarning("Trade resolve unlock 거부: " + msg);
            return new UnlockOutcome(new Journal.UnlockResult(false, 0, msg), false);
        }
        try {
            if (!journalPort.isLoadSucceeded()) {
                Journal.LoadReport pre = journalPort.loadAll(nowMs);
                Set<UUID> preservedPre = applyMergedReload(pre.entries(), "unlock 사전 재로딩");
                if (!preservedPre.isEmpty()) {
                    return new UnlockOutcome(new Journal.UnlockResult(false, 0,
                            "디스크 상태가 메모리 보호 상태보다 덜 보호적이어서 잠금 해제를 거부했습니다: " + preservedPre), false);
                }
                if (!pre.newlyQuarantined().isEmpty()) {
                    feedback.logSevere("unlock 전 재로딩 중 새 손상 저널 격리: " + pre.newlyQuarantined());
                    return new UnlockOutcome(new Journal.UnlockResult(false, 0,
                            "저널 재로딩 중 새 손상 파일이 격리되었습니다("
                                    + String.join(", ", pre.newlyQuarantined())
                                    + "). 확인 후 다시 /trade resolve unlock 을 실행하세요."), false);
                }
            }

            Journal.UnlockResult unlock = journalPort.unlock(adminName, nowMs);
            if (!unlock.unlocked()) {
                return new UnlockOutcome(unlock, false);
            }

            Journal.LoadReport verified = journalPort.loadAll(nowMs);
            Set<UUID> preservedPost = applyMergedReload(verified.entries(), "unlock 사후 재검증");
            if (!preservedPost.isEmpty()) {
                return new UnlockOutcome(new Journal.UnlockResult(false, unlock.resolvedFiles(),
                        "잠금 해제 후 재검증에서 미영속 메모리 보호 상태가 감지되어 잠금을 유지합니다: " + preservedPost), false);
            }
            if (!verified.newlyQuarantined().isEmpty() || verified.locked() || journalPort.isStoreLocked()) {
                return new UnlockOutcome(new Journal.UnlockResult(false, unlock.resolvedFiles(),
                        "잠금 해제 후 재검증에서 여전히 잠금 상태입니다: " + journalPort.storeLockReason()), false);
            }
            return new UnlockOutcome(unlock, !openJournals.isEmpty());
        } catch (IOException e) {
            preserveProtectiveMemoryOnly("unlock 재검증 실패");
            feedback.logSevere("Trade 잠금 해제·재검증 중 I/O 오류 (잠금 유지): " + e.getMessage());
            return new UnlockOutcome(new Journal.UnlockResult(false, 0,
                    "저널 재로딩·검증 실패로 잠금을 해제하지 못했습니다: " + e.getMessage()), false);
        }
    }

    /** 완료·취소·수동 정리로 저널 파일 삭제가 성공한 뒤 호출해 캐시·미영속·잠금 상태를 정리합니다 (P2-9). */
    public void onJournalDeleted(Journal.Entry entry) {
        UUID tradeId = entry.tradeId();
        openJournals.remove(tradeId);
        unpersistedProtective.remove(tradeId);
        giveUncertainConnGenA.remove(tradeId);
        giveUncertainConnGenB.remove(tradeId);
        returnUncertainConnGenA.remove(tradeId);
        returnUncertainConnGenB.remove(tradeId);
        if (!hasOpenJournal(entry.playerA())) {
            saveFailedLockedPlayers.remove(entry.playerA());
        }
        if (!hasOpenJournal(entry.playerB())) {
            saveFailedLockedPlayers.remove(entry.playerB());
        }
    }

    private Set<UUID> applyMergedReload(List<Journal.Entry> diskEntries, String context) {
        JournalCacheMerge.MergeResult merged = JournalCacheMerge.merge(
                openJournals, diskEntries, unpersistedProtective.keySet());
        openJournals.clear();
        openJournals.putAll(merged.merged());
        // 디스크에 이미 보호 상태가 반영된 항목은 미영속 집합에서 제거하고, 보존된 항목은 미영속 잠금 유지
        unpersistedProtective.keySet().retainAll(merged.remainingUnpersistedIds());
        for (UUID id : merged.preservedMemoryIds()) {
            unpersistedProtective.putIfAbsent(id, new UnpersistedProtective(
                    id, context, "디스크 상태가 메모리 보호 상태보다 덜 보호적임"));
        }
        return merged.preservedMemoryIds();
    }

    private void preserveProtectiveMemoryOnly(String context) {
        JournalCacheMerge.MergeResult preserved = JournalCacheMerge.merge(
                openJournals, List.of(), unpersistedProtective.keySet());
        openJournals.clear();
        openJournals.putAll(preserved.merged());
        for (UUID id : preserved.preservedMemoryIds()) {
            unpersistedProtective.putIfAbsent(id, new UnpersistedProtective(
                    id, context, "로드 실패 중 메모리 보호 상태 보존"));
        }
    }

    // ------------------------------------------------------------------ 지급·반환 실행 (P1-1·P1-3·P1-4)

    /**
     * 한쪽의 지급({@code isReturn=false}) 또는 돌려주기({@code isReturn=true})를 같은 틱에서 시도합니다.
     *
     * <p>저널/메모리 잠금 상태에서는 아무것도 실행하지 않고 그대로 반환합니다(fail-closed).
     * 확인 실패 뒤 같은 틱 회수(인벤토리·PDC 토큰 모두) 후 {@link Journal.SideStatus#UNCERTAIN} 기록이
     * 실패하면 {@link #persistProtective(Journal.Entry, String)} 가 Trade 전체를 메모리 잠금으로 전환합니다.
     */
    public Journal.Entry attemptDeliveryForSide(
            Journal.Entry entry,
            TradeSession.Side side,
            boolean isReturn,
            boolean notifyOnline,
            long nowMs
    ) {
        if (isJournalOrMemoryLocked() || entry.isSealed()) {
            return entry;
        }
        UUID tradeId = entry.tradeId();
        UUID targetUuid = entry.uuidOf(side);
        List<byte[]> itemBytes = isReturn ? entry.ownItems(side) : entry.incomingItems(side);
        Journal.SideStatus currentStatus = isReturn ? entry.returnOf(side) : entry.giveOf(side);

        if (itemBytes.isEmpty() || currentStatus == Journal.SideStatus.FOUND
                || (isReturn && currentStatus == Journal.SideStatus.NONE)) {
            if (!isReturn && currentStatus != Journal.SideStatus.FOUND) {
                entry = entry.withGive(side, Journal.SideStatus.FOUND, entry.giveAttemptOf(side), false, nowMs);
                persistNormal(entry);
            }
            return entry;
        }

        boolean isOnline = recipientPort.isOnline(targetUuid);
        boolean isAlive = isOnline && recipientPort.isAlive(targetUuid);
        boolean isUncertain = currentStatus == Journal.SideStatus.UNCERTAIN;
        boolean sameUncertainSession = isUncertain && isSameUncertainSession(tradeId, side, isReturn, targetUuid);

        TradeTokens.Purpose purpose = isReturn ? TradeTokens.Purpose.RETURN : TradeTokens.Purpose.GIVE;
        DiskCheck preCheck;
        if (sameUncertainSession) {
            boolean preSaveOk = recipientPort.saveData(targetUuid);
            preCheck = preSaveOk
                    ? diskTokenReader.checkAnyAttemptToken(targetUuid, tradeId, purpose)
                    : new DiskCheck(MarkerStatus.UNREADABLE, null);
        } else {
            preCheck = diskTokenReader.checkAnyAttemptToken(targetUuid, tradeId, purpose);
        }

        boolean needsItemsCheck = isAlive && (preCheck.status() == MarkerStatus.ABSENT
                || (sameUncertainSession && preCheck.status() == MarkerStatus.FOUND));
        SlotCheck slotCheck = null;
        if (needsItemsCheck) {
            slotCheck = recipientPort.inspectItemsAndSlots(targetUuid, itemBytes);
            if (!slotCheck.deserializeOk()) {
                feedback.logSevere("거래 " + tradeId + " 아이템 역직렬화 실패: " + slotCheck.errorMessage());
                Journal.Entry sealed = entry.withSealed("아이템 역직렬화 실패: " + slotCheck.errorMessage(), nowMs);
                persistProtective(sealed, "아이템 역직렬화 실패 봉인");
                return sealed;
            }
        }

        int missingSlots = slotCheck == null ? 0 : slotCheck.missingSlots();
        RecoveryPolicy.DeliveryDecision decision = isReturn
                ? RecoveryPolicy.decideReturnSide(!itemBytes.isEmpty(), currentStatus, sameUncertainSession,
                        preCheck.status(), isOnline, isAlive, missingSlots == 0)
                : RecoveryPolicy.decideGiveSide(!itemBytes.isEmpty(), currentStatus, sameUncertainSession,
                        preCheck.status(), isOnline, isAlive, missingSlots == 0);

        int attempt = isReturn ? entry.returnAttemptOf(side) : entry.giveAttemptOf(side);

        switch (decision) {
            case ALREADY_DONE -> {
                clearUncertainConnGen(tradeId, side, isReturn);
                entry = isReturn
                        ? entry.withReturn(side, Journal.SideStatus.FOUND, attempt, true, nowMs)
                        : entry.withGive(side, Journal.SideStatus.FOUND, attempt, true, nowMs);
                persistNormal(entry);
                return entry;
            }
            case WAIT_INVENTORY_FULL -> {
                if (notifyOnline && isOnline) {
                    feedback.notifyInventoryFull(targetUuid, missingSlots);
                }
                return entry;
            }
            case WAIT_DEAD, WAIT_OFFLINE, WAIT_UNREADABLE -> {
                return entry;
            }
            case CONVERGE_TO_DISK -> {
                String diskToken = preCheck.foundToken() != null
                        ? preCheck.foundToken()
                        : (isReturn
                                ? TradeTokens.returnToken(tradeId, targetUuid, attempt)
                                : TradeTokens.giveToken(tradeId, targetUuid, attempt));
                return convergeMemoryToDisk(
                        entry, side, isReturn, targetUuid, slotCheck.preparedItems(),
                        attempt, diskToken, notifyOnline, nowMs);
            }
            case CLEAR_UNCERTAIN -> {
                int nextAttempt = attempt + 1;
                Journal.Entry cleared = isReturn
                        ? entry.withReturn(side, Journal.SideStatus.PENDING, nextAttempt, true, nowMs)
                        : entry.withGive(side, Journal.SideStatus.PENDING, nextAttempt, true, nowMs);
                if (!persistNormal(cleared)) {
                    return entry;
                }
                clearUncertainConnGen(tradeId, side, isReturn);
                entry = cleared;
                attempt = nextAttempt;
                RecoveryPolicy.DeliveryDecision nextDecision = isReturn
                        ? RecoveryPolicy.decideReturnSide(!itemBytes.isEmpty(), Journal.SideStatus.PENDING, false,
                                MarkerStatus.ABSENT, isOnline, isAlive, missingSlots == 0)
                        : RecoveryPolicy.decideGiveSide(!itemBytes.isEmpty(), Journal.SideStatus.PENDING, false,
                                MarkerStatus.ABSENT, isOnline, isAlive, missingSlots == 0);
                if (nextDecision == RecoveryPolicy.DeliveryDecision.WAIT_INVENTORY_FULL) {
                    if (notifyOnline && isOnline) {
                        feedback.notifyInventoryFull(targetUuid, missingSlots);
                    }
                    return entry;
                }
                if (nextDecision != RecoveryPolicy.DeliveryDecision.DELIVER_NOW) {
                    return entry;
                }
            }
            case DELIVER_NOW -> {
                // 아래에서 같은 틱 지급 + saveData + 디스크 확인 + 실패 시 같은 틱 회수 실행
            }
        }

        final int deliveryAttempt = attempt;
        String token = isReturn
                ? TradeTokens.returnToken(tradeId, targetUuid, deliveryAttempt)
                : TradeTokens.giveToken(tradeId, targetUuid, deliveryAttempt);

        Snapshot snapshot = recipientPort.snapshotInventoryAndPdc(targetUuid);
        String newApplied = TradeTokens.append(snapshot.prevApplied(), token);

        if (!recipientPort.placeAllItems(targetUuid, slotCheck.preparedItems())) {
            if (notifyOnline) {
                feedback.notifyInventoryFull(targetUuid, Math.max(1, missingSlots));
            }
            return entry;
        }

        boolean saveOk = recipientPort.writePdcAndSave(targetUuid, newApplied);
        MarkerStatus postStatus = saveOk
                ? diskTokenReader.checkExactAttemptToken(targetUuid, tradeId, purpose, deliveryAttempt)
                : MarkerStatus.UNREADABLE;

        if (postStatus == MarkerStatus.FOUND) {
            clearUncertainConnGen(tradeId, side, isReturn);
            entry = isReturn
                    ? entry.withReturn(side, Journal.SideStatus.FOUND, deliveryAttempt, false, nowMs)
                    : entry.withGive(side, Journal.SideStatus.FOUND, deliveryAttempt, false, nowMs);
            persistNormal(entry);
            return entry;
        }

        // TRADE.md 5·6장 (P1-1): FOUND 가 아니면 같은 틱에 회수(인벤토리·PDC 토큰 모두)하고 UNCERTAIN 으로 전환
        if (!recipientPort.restoreInventoryAndPdc(targetUuid, snapshot)) {
            return lockAndSealOnRollbackFailure(entry, side, targetUuid, nowMs);
        }
        entry = isReturn
                ? entry.withReturn(side, Journal.SideStatus.UNCERTAIN, deliveryAttempt, false, nowMs)
                : entry.withGive(side, Journal.SideStatus.UNCERTAIN, deliveryAttempt, false, nowMs);
        recordUncertainConnGen(tradeId, side, isReturn, targetUuid);
        persistProtective(entry, isReturn ? "반환 회수 후 UNCERTAIN" : "지급 회수 후 UNCERTAIN");
        if (notifyOnline) {
            feedback.notifyUncertainPending(targetUuid);
        }
        return entry;
    }

    private Journal.Entry convergeMemoryToDisk(
            Journal.Entry entry,
            TradeSession.Side side,
            boolean isReturn,
            UUID targetUuid,
            Object preparedItems,
            int attempt,
            String diskToken,
            boolean notifyOnline,
            long nowMs
    ) {
        UUID tradeId = entry.tradeId();
        TradeTokens.Purpose purpose = isReturn ? TradeTokens.Purpose.RETURN : TradeTokens.Purpose.GIVE;

        Snapshot snapshot = recipientPort.snapshotInventoryAndPdc(targetUuid);
        String newApplied = TradeTokens.append(snapshot.prevApplied(), diskToken);

        if (!recipientPort.placeAllItems(targetUuid, preparedItems)) {
            if (notifyOnline) {
                feedback.notifyInventoryFull(targetUuid, 1);
            }
            return entry;
        }

        boolean saveOk = recipientPort.writePdcAndSave(targetUuid, newApplied);
        MarkerStatus postStatus = saveOk
                ? diskTokenReader.checkAnyAttemptToken(targetUuid, tradeId, purpose).status()
                : MarkerStatus.UNREADABLE;

        if (postStatus == MarkerStatus.FOUND) {
            clearUncertainConnGen(tradeId, side, isReturn);
            entry = isReturn
                    ? entry.withReturn(side, Journal.SideStatus.FOUND, attempt, true, nowMs)
                    : entry.withGive(side, Journal.SideStatus.FOUND, attempt, true, nowMs);
            persistNormal(entry);
            return entry;
        }

        if (!recipientPort.restoreInventoryAndPdc(targetUuid, snapshot)) {
            return lockAndSealOnRollbackFailure(entry, side, targetUuid, nowMs);
        }
        return entry;
    }

    private Journal.Entry lockAndSealOnRollbackFailure(
            Journal.Entry entry,
            TradeSession.Side side,
            UUID targetUuid,
            long nowMs
    ) {
        UUID tradeId = entry.tradeId();
        String playerName = recipientPort.playerName(targetUuid, entry.nameOf(side));
        saveFailedLockedPlayers.add(targetUuid);
        Journal.Entry sealed = entry.withSealed("지급/반환 같은 틱 회수 실패: " + playerName, nowMs);
        persistProtective(sealed, "지급/반환 회수 실패 봉인");
        feedback.logSevere("거래 " + tradeId + " 지급/반환 같은 틱 회수 중 예외가 발생해 플레이어 "
                + playerName + " (" + targetUuid + ")의 Trade 를 잠그고 거래를 봉인했습니다. "
                + "재시도로 넘어가지 않습니다. 관리자가 인벤토리를 확인한 뒤 /trade resolve "
                + tradeId + " dismiss 로 정리하세요.");
        if (recipientPort.isOnline(targetUuid)) {
            feedback.notifyRollbackFailedSealed(targetUuid, tradeId);
        }
        return sealed;
    }

    private Map<UUID, Long> uncertainConnGenMap(TradeSession.Side side, boolean isReturn) {
        if (isReturn) {
            return side == TradeSession.Side.A ? returnUncertainConnGenA : returnUncertainConnGenB;
        }
        return side == TradeSession.Side.A ? giveUncertainConnGenA : giveUncertainConnGenB;
    }

    private boolean isSameUncertainSession(UUID tradeId, TradeSession.Side side, boolean isReturn, UUID playerId) {
        if (!recipientPort.isOnline(playerId)) {
            return false;
        }
        Long current = activeConnGen.get(playerId);
        Long recorded = uncertainConnGenMap(side, isReturn).get(tradeId);
        return current != null && current.equals(recorded);
    }

    private void recordUncertainConnGen(UUID tradeId, TradeSession.Side side, boolean isReturn, UUID playerId) {
        long gen = activeConnGen.computeIfAbsent(playerId, k -> connGenSeq.incrementAndGet());
        uncertainConnGenMap(side, isReturn).put(tradeId, gen);
    }

    private void clearUncertainConnGen(UUID tradeId, TradeSession.Side side, boolean isReturn) {
        uncertainConnGenMap(side, isReturn).remove(tradeId);
    }
}
