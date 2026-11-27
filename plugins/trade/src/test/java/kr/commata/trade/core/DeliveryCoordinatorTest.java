package kr.commata.trade.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.commata.common.playerdata.PlayerDatReader.MarkerStatus;
import kr.commata.trade.core.DeliveryCoordinator.DiskCheck;
import kr.commata.trade.core.DeliveryCoordinator.ReloadStatus;
import kr.commata.trade.core.DeliveryCoordinator.Snapshot;
import kr.commata.trade.core.DeliveryCoordinator.SlotCheck;
import kr.commata.trade.core.Journal.SideStatus;
import kr.commata.trade.core.Journal.Stage;
import kr.commata.trade.core.TradeSession.Side;
import org.junit.jupiter.api.Test;

/**
 * {@link DeliveryCoordinator} 의 서비스 수준 장애 경계 단위 테스트 (MockBukkit 없이 가짜 포트 주입, P3-11 보강).
 *
 * <p>지급/반환 시도 → 디스크 {@code ABSENT}/{@code UNREADABLE} → 같은 틱 회수(인벤토리·PDC 토큰 모두) →
 * {@code UNCERTAIN} 또는 회수 실패 봉인 저널 쓰기 실패 → Trade 전체 잠금 및 {@code /trade reload}·
 * {@code /trade resolve unlock} 거부 → 영속화 재시도 성공 → 같은 접속 수렴·새 접속 판정 전 과정을 검증합니다.
 */
final class DeliveryCoordinatorTest {

    private static final UUID PLAYER_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID PLAYER_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final String EXISTING_PDC_TOKEN = "00000000-0000-0000-0000-000000000001:take:old";

    private static Journal.Entry committedEntry(UUID tradeId) {
        return new Journal.Entry(
                tradeId, 1000L, 1000L, Stage.COMMITTED,
                PLAYER_A, "Alice", PLAYER_B, "Bob",
                0L, 0L,
                List.of(new byte[]{10, 20}), List.of(new byte[]{30, 40}),
                List.of("다이아몬드 x1"), List.of("네더라이트 주괴 x1"),
                SideStatus.FOUND, SideStatus.FOUND,
                SideStatus.PENDING, SideStatus.FOUND,
                SideStatus.NONE, SideStatus.NONE,
                1, 1, 1, 1,
                0, 0,
                0L, 0L,
                "", false, ""
        );
    }

    private static Journal.Entry returningEntry(UUID tradeId) {
        return new Journal.Entry(
                tradeId, 1000L, 1000L, Stage.RETURNING,
                PLAYER_A, "Alice", PLAYER_B, "Bob",
                0L, 0L,
                List.of(new byte[]{10, 20}), List.of(),
                List.of("다이아몬드 x1"), List.of(),
                SideStatus.FOUND, SideStatus.ABSENT,
                SideStatus.FOUND, SideStatus.PENDING,
                SideStatus.PENDING, SideStatus.NONE,
                1, 1, 1, 1,
                0, 0,
                0L, 0L,
                "가져오기 미반영으로 거래 취소", false, ""
        );
    }

    /**
     * 핵심 시나리오 1 (재검증 P1 · P3-11):
     * 지급 시도 → 디스크 ABSENT → 인벤토리·PDC 토큰 모두 회수 → UNCERTAIN 저널 쓰기 실패 →
     * Trade 전체 잠금 + reload/unlock 거부 → 영속화 재시도 성공 →
     * 같은 접속에서 디스크 FOUND 감지 시 메모리에 재지급·저장해 디스크 기준으로 수렴.
     */
    @Test
    void giveAbsentRollbackUncertainWriteFailureLocksReloadThenConvergesInSameSession() {
        UUID tradeId = UUID.randomUUID();
        Journal.Entry initial = committedEntry(tradeId);
        FakeJournalPort journalPort = new FakeJournalPort(initial);
        FakeRecipientPort recipient = new FakeRecipientPort();
        recipient.setPlayer(PLAYER_A, "Alice", true, true, List.of("기존 검"), EXISTING_PDC_TOKEN);
        FakeDiskTokenReader disk = new FakeDiskTokenReader();
        RecordingFeedback feedback = new RecordingFeedback();

        DeliveryCoordinator coord = new DeliveryCoordinator(journalPort, recipient, disk, feedback);
        coord.onPlayerJoin(PLAYER_A);
        coord.putOpenJournal(initial);

        // 1) 지급 시도: 사전 디스크 ABSENT → 인벤토리·PDC 반영 + saveData → 사후 확인 ABSENT → 회수 → UNCERTAIN 쓰기 실패!
        disk.enqueueAnyCheck(new DiskCheck(MarkerStatus.ABSENT, null));
        disk.enqueueExactCheck(MarkerStatus.ABSENT);
        journalPort.failNextWrites = 1;

        Journal.Entry afterFirst = coord.attemptDeliveryForSide(initial, Side.A, false, true, 2000L);

        // 인벤토리와 PDC 토큰이 모두 지급 전 상태로 정확히 회수됐는지 검증
        assertEquals(List.of("기존 검"), recipient.inventoryOf(PLAYER_A));
        assertEquals(EXISTING_PDC_TOKEN, recipient.pdcOf(PLAYER_A));
        // 메모리 캐시는 UNCERTAIN(시도 1), 디스크는 여전히 예전 PENDING, Trade 는 전체 잠금
        assertEquals(SideStatus.UNCERTAIN, afterFirst.giveA());
        assertEquals(1, afterFirst.giveAttemptA());
        assertEquals(SideStatus.UNCERTAIN, coord.openJournal(tradeId).giveA());
        assertEquals(SideStatus.PENDING, journalPort.diskEntries.get(tradeId).giveA());
        assertTrue(coord.hasUnpersistedProtectiveStates());
        assertTrue(coord.isJournalOrMemoryLocked());
        assertEquals(Set.of(tradeId), coord.unpersistedProtectiveIds());
        assertFalse(feedback.severes.isEmpty());

        // 2) 미영속 UNCERTAIN 상태에서 /trade reload 와 /trade resolve unlock 모두 거부 (디스크 PENDING 덮어쓰기 차단!)
        journalPort.storeLocked = true;
        var reloadRefused = coord.reloadJournalsIfLocked(2100L);
        assertEquals(ReloadStatus.REFUSED_UNPERSISTED, reloadRefused.status());
        assertEquals(SideStatus.UNCERTAIN, coord.openJournal(tradeId).giveA());

        var unlockRefused = coord.unlockByAdmin("Admin", 2200L);
        assertFalse(unlockRefused.result().unlocked());
        assertEquals(SideStatus.UNCERTAIN, coord.openJournal(tradeId).giveA());

        // 잠금 중에는 지급 재시도도 실행되지 않음 (fail-closed)
        Journal.Entry blockedDelivery = coord.attemptDeliveryForSide(afterFirst, Side.A, false, true, 2300L);
        assertEquals(SideStatus.UNCERTAIN, blockedDelivery.giveA());
        assertEquals(List.of("기존 검"), recipient.inventoryOf(PLAYER_A));

        // 3) 디스크 복구 → 영속화 재시도 성공 → UNCERTAIN 이 디스크에 기록되고 메모리 잠금 해제
        journalPort.storeLocked = false;
        var retry = coord.retryUnpersistedProtectiveWrites();
        assertEquals(1, retry.succeededCount());
        assertEquals(0, retry.remainingCount());
        assertFalse(coord.hasUnpersistedProtectiveStates());
        assertFalse(coord.isJournalOrMemoryLocked());
        assertEquals(SideStatus.UNCERTAIN, journalPort.diskEntries.get(tradeId).giveA());

        // 4) 같은 접속(connGen 동일)에서 재확인: 디스크에 예전 시도 1 토큰이 FOUND(회수 전 저장이 디스크에 남았던 상황)
        //    → CONVERGE_TO_DISK 경로로 회수된 메모리에 아이템·토큰을 다시 반영 + saveData → 사후 FOUND → 완료!
        String attempt1Token = TradeTokens.giveToken(tradeId, PLAYER_A, 1);
        disk.enqueueAnyCheck(new DiskCheck(MarkerStatus.FOUND, attempt1Token)); // preCheck
        disk.enqueueAnyCheck(new DiskCheck(MarkerStatus.FOUND, attempt1Token)); // converge postCheck

        Journal.Entry converged = coord.attemptDeliveryForSide(
                coord.openJournal(tradeId), Side.A, false, true, 3000L);

        assertEquals(SideStatus.FOUND, converged.giveA());
        assertEquals(1, converged.giveAttemptA());
        assertEquals(List.of("기존 검", "네더라이트 주괴 x1"), recipient.inventoryOf(PLAYER_A));
        assertTrue(TradeTokens.containsGiveExact(recipient.pdcOf(PLAYER_A), tradeId, PLAYER_A, 1));
        assertEquals(SideStatus.FOUND, journalPort.diskEntries.get(tradeId).giveA());
    }

    /**
     * 핵심 시나리오 2 (P1-1 · P3-11):
     * 지급 시도 → 디스크 UNREADABLE → 인벤토리·PDC 토큰 모두 회수 → UNCERTAIN 쓰기 실패 →
     * 재로딩 거부 → 영속화 재시도 성공 → 새 접속(connGen 변경)에서 디스크 FOUND →
     * 이미 디스크에서 막 읽은 상태이므로 재지급 없이 즉시 완료(ALREADY_DONE, 중복 지급 없음).
     */
    @Test
    void giveUnreadableRollbackUncertainWriteFailureThenNewSessionFoundCompletesWithoutRedelivery() {
        UUID tradeId = UUID.randomUUID();
        Journal.Entry initial = committedEntry(tradeId);
        FakeJournalPort journalPort = new FakeJournalPort(initial);
        FakeRecipientPort recipient = new FakeRecipientPort();
        recipient.setPlayer(PLAYER_A, "Alice", true, true, List.of("기존 검"), EXISTING_PDC_TOKEN);
        FakeDiskTokenReader disk = new FakeDiskTokenReader();
        RecordingFeedback feedback = new RecordingFeedback();

        DeliveryCoordinator coord = new DeliveryCoordinator(journalPort, recipient, disk, feedback);
        coord.onPlayerJoin(PLAYER_A);
        coord.putOpenJournal(initial);

        // 1) 지급 시도 → 사후 확인 UNREADABLE → 회수 → UNCERTAIN 쓰기 실패
        disk.enqueueAnyCheck(new DiskCheck(MarkerStatus.ABSENT, null));
        disk.enqueueExactCheck(MarkerStatus.UNREADABLE);
        journalPort.failNextWrites = 1;

        coord.attemptDeliveryForSide(initial, Side.A, false, true, 2000L);

        assertEquals(List.of("기존 검"), recipient.inventoryOf(PLAYER_A));
        assertEquals(EXISTING_PDC_TOKEN, recipient.pdcOf(PLAYER_A));
        assertTrue(coord.hasUnpersistedProtectiveStates());
        assertEquals(ReloadStatus.REFUSED_UNPERSISTED, coord.reloadJournalsIfLocked(2100L).status());

        // 2) 영속화 재시도 성공
        var retry = coord.retryUnpersistedProtectiveWrites();
        assertEquals(1, retry.succeededCount());
        assertFalse(coord.isJournalOrMemoryLocked());

        // 3) 퇴장 후 새 접속(connGen 변경): 디스크에서 막 읽은 상태(아이템·토큰이 이미 존재)라 가정
        coord.onPlayerQuit(PLAYER_A);
        coord.onPlayerJoin(PLAYER_A);
        String attempt1Token = TradeTokens.giveToken(tradeId, PLAYER_A, 1);
        recipient.setPlayer(PLAYER_A, "Alice", true, true,
                List.of("기존 검", "네더라이트 주괴 x1"),
                TradeTokens.append(EXISTING_PDC_TOKEN, attempt1Token));
        int placeCallsBefore = recipient.placeAllItemsCalls;
        int saveCallsBefore = recipient.saveDataCalls;

        disk.enqueueAnyCheck(new DiskCheck(MarkerStatus.FOUND, attempt1Token));

        Journal.Entry afterNewSession = coord.attemptDeliveryForSide(
                coord.openJournal(tradeId), Side.A, false, true, 3000L);

        assertEquals(SideStatus.FOUND, afterNewSession.giveA());
        assertEquals(placeCallsBefore, recipient.placeAllItemsCalls, "새 접속 FOUND 는 재지급하면 안 됨");
        assertEquals(saveCallsBefore, recipient.saveDataCalls, "새 접속은 사전 saveData 없이 디스크로 바로 판정");
        assertEquals(List.of("기존 검", "네더라이트 주괴 x1"), recipient.inventoryOf(PLAYER_A));
    }

    /**
     * 핵심 시나리오 3 (P1-1 · P3-11):
     * UNCERTAIN 영속화 후 같은 접속에서 saveData → 디스크 ABSENT 확정 시
     * CLEAR_UNCERTAIN 으로 시도 번호를 1 → 2 로 먼저 저널에 기록한 뒤 시도 2 토큰으로만 재지급.
     */
    @Test
    void uncertainConfirmedAbsentAdvancesAttemptBeforeRedelivery() {
        UUID tradeId = UUID.randomUUID();
        Journal.Entry initial = committedEntry(tradeId);
        FakeJournalPort journalPort = new FakeJournalPort(initial);
        FakeRecipientPort recipient = new FakeRecipientPort();
        recipient.setPlayer(PLAYER_A, "Alice", true, true, List.of("기존 검"), EXISTING_PDC_TOKEN);
        FakeDiskTokenReader disk = new FakeDiskTokenReader();
        RecordingFeedback feedback = new RecordingFeedback();

        DeliveryCoordinator coord = new DeliveryCoordinator(journalPort, recipient, disk, feedback);
        coord.onPlayerJoin(PLAYER_A);
        coord.putOpenJournal(initial);

        // 1차 시도: ABSENT → 회수 → UNCERTAIN(attempt=1) 기록 성공
        disk.enqueueAnyCheck(new DiskCheck(MarkerStatus.ABSENT, null));
        disk.enqueueExactCheck(MarkerStatus.ABSENT);
        Journal.Entry uncertain = coord.attemptDeliveryForSide(initial, Side.A, false, true, 2000L);
        assertEquals(SideStatus.UNCERTAIN, uncertain.giveA());
        assertEquals(1, uncertain.giveAttemptA());
        assertEquals(List.of("기존 검"), recipient.inventoryOf(PLAYER_A));
        assertEquals(EXISTING_PDC_TOKEN, recipient.pdcOf(PLAYER_A));

        // 2차 재확인(같은 접속): saveData → 디스크 ABSENT 확정 → PENDING(attempt=2) 선기록 → attempt=2 지급 + FOUND!
        disk.enqueueAnyCheck(new DiskCheck(MarkerStatus.ABSENT, null));
        disk.enqueueExactCheck(MarkerStatus.FOUND);

        Journal.Entry done = coord.attemptDeliveryForSide(uncertain, Side.A, false, true, 3000L);

        assertEquals(SideStatus.FOUND, done.giveA());
        assertEquals(2, done.giveAttemptA());
        assertEquals(List.of("기존 검", "네더라이트 주괴 x1"), recipient.inventoryOf(PLAYER_A));
        assertFalse(TradeTokens.containsGiveExact(recipient.pdcOf(PLAYER_A), tradeId, PLAYER_A, 1),
                "회수된 시도 1 토큰을 재사용하면 안 됨");
        assertTrue(TradeTokens.containsGiveExact(recipient.pdcOf(PLAYER_A), tradeId, PLAYER_A, 2),
                "새 시도 2 토큰이 기록되어야 함");
        // 디스크 쓰기 기록 순서: UNCERTAIN(1) → PENDING(2) → FOUND(2)
        assertEquals(3, journalPort.writeHistory.size());
        assertEquals(SideStatus.UNCERTAIN, journalPort.writeHistory.get(0).giveA());
        assertEquals(1, journalPort.writeHistory.get(0).giveAttemptA());
        assertEquals(SideStatus.PENDING, journalPort.writeHistory.get(1).giveA());
        assertEquals(2, journalPort.writeHistory.get(1).giveAttemptA());
        assertEquals(SideStatus.FOUND, journalPort.writeHistory.get(2).giveA());
        assertEquals(2, journalPort.writeHistory.get(2).giveAttemptA());
    }

    /**
     * 핵심 시나리오 4 (재검증 P1 · P1-1):
     * 회수 중 예외 → 플레이어 잠금 + 거래 봉인 → 봉인 저널 쓰기 실패 →
     * Trade 전체 잠금 + 재로딩/unlock 거부 + 재시도 진행 차단 → 영속화 성공 뒤에도 봉인 유지.
     */
    @Test
    void rollbackExceptionSealsAndLocksGloballyWhenSealWriteFails() {
        UUID tradeId = UUID.randomUUID();
        Journal.Entry initial = committedEntry(tradeId);
        FakeJournalPort journalPort = new FakeJournalPort(initial);
        FakeRecipientPort recipient = new FakeRecipientPort();
        recipient.setPlayer(PLAYER_A, "Alice", true, true, List.of("기존 검"), EXISTING_PDC_TOKEN);
        recipient.failRestore = true;
        FakeDiskTokenReader disk = new FakeDiskTokenReader();
        RecordingFeedback feedback = new RecordingFeedback();

        DeliveryCoordinator coord = new DeliveryCoordinator(journalPort, recipient, disk, feedback);
        coord.onPlayerJoin(PLAYER_A);
        coord.putOpenJournal(initial);

        disk.enqueueAnyCheck(new DiskCheck(MarkerStatus.ABSENT, null));
        disk.enqueueExactCheck(MarkerStatus.ABSENT);
        journalPort.failNextWrites = 1; // 봉인 저널 쓰기 실패!

        Journal.Entry sealed = coord.attemptDeliveryForSide(initial, Side.A, false, true, 2000L);

        assertTrue(sealed.isSealed());
        assertTrue(coord.isPlayerSaveFailedLocked(PLAYER_A));
        assertTrue(coord.hasUnpersistedProtectiveStates());
        assertTrue(coord.isJournalOrMemoryLocked());
        assertFalse(journalPort.diskEntries.get(tradeId).isSealed(), "디스크는 아직 비봉인 상태");

        // 관리자 reload / unlock 모두 거부 → 비봉인 디스크가 메모리 봉인을 덮지 못함
        assertEquals(ReloadStatus.REFUSED_UNPERSISTED, coord.reloadJournalsIfLocked(2100L).status());
        assertFalse(coord.unlockByAdmin("Admin", 2200L).result().unlocked());
        assertTrue(coord.openJournal(tradeId).isSealed());

        // 관리자 단건 영속화 재시도 성공 → 디스크에 봉인이 반영되고 전체 메모리 잠금 해제, 거래 봉인은 그대로 유지
        var single = coord.retrySingleUnpersistedWrite(tradeId);
        assertTrue(single.wasUnpersisted());
        assertTrue(single.succeeded());
        assertFalse(coord.hasUnpersistedProtectiveStates());
        assertTrue(journalPort.diskEntries.get(tradeId).isSealed());
        assertTrue(coord.openJournal(tradeId).isSealed());

        // 봉인된 거래는 재시도로 넘어가지 않음
        int placeBefore = recipient.placeAllItemsCalls;
        coord.attemptDeliveryForSide(coord.openJournal(tradeId), Side.A, false, true, 3000L);
        assertEquals(placeBefore, recipient.placeAllItemsCalls);
    }

    /**
     * 핵심 시나리오 5 (P1-1 반환 경로 · P3-11):
     * RETURNING 반환 시도에서도 ABSENT → 회수(인벤토리·PDC 원복) → UNCERTAIN 쓰기 실패 →
     * 재로딩 거부 → 영속화 성공 → 같은 접속 수렴이 동일하게 동작하는지 검증.
     */
    @Test
    void returningAbsentRollbackUncertainWriteFailureLocksReloadThenConverges() {
        UUID tradeId = UUID.randomUUID();
        Journal.Entry initial = returningEntry(tradeId);
        FakeJournalPort journalPort = new FakeJournalPort(initial);
        FakeRecipientPort recipient = new FakeRecipientPort();
        recipient.setPlayer(PLAYER_A, "Alice", true, true, List.of("곡괭이"), EXISTING_PDC_TOKEN);
        FakeDiskTokenReader disk = new FakeDiskTokenReader();
        RecordingFeedback feedback = new RecordingFeedback();

        DeliveryCoordinator coord = new DeliveryCoordinator(journalPort, recipient, disk, feedback);
        coord.onPlayerJoin(PLAYER_A);
        coord.putOpenJournal(initial);

        // 반환 시도 → 사후 ABSENT → 회수 → UNCERTAIN 쓰기 실패
        disk.enqueueAnyCheck(new DiskCheck(MarkerStatus.ABSENT, null));
        disk.enqueueExactCheck(MarkerStatus.ABSENT);
        journalPort.failNextWrites = 1;

        Journal.Entry afterFail = coord.attemptDeliveryForSide(initial, Side.A, true, true, 2000L);
        assertEquals(SideStatus.UNCERTAIN, afterFail.returnA());
        assertEquals(List.of("곡괭이"), recipient.inventoryOf(PLAYER_A));
        assertEquals(EXISTING_PDC_TOKEN, recipient.pdcOf(PLAYER_A));
        assertEquals(ReloadStatus.REFUSED_UNPERSISTED, coord.reloadJournalsIfLocked(2100L).status());

        // 영속화 성공 뒤 같은 접속에서 디스크 FOUND → 수렴 완료
        assertEquals(1, coord.retryUnpersistedProtectiveWrites().succeededCount());
        String retTok = TradeTokens.returnToken(tradeId, PLAYER_A, 1);
        disk.enqueueAnyCheck(new DiskCheck(MarkerStatus.FOUND, retTok));
        disk.enqueueAnyCheck(new DiskCheck(MarkerStatus.FOUND, retTok));

        Journal.Entry converged = coord.attemptDeliveryForSide(
                coord.openJournal(tradeId), Side.A, true, true, 3000L);
        assertEquals(SideStatus.FOUND, converged.returnA());
        assertEquals(List.of("곡괭이", "다이아몬드 x1"), recipient.inventoryOf(PLAYER_A));
        assertTrue(TradeTokens.containsReturnExact(recipient.pdcOf(PLAYER_A), tradeId, PLAYER_A, 1));
    }

    /**
     * 핵심 시나리오 6 (2중 방어: JournalCacheMerge 병합 가드):
     * 저장소 잠금으로 재로딩이 실행되더라도 디스크가 메모리의 UNCERTAIN 보다 덜 보호적인 PENDING 을
     * 돌려주면 캐시를 덮지 않고 보호 상태를 유지한 채 잠금을 유지합니다.
     */
    @Test
    void defensiveReloadMergeRefusesDowngradeFromMemoryUncertainToDiskPending() {
        UUID tradeId = UUID.randomUUID();
        Journal.Entry diskStalePending = committedEntry(tradeId);
        Journal.Entry memUncertain = diskStalePending.withGive(Side.A, SideStatus.UNCERTAIN, 1, false, 2000L);

        FakeJournalPort journalPort = new FakeJournalPort(diskStalePending);
        journalPort.storeLocked = true; // reload 가 실제로 loadAll 을 실행하도록 유도
        FakeRecipientPort recipient = new FakeRecipientPort();
        FakeDiskTokenReader disk = new FakeDiskTokenReader();
        RecordingFeedback feedback = new RecordingFeedback();

        DeliveryCoordinator coord = new DeliveryCoordinator(journalPort, recipient, disk, feedback);
        // unpersistedProtective 플래그 없이 메모리에만 UNCERTAIN 이 있는 극단 상황 가정
        coord.putOpenJournal(memUncertain);

        var reload = coord.reloadJournalsIfLocked(3000L);

        assertEquals(ReloadStatus.RELOADED_STILL_LOCKED, reload.status());
        assertEquals(SideStatus.UNCERTAIN, coord.openJournal(tradeId).giveA(),
                "디스크의 예전 PENDING 이 메모리의 UNCERTAIN 을 덮으면 안 됨");
        assertTrue(coord.hasUnpersistedProtectiveStates());
        assertNotEquals(SideStatus.PENDING, coord.openJournal(tradeId).giveA());
    }

    // ------------------------------------------------------------------ 가짜 포트 구현

    private static final class FakeJournalPort implements DeliveryCoordinator.JournalPort {
        final Map<UUID, Journal.Entry> diskEntries = new LinkedHashMap<>();
        final List<Journal.Entry> writeHistory = new ArrayList<>();
        int failNextWrites = 0;
        boolean storeLocked = false;
        boolean loadSucceeded = true;

        FakeJournalPort(Journal.Entry... initial) {
            for (Journal.Entry e : initial) {
                diskEntries.put(e.tradeId(), e);
            }
        }

        @Override
        public void write(Journal.Entry entry) throws IOException {
            if (failNextWrites > 0) {
                failNextWrites--;
                throw new IOException("디스크 쓰기 주입 오류");
            }
            diskEntries.put(entry.tradeId(), entry);
            writeHistory.add(entry);
        }

        @Override
        public Journal.LoadReport loadAll(long nowMs) {
            loadSucceeded = true;
            return new Journal.LoadReport(
                    List.copyOf(diskEntries.values()),
                    List.of(),
                    storeLocked,
                    storeLocked ? "테스트 잠금" : null);
        }

        @Override
        public Journal.UnlockResult unlock(String adminName, long nowMs) {
            storeLocked = false;
            return new Journal.UnlockResult(true, 0, "잠금 해제");
        }

        @Override
        public boolean isStoreLocked() {
            return !loadSucceeded || storeLocked;
        }

        @Override
        public String storeLockReason() {
            return storeLocked ? "테스트 잠금" : null;
        }

        @Override
        public boolean isLoadSucceeded() {
            return loadSucceeded;
        }
    }

    private static final class FakeRecipientPort implements DeliveryCoordinator.RecipientPort {
        private static final class PlayerState {
            String name;
            boolean online;
            boolean alive;
            List<String> items;
            String pdc;
        }

        private final Map<UUID, PlayerState> players = new HashMap<>();
        boolean failRestore = false;
        int placeAllItemsCalls = 0;
        int saveDataCalls = 0;

        void setPlayer(UUID id, String name, boolean online, boolean alive, List<String> items, String pdc) {
            PlayerState s = new PlayerState();
            s.name = name;
            s.online = online;
            s.alive = alive;
            s.items = new ArrayList<>(items);
            s.pdc = pdc;
            players.put(id, s);
        }

        List<String> inventoryOf(UUID id) {
            return List.copyOf(players.get(id).items);
        }

        String pdcOf(UUID id) {
            return players.get(id).pdc;
        }

        @Override
        public boolean isOnline(UUID playerId) {
            PlayerState s = players.get(playerId);
            return s != null && s.online;
        }

        @Override
        public boolean isAlive(UUID playerId) {
            PlayerState s = players.get(playerId);
            return s != null && s.online && s.alive;
        }

        @Override
        public String playerName(UUID playerId, String fallbackName) {
            PlayerState s = players.get(playerId);
            return s != null ? s.name : fallbackName;
        }

        @Override
        public SlotCheck inspectItemsAndSlots(UUID playerId, List<byte[]> itemBytes) {
            List<String> labels = new ArrayList<>();
            for (byte[] b : itemBytes) {
                labels.add(b.length > 0 && b[0] == 10 ? "다이아몬드 x1" : "네더라이트 주괴 x1");
            }
            return SlotCheck.ok(0, labels);
        }

        @Override
        public boolean saveData(UUID playerId) {
            saveDataCalls++;
            return true;
        }

        @Override
        public Snapshot snapshotInventoryAndPdc(UUID playerId) {
            PlayerState s = players.get(playerId);
            return new Snapshot(new ArrayList<>(s.items), s.pdc);
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean placeAllItems(UUID playerId, Object preparedItems) {
            placeAllItemsCalls++;
            PlayerState s = players.get(playerId);
            s.items.addAll((List<String>) preparedItems);
            return true;
        }

        @Override
        public boolean writePdcAndSave(UUID playerId, String newApplied) {
            PlayerState s = players.get(playerId);
            s.pdc = newApplied;
            saveDataCalls++;
            return true;
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean restoreInventoryAndPdc(UUID playerId, Snapshot snapshot) {
            if (failRestore) {
                return false;
            }
            PlayerState s = players.get(playerId);
            s.items = new ArrayList<>((List<String>) snapshot.inventorySnapshot());
            s.pdc = snapshot.prevApplied();
            return true;
        }
    }

    private static final class FakeDiskTokenReader implements DeliveryCoordinator.DiskTokenReader {
        private final Deque<DiskCheck> anyChecks = new ArrayDeque<>();
        private final Deque<MarkerStatus> exactChecks = new ArrayDeque<>();

        void enqueueAnyCheck(DiskCheck check) {
            anyChecks.addLast(check);
        }

        void enqueueExactCheck(MarkerStatus status) {
            exactChecks.addLast(status);
        }

        @Override
        public DiskCheck checkAnyAttemptToken(UUID playerId, UUID tradeId, TradeTokens.Purpose purpose) {
            DiskCheck next = anyChecks.pollFirst();
            if (next == null) {
                throw new AssertionError("예상하지 않은 checkAnyAttemptToken 호출");
            }
            return next;
        }

        @Override
        public MarkerStatus checkExactAttemptToken(UUID playerId, UUID tradeId, TradeTokens.Purpose purpose, int attempt) {
            MarkerStatus next = exactChecks.pollFirst();
            if (next == null) {
                throw new AssertionError("예상하지 않은 checkExactAttemptToken 호출");
            }
            return next;
        }
    }

    private static final class RecordingFeedback implements DeliveryCoordinator.Feedback {
        final List<String> infos = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final List<String> severes = new ArrayList<>();

        @Override
        public void notifyInventoryFull(UUID playerId, int missingSlots) {
        }

        @Override
        public void notifyUncertainPending(UUID playerId) {
        }

        @Override
        public void notifyRollbackFailedSealed(UUID playerId, UUID tradeId) {
        }

        @Override
        public void logInfo(String message) {
            infos.add(message);
        }

        @Override
        public void logWarning(String message) {
            warnings.add(message);
        }

        @Override
        public void logSevere(String message) {
            severes.add(message);
        }
    }
}
