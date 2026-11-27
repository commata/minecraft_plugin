package kr.commata.trade.service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import kr.commata.common.playerdata.PlayerDatReader;
import kr.commata.common.text.Messages;
import kr.commata.common.time.Durations;
import kr.commata.trade.Msg;
import kr.commata.trade.TradeConfig;
import kr.commata.trade.bridge.BankBridge;
import kr.commata.trade.bridge.TravelBridge;
import kr.commata.trade.core.ClickRules;
import kr.commata.trade.core.Journal;
import kr.commata.trade.core.OfferValidation;
import kr.commata.trade.core.RecoveryPolicy;
import kr.commata.trade.core.SessionRegistry;
import kr.commata.trade.core.TradeSession;
import kr.commata.trade.core.TradeTokens;
import kr.commata.trade.storage.TradeLogger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * 1:1 안전 교환 유스케이스·정산(5장)·장애 복구(6장)·수령(8장) 서비스 (TRADE.md 2~9장).
 *
 * <p>모든 메서드는 메인 스레드에서 호출됩니다.
 * 거래 중({@code OPEN}/{@code COUNTDOWN})에는 아이템이 인벤토리에서 움직이지 않으며,
 * 정산은 {@code TAKING} 저널 fsync → 가져오기(디스크 확인, 미확인 시 반환 금지) →
 * 양쪽 {@code FOUND} 뒤 Bank {@code settleTrade}/0원 {@code COMMITTED} → 지급(같은 틱 회수·시도 번호) →
 * 완료/단방향 {@code RETURNING} 취소 순서로 실행합니다.
 */
public final class TradeService {

    private final Plugin plugin;
    private final Server server;
    private final Logger logger;
    private final Path worldRoot;
    private final BankBridge bank;
    private final TravelBridge travel;
    private final Journal.Store journalStore;
    private final TradeLogger tradeLogger;
    private final NamespacedKey keyApplied;

    private volatile TradeConfig config;
    private volatile TradeUiHooks uiHooks = TradeUiHooks.NOOP;

    private final SessionRegistry sessions = new SessionRegistry();
    /** 활성 거래 세션별 양쪽 제시 칸의 깊은 ItemStack 스냅샷: tradeId -> (side -> (invSlot -> clone)) */
    private final Map<UUID, Map<TradeSession.Side, Map<Integer, ItemStack>>> offerSnapshots = new HashMap<>();
    /** 카운트다운 틱 누적(20틱마다 1초 감소): tradeId -> ticks */
    private final Map<UUID, Integer> countdownTicks = new HashMap<>();

    /** 미완료 저널 메모리 캐시: tradeId -> Entry */
    private final Map<UUID, Journal.Entry> openJournals = new HashMap<>();
    /** 접속 세션 세대 번호 (접속할 때마다 새 값, 퇴장 시 제거 — TRADE.md 6·13-1장) */
    private final AtomicLong connGenSeq = new AtomicLong(0L);
    private final Map<UUID, Long> activeConnGen = new HashMap<>();
    /** 이번 기동에서 TAKING 가져오기를 실행한 시점의 connGen: tradeId -> connGen */
    private final Map<UUID, Long> takingConnGenA = new HashMap<>();
    private final Map<UUID, Long> takingConnGenB = new HashMap<>();
    /** 이번 기동에서 지급/반환 회수 후 UNCERTAIN 이 된 시점의 connGen: tradeId -> connGen (P1-1) */
    private final Map<UUID, Long> giveUncertainConnGenA = new HashMap<>();
    private final Map<UUID, Long> giveUncertainConnGenB = new HashMap<>();
    private final Map<UUID, Long> returnUncertainConnGenA = new HashMap<>();
    private final Map<UUID, Long> returnUncertainConnGenB = new HashMap<>();
    /** 같은 접속에서 saveData 후 ABSENT 가 3회 연속 발생하거나 회수 실패로 Trade 가 잠긴 플레이어 */
    private final Set<UUID> saveFailedLockedPlayers = new HashSet<>();
    /** 한 틱 안 재진입 방지 */
    private final Set<UUID> inFlight = new HashSet<>();

    public TradeService(Plugin plugin, Path dataDir, Path worldRoot,
                        BankBridge bank, TravelBridge travel, TradeConfig config) {
        this.plugin = plugin;
        this.server = plugin.getServer();
        this.logger = plugin.getLogger();
        this.worldRoot = worldRoot;
        this.bank = bank;
        this.travel = travel;
        this.journalStore = new Journal.Store(dataDir);
        this.tradeLogger = new TradeLogger(dataDir, logger);
        this.keyApplied = new NamespacedKey(plugin, TradeTokens.KEY_NAME);
        this.config = config;
    }

    public void setUiHooks(TradeUiHooks hooks) {
        this.uiHooks = hooks == null ? TradeUiHooks.NOOP : hooks;
    }

    public TradeConfig config() {
        return config;
    }

    public void updateConfig(TradeConfig newConfig) {
        this.config = newConfig;
    }

    public BankBridge bank() {
        return bank;
    }

    public TravelBridge travel() {
        return travel;
    }

    public Journal.Store journalStore() {
        return journalStore;
    }

    public SessionRegistry sessions() {
        return sessions;
    }

    public Collection<Journal.Entry> openJournals() {
        return List.copyOf(openJournals.values());
    }

    public Set<UUID> saveFailedLockedPlayers() {
        return Set.copyOf(saveFailedLockedPlayers);
    }

    /**
     * 새 거래 요청·수락·정산(돈 이동) 불가 여부 (TRADE.md 1·6·10장 5, P2-7).
     *
     * <p>저널 잠금은 복구·지급·반환까지 전체를 막지만, Bank 불가·잠금은 새 요청·수락·정산과 금액 있는
     * {@code TAKING} 의 Bank 확인만 막고, 이미 확정된({@code COMMITTED}·{@code RETURNING}) 아이템 지급·반환과
     * 0원 {@code TAKING} 복구는 Bank 없이도 계속합니다.
     */
    public boolean isGloballyLocked() {
        return journalStore.isLocked() || !bank.available();
    }

    public String globalLockReason() {
        if (journalStore.isLocked()) {
            return journalStore.lockReason();
        }
        if (!bank.available()) {
            return "Bank 불가 (" + bank.statusText() + ")";
        }
        return null;
    }

    public boolean hasOpenJournal(UUID playerId) {
        for (Journal.Entry e : openJournals.values()) {
            if (e.involves(playerId)) {
                return true;
            }
        }
        return false;
    }

    public boolean isPlayerBlockedFromNewTrade(UUID playerId) {
        return isGloballyLocked()
                || hasOpenJournal(playerId)
                || saveFailedLockedPlayers.contains(playerId)
                || inFlight.contains(playerId);
    }

    public static boolean isAllowedGameMode(GameMode mode) {
        return mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE;
    }

    // ------------------------------------------------------------------ 기동·접속·퇴장·타이머

    /** 서버 기동 시 저널 로드, 손상 격리 및 미완료 저널 복구 (TRADE.md 6장). */
    public void startupLoadAndReconcile() {
        long nowMs = System.currentTimeMillis();
        // 이미 접속 중인 플레이어가 있다면(정상 기동엔 없음) connGen 부여
        for (Player p : server.getOnlinePlayers()) {
            activeConnGen.putIfAbsent(p.getUniqueId(), connGenSeq.incrementAndGet());
        }
        try {
            Journal.LoadReport report = journalStore.loadAll(nowMs);
            openJournals.clear();
            for (Journal.Entry entry : report.entries()) {
                openJournals.put(entry.tradeId(), entry);
            }
            if (!report.newlyQuarantined().isEmpty()) {
                logger.severe("손상된 거래 저널을 격리하고 LOCKED 를 생성했습니다: " + report.newlyQuarantined());
            }
            if (report.locked()) {
                logger.severe("==========================================================");
                logger.severe("Trade 전체 잠금 모드: " + report.lockReason());
                logger.severe("plugins/Trade/journal/corrupt/ 의 손상 파일을 확인한 뒤");
                logger.severe("관리자가 /trade resolve unlock 을 실행해야 잠금이 해제됩니다.");
                logger.severe("==========================================================");
                return;
            }
            if (!openJournals.isEmpty()) {
                logger.info("미완료 거래 저널 " + openJournals.size() + "건 기동 복구를 시작합니다.");
                reconcileAllOpenJournals(false);
            }
        } catch (IOException e) {
            openJournals.clear();
            logger.severe("==========================================================");
            logger.severe("Trade 저널 폴더 로드 실패 — Trade 전체 잠금 모드로 전환합니다: " + e.getMessage());
            logger.severe("디스크·권한 문제를 해결한 뒤 /trade reload 또는 /trade resolve unlock 으로");
            logger.severe("저널 재로딩과 검증이 완료되어야 잠금이 해제됩니다.");
            logger.severe("==========================================================");
        }
    }

    /**
     * {@code /trade reload} 시 저널 미로드 또는 잠금 상태라면 전체 저널 재로딩·검증을 시도합니다 (P1-2).
     *
     * @return 재로딩 결과 안내 문구(평문), 이미 정상 로드·비잠금 상태이면 {@code null}
     */
    public String reloadJournalsIfLocked() {
        if (journalStore.isLoadSucceeded() && !journalStore.isLocked()) {
            return null;
        }
        long nowMs = System.currentTimeMillis();
        try {
            Journal.LoadReport report = journalStore.loadAll(nowMs);
            openJournals.clear();
            for (Journal.Entry entry : report.entries()) {
                openJournals.put(entry.tradeId(), entry);
            }
            if (!report.newlyQuarantined().isEmpty()) {
                logger.severe("재로딩 중 손상 거래 저널 격리 + LOCKED 생성: " + report.newlyQuarantined());
            }
            if (report.locked() || journalStore.isLocked()) {
                String reason = journalStore.lockReason();
                logger.warning("Trade 저널 재로딩 완료 (여전히 잠금 모드: " + reason + ")");
                return "설정을 다시 불러왔으나 저널 잠금 상태가 유지됩니다: " + reason;
            }
            logger.info("Trade 저널 재로딩·검증 완료 (잠금 해제, 미완료 저널 " + openJournals.size() + "건).");
            if (!openJournals.isEmpty()) {
                reconcileAllOpenJournals(true);
            }
            return "Trade 설정 및 저널(" + openJournals.size() + "건)을 다시 불러와 잠금이 해제되었습니다.";
        } catch (IOException e) {
            openJournals.clear();
            logger.severe("Trade 저널 재로딩 실패 (잠금 유지): " + e.getMessage());
            return "설정은 다시 불러왔으나 저널 재로딩 실패로 잠금이 유지됩니다: " + e.getMessage();
        }
    }

    /**
     * 관리자 {@code /trade resolve unlock}:
     * 1) 미로드 상태면 먼저 전체 재로딩·검증(새 손상 파일이 발견되면 즉시 해제하지 않고 관리자 확인 요구)
     * 2) 미해결 격리 파일 {@code .resolved} fsync 후 {@code LOCKED} 삭제
     * 3) 전체 저널 재로딩·검증이 성공해 {@code isLocked() == false} 일 때만 복구 재개 (P1-2).
     */
    public Journal.UnlockResult unlockByAdmin(String adminName) {
        long nowMs = System.currentTimeMillis();
        try {
            if (!journalStore.isLoadSucceeded()) {
                Journal.LoadReport pre = journalStore.loadAll(nowMs);
                openJournals.clear();
                for (Journal.Entry entry : pre.entries()) {
                    openJournals.put(entry.tradeId(), entry);
                }
                if (!pre.newlyQuarantined().isEmpty()) {
                    logger.severe("unlock 전 재로딩 중 새 손상 저널 격리: " + pre.newlyQuarantined());
                    return new Journal.UnlockResult(false, 0,
                            "저널 재로딩 중 새 손상 파일이 격리되었습니다("
                                    + String.join(", ", pre.newlyQuarantined())
                                    + "). 확인 후 다시 /trade resolve unlock 을 실행하세요.");
                }
            }

            Journal.UnlockResult unlock = journalStore.unlock(adminName, nowMs);
            if (!unlock.unlocked()) {
                return unlock;
            }

            // 잠금 파일 삭제 후 전체 저널 목록을 다시 로드·검증해야만 잠금 해제 확정
            Journal.LoadReport verified = journalStore.loadAll(nowMs);
            openJournals.clear();
            for (Journal.Entry entry : verified.entries()) {
                openJournals.put(entry.tradeId(), entry);
            }
            if (!verified.newlyQuarantined().isEmpty() || verified.locked() || journalStore.isLocked()) {
                return new Journal.UnlockResult(false, unlock.resolvedFiles(),
                        "잠금 해제 후 재검증에서 여전히 잠금 상태입니다: " + journalStore.lockReason());
            }
            if (!openJournals.isEmpty()) {
                reconcileAllOpenJournals(true);
            }
            return unlock;
        } catch (IOException e) {
            logger.severe("Trade 잠금 해제·재검증 중 I/O 오류 (잠금 유지): " + e.getMessage());
            return new Journal.UnlockResult(false, 0,
                    "저널 재로딩·검증 실패로 잠금을 해제하지 못했습니다: " + e.getMessage());
        }
    }

    /** {@code PlayerJoinEvent} (LOWEST): 새 connGen 부여 후 해당 플레이어 관련 미완료 저널 복구·지급. */
    public void onPlayerJoin(Player player) {
        UUID uuid = player.getUniqueId();
        activeConnGen.put(uuid, connGenSeq.incrementAndGet());
        if (journalStore.isLocked()) {
            return;
        }
        List<UUID> related = relatedJournalIds(uuid);
        for (UUID tradeId : related) {
            Journal.Entry entry = openJournals.get(tradeId);
            if (entry != null) {
                reconcileEntry(entry, true);
            }
        }
    }

    /**
     * {@code PlayerQuitEvent} (MONITOR):
     * 1) connGen 제거 → 같은 접속 세션 재시도 대상에서 제외
     * 2) 정산 전 세션은 이벤트 안에서 즉시 CANCELLED
     * 3) TAKING 저널이 있으면 Paper 의 퇴장 저장이 끝난 뒤(다음 틱)에 디스크 재판정 실행 (TRADE.md 6장)
     */
    public void onPlayerQuit(UUID uuid) {
        activeConnGen.remove(uuid);
        sessions.sessionOf(uuid).ifPresent(session -> {
            if (session.isPreSettlement()) {
                cancelPreSettlement(session, "접속 종료");
            }
        });
        if (!journalStore.isLocked()) {
            List<UUID> related = relatedJournalIds(uuid);
            if (!related.isEmpty()) {
                server.getScheduler().runTask(plugin, () -> {
                    if (journalStore.isLocked()) {
                        return;
                    }
                    for (UUID tradeId : related) {
                        Journal.Entry entry = openJournals.get(tradeId);
                        if (entry != null) {
                            reconcileEntry(entry, true);
                        }
                    }
                });
            }
        }
    }

    /** {@code PlayerRespawnEvent} (MONITOR, 다음 틱): 사망 중 대기하던 지급·반환을 재개합니다 (TRADE.md 5·6장). */
    public void onPlayerRespawn(Player player) {
        if (player == null || !player.isOnline() || player.isDead() || journalStore.isLocked()) {
            return;
        }
        List<UUID> related = relatedJournalIds(player.getUniqueId());
        for (UUID tradeId : related) {
            Journal.Entry entry = openJournals.get(tradeId);
            if (entry != null) {
                reconcileEntry(entry, true);
            }
        }
    }

    /** 강퇴·사망·모드 변경·전투 등 정산 전 즉시 취소 사유 처리 (TRADE.md 2·7장). */
    public void cancelIfActive(UUID playerId, String reason) {
        sessions.sessionOf(playerId).ifPresent(session -> {
            if (session.isPreSettlement()) {
                cancelPreSettlement(session, reason);
            }
        });
    }

    /** {@code InventoryCloseEvent} (TradeMenu.closed 에서 동기 호출): 대화창 전환 1회 예외 외에는 즉시 취소. */
    public void onTradeMenuClosed(Player player, TradeSession session) {
        if (session == null || !session.isPreSettlement()) {
            return;
        }
        TradeSession.Side side = session.sideOf(player.getUniqueId());
        if (side == null) {
            return;
        }
        if (session.consumeExpectedDialogClose(side)) {
            // 금액 입력 대화창을 여느라 닫힌 1회만 취소로 보지 않음 (TRADE.md 7장)
            return;
        }
        if (session.participant(side).inDialog()) {
            // 이미 대화창이 떠 있는 상태의 중복 닫기 이벤트는 무시
            return;
        }
        cancelPreSettlement(session, player.getName() + "님이 창을 닫음");
    }

    /** 매 틱(1L) 실행: 요청 만료·대화창 만료·제시 슬롯 실시간 검증·카운트다운 진행 (TRADE.md 2·3·7장). */
    public void tickPerTick() {
        if (sessions.activeCount() == 0) {
            return;
        }
        long nowMs = System.currentTimeMillis();

        // 1) 30초 만료 요청 처리
        for (TradeSession expired : sessions.findExpiredRequests(nowMs)) {
            if (expired.cancelIfPreSettlement("시간 만료")) {
                sessions.remove(expired);
                sessions.setReRequestCooldown(expired.sideA().uuid(), expired.sideB().uuid(),
                        nowMs, config.reRequestCooldownMs());
                notifyIfOnline(expired.sideA().uuid(),
                        "<#FFDF6B>" + expired.sideB().name() + "님에게 보낸 거래 요청이 만료되었습니다.");
                notifyIfOnline(expired.sideB().uuid(),
                        "<#FFDF6B>" + expired.sideA().name() + "님의 거래 요청이 만료되었습니다.");
            }
        }

        // 2) OPEN / COUNTDOWN 세션 매 틱 검사
        for (TradeSession session : sessions.allSessions()) {
            if (!session.isInteractive()) {
                continue;
            }
            Player pA = server.getPlayer(session.sideA().uuid());
            Player pB = server.getPlayer(session.sideB().uuid());
            if (pA == null || !pA.isOnline() || pB == null || !pB.isOnline()) {
                cancelPreSettlement(session, "접속 종료");
                continue;
            }

            // 대화창 만료(60초) 검사
            checkDialogExpiration(session, TradeSession.Side.A, pA, nowMs);
            checkDialogExpiration(session, TradeSession.Side.B, pB, nowMs);
            if (!session.isInteractive()) {
                continue;
            }

            // 제시 칸 실시간 재검증 (줍기·Market 지급·소모·내구도·꾸러미 변화 검출, TRADE.md 3·14장)
            boolean mutatedA = revalidateOfferedSlots(session, TradeSession.Side.A, pA);
            boolean mutatedB = revalidateOfferedSlots(session, TradeSession.Side.B, pB);
            if (mutatedA || mutatedB) {
                countdownTicks.remove(session.tradeId());
                uiHooks.refreshSessionMenus(session);
                continue;
            }

            // 카운트다운 (20틱 = 1초마다 3 → 2 → 1 → 0)
            if (session.state() == TradeSession.State.COUNTDOWN) {
                int ticks = countdownTicks.merge(session.tradeId(), 1, Integer::sum);
                if (ticks >= 20) {
                    countdownTicks.put(session.tradeId(), 0);
                    long expectedGen = session.countdownGen();
                    boolean settleNow = session.tickCountdownSecond(expectedGen);
                    if (settleNow) {
                        countdownTicks.remove(session.tradeId());
                        executeSettlement(session, expectedGen);
                    } else if (session.state() == TradeSession.State.COUNTDOWN) {
                        uiHooks.refreshSessionMenus(session);
                        pA.playSound(pA.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.2f);
                        pB.playSound(pB.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.2f);
                    }
                }
            } else {
                countdownTicks.remove(session.tradeId());
            }
        }
    }

    /** 1분마다 실행되는 자동 복구 타이머 (메인 스레드, TRADE.md 5·6장). */
    public void tickRecovery() {
        sessions.pruneCooldowns(System.currentTimeMillis());
        if (journalStore.isLocked()) {
            return;
        }
        reconcileAllOpenJournals(true);
    }

    /** 플러그인 종료(onDisable) 시 진행 중인 사전 거래 세션을 전부 정리합니다. */
    public void shutdown() {
        for (TradeSession session : sessions.clearAll()) {
            if (session.isPreSettlement()) {
                session.cancelIfPreSettlement("서버 종료");
                Player a = server.getPlayer(session.sideA().uuid());
                Player b = server.getPlayer(session.sideB().uuid());
                if (a != null && a.isOnline()) {
                    uiHooks.closeDialog(a);
                }
                if (b != null && b.isOnline()) {
                    uiHooks.closeDialog(b);
                }
            }
        }
        offerSnapshots.clear();
        countdownTicks.clear();
    }

    // ------------------------------------------------------------------ 요청 · 수락 · 거절 · 취소

    /** 접속자 목록에서 이 대상에게 요청할 수 없는 이유. 요청 가능하면 {@code null}. */
    public String unavailableReason(Player requester, Player target, long nowMs) {
        if (isGloballyLocked()) {
            return "거래 잠김";
        }
        UUID reqId = requester.getUniqueId();
        UUID tgtId = target.getUniqueId();
        if (hasOpenJournal(tgtId) || saveFailedLockedPlayers.contains(tgtId)) {
            return "정산 대기 중";
        }
        if (!isAllowedGameMode(target.getGameMode())) {
            return "크리에이티브/관전 중";
        }
        if (travel.inCombat(target, nowMs)) {
            return "전투 중";
        }
        Optional<TradeSession> targetSession = sessions.sessionOf(tgtId);
        if (targetSession.isPresent()) {
            TradeSession s = targetSession.get();
            if (s.state() == TradeSession.State.REQUESTED && s.sideA().uuid().equals(reqId)) {
                long sec = Math.max(1L, (s.requestRemainingMs(nowMs) + 999L) / 1000L);
                return "요청 대기 " + sec + "초";
            }
            return "거래 중";
        }
        long cd = sessions.reRequestRemainingMs(reqId, tgtId, nowMs);
        if (cd > 0L) {
            long sec = Math.max(1L, (cd + 999L) / 1000L);
            return "재요청 " + sec + "초 뒤";
        }
        return null;
    }

    public boolean sendRequest(Player requester, Player target) {
        long nowMs = System.currentTimeMillis();
        if (!ensureReadyForNewTrade(requester)) {
            return false;
        }
        if (requester.getUniqueId().equals(target.getUniqueId())) {
            Msg.error(requester, "자기 자신에게는 거래를 요청할 수 없습니다.");
            return false;
        }
        if (!isAllowedGameMode(requester.getGameMode())) {
            Msg.error(requester, "크리에이티브·관전 모드에서는 거래할 수 없습니다.");
            return false;
        }
        if (travel.inCombat(requester, nowMs)) {
            Msg.error(requester, "전투 중에는 거래를 요청할 수 없습니다 ("
                    + Durations.korean(Duration.ofMillis(travel.combatRemainingMs(requester, nowMs))) + " 남음).");
            return false;
        }
        if (sessions.hasSession(requester.getUniqueId())) {
            Msg.error(requester, "이미 진행 중인 거래나 요청이 있습니다. 먼저 취소해 주세요 (/trade cancel).");
            return false;
        }
        if (!target.isOnline() || !requester.canSee(target)) {
            Msg.error(requester, "접속 중인 플레이어를 찾을 수 없습니다.");
            return false;
        }
        if (!isAllowedGameMode(target.getGameMode())) {
            Msg.error(requester, target.getName() + "님은 현재 크리에이티브·관전 모드라 거래할 수 없습니다.");
            return false;
        }
        if (travel.inCombat(target, nowMs)) {
            Msg.error(requester, target.getName() + "님은 현재 전투 중이라 거래할 수 없습니다.");
            return false;
        }
        if (hasOpenJournal(target.getUniqueId()) || saveFailedLockedPlayers.contains(target.getUniqueId())) {
            Msg.error(requester, target.getName() + "님은 이전 거래 정산 확인 대기 중이라 거래할 수 없습니다.");
            return false;
        }
        if (sessions.hasSession(target.getUniqueId())) {
            Msg.error(requester, target.getName() + "님은 이미 다른 거래를 진행 중입니다.");
            return false;
        }
        long cd = sessions.reRequestRemainingMs(requester.getUniqueId(), target.getUniqueId(), nowMs);
        if (cd > 0L) {
            Msg.error(requester, target.getName() + "님에게는 "
                    + Durations.korean(Duration.ofMillis(cd)) + " 뒤에 다시 요청할 수 있습니다.");
            return false;
        }

        TradeSession session = new TradeSession(
                UUID.randomUUID(),
                requester.getUniqueId(), requester.getName(),
                target.getUniqueId(), target.getName(),
                nowMs, config.requestTimeoutMs(), config.countdownSeconds());
        if (!sessions.register(session)) {
            Msg.error(requester, "거래 요청을 등록하지 못했습니다.");
            return false;
        }

        Msg.info(requester, "<#55FFFF>" + target.getName() + "<#AAAAAA>님에게 거래 요청을 보냈습니다 ("
                + config.requestTimeoutSeconds() + "초).");

        // 채팅 요청 + 클릭 버튼 [수락] [거절] + 알림음 1회 (ECONOMY.md 7.4.6a)
        Component header = Messages.parse("<#F5C542>[거래] <#55FFFF>" + requester.getName()
                + "<#AAAAAA>님이 거래를 요청했습니다 (" + config.requestTimeoutSeconds() + "초) ");
        Component acceptBtn = Messages.parse("<#55FF55>[수락]")
                .clickEvent(ClickEvent.runCommand("/trade accept " + requester.getName()))
                .hoverEvent(HoverEvent.showText(Messages.parse("<#55FF55>클릭하여 거래 수락")));
        Component denyBtn = Messages.parse("<#FF5555>[거절]")
                .clickEvent(ClickEvent.runCommand("/trade deny " + requester.getName()))
                .hoverEvent(HoverEvent.showText(Messages.parse("<#FF5555>클릭하여 거래 거절")));
        Msg.component(target, header.append(acceptBtn).append(Component.text(" ")).append(denyBtn));
        target.playSound(target.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
        return true;
    }

    public boolean acceptRequest(Player target, String optionalRequesterName) {
        long nowMs = System.currentTimeMillis();
        if (!ensureReadyForNewTrade(target)) {
            return false;
        }
        Optional<TradeSession> opt = sessions.sessionOf(target.getUniqueId());
        if (opt.isEmpty()) {
            Msg.error(target, "받은 거래 요청이 없습니다.");
            return false;
        }
        TradeSession session = opt.get();
        if (session.state() != TradeSession.State.REQUESTED || !session.sideB().uuid().equals(target.getUniqueId())) {
            Msg.error(target, "수락할 수 있는 받은 거래 요청이 없습니다.");
            return false;
        }
        if (optionalRequesterName != null && !optionalRequesterName.isBlank()
                && !session.sideA().name().equalsIgnoreCase(optionalRequesterName)) {
            Msg.error(target, optionalRequesterName + "님에게 받은 거래 요청이 없습니다.");
            return false;
        }
        if (session.isRequestExpired(nowMs)) {
            session.cancelIfPreSettlement("시간 만료");
            sessions.remove(session);
            sessions.setReRequestCooldown(session.sideA().uuid(), session.sideB().uuid(),
                    nowMs, config.reRequestCooldownMs());
            Msg.error(target, "거래 요청이 만료되었습니다.");
            return false;
        }

        Player requester = server.getPlayer(session.sideA().uuid());
        if (requester == null || !requester.isOnline()) {
            cancelPreSettlement(session, "상대방 접속 종료");
            return false;
        }
        if (!isAllowedGameMode(target.getGameMode()) || !isAllowedGameMode(requester.getGameMode())) {
            Msg.error(target, "크리에이티브·관전 모드에서는 거래할 수 없습니다.");
            return false;
        }
        if (travel.inCombat(target, nowMs)) {
            Msg.error(target, "전투 중에는 거래를 수락할 수 없습니다.");
            return false;
        }
        if (travel.inCombat(requester, nowMs)) {
            Msg.error(target, requester.getName() + "님이 현재 전투 중이라 거래를 시작할 수 없습니다.");
            return false;
        }
        if (hasOpenJournal(requester.getUniqueId()) || saveFailedLockedPlayers.contains(requester.getUniqueId())) {
            Msg.error(target, requester.getName() + "님의 이전 거래 정산 확인이 진행 중입니다.");
            return false;
        }
        // 커서에 아이템이 있으면 창을 열지 않음 (TRADE.md 7·13장)
        if (!TradeInventoryHelper.isEmpty(target.getItemOnCursor())) {
            Msg.error(target, "손에 든 아이템을 먼저 내려놓아 주세요.");
            return false;
        }
        if (!TradeInventoryHelper.isEmpty(requester.getItemOnCursor())) {
            Msg.error(target, requester.getName() + "님이 손에 아이템을 들고 있어 지금은 거래 창을 열 수 없습니다.");
            Msg.warn(requester, "손에 든 아이템을 먼저 내려놓아 주세요.");
            return false;
        }

        if (!session.accept()) {
            return false;
        }
        Map<TradeSession.Side, Map<Integer, ItemStack>> snapMap = new HashMap<>();
        snapMap.put(TradeSession.Side.A, new HashMap<>());
        snapMap.put(TradeSession.Side.B, new HashMap<>());
        offerSnapshots.put(session.tradeId(), snapMap);

        Msg.success(requester, target.getName() + "님이 거래를 수락했습니다.");
        Msg.success(target, requester.getName() + "님의 거래 요청을 수락했습니다.");
        uiHooks.openTradeMenu(requester, session);
        uiHooks.openTradeMenu(target, session);
        return true;
    }

    public boolean denyRequest(Player target, String optionalRequesterName) {
        long nowMs = System.currentTimeMillis();
        Optional<TradeSession> opt = sessions.sessionOf(target.getUniqueId());
        if (opt.isEmpty()) {
            Msg.error(target, "거절할 거래 요청이 없습니다.");
            return false;
        }
        TradeSession session = opt.get();
        if (session.state() != TradeSession.State.REQUESTED || !session.sideB().uuid().equals(target.getUniqueId())) {
            Msg.error(target, "거절할 수 있는 받은 거래 요청이 없습니다.");
            return false;
        }
        if (optionalRequesterName != null && !optionalRequesterName.isBlank()
                && !session.sideA().name().equalsIgnoreCase(optionalRequesterName)) {
            Msg.error(target, optionalRequesterName + "님에게 받은 거래 요청이 없습니다.");
            return false;
        }
        if (session.cancelIfPreSettlement("거절")) {
            sessions.remove(session);
            sessions.setReRequestCooldown(session.sideA().uuid(), session.sideB().uuid(),
                    nowMs, config.reRequestCooldownMs());
            Msg.info(target, session.sideA().name() + "님의 거래 요청을 거절했습니다.");
            notifyIfOnline(session.sideA().uuid(),
                    "<#FF5555>" + target.getName() + "님이 거래 요청을 거절했습니다.");
            return true;
        }
        return false;
    }

    public boolean cancelOutgoingOrActive(Player player) {
        Optional<TradeSession> opt = sessions.sessionOf(player.getUniqueId());
        if (opt.isEmpty()) {
            Msg.error(player, "취소할 거래나 요청이 없습니다.");
            return false;
        }
        TradeSession session = opt.get();
        if (session.state() == TradeSession.State.REQUESTED) {
            if (session.sideB().uuid().equals(player.getUniqueId())) {
                return denyRequest(player, null);
            }
            if (session.cancelIfPreSettlement("요청 취소")) {
                sessions.remove(session);
                Msg.info(player, session.sideB().name() + "님에게 보낸 거래 요청을 취소했습니다.");
                notifyIfOnline(session.sideB().uuid(),
                        "<#FFDF6B>" + player.getName() + "님이 거래 요청을 취소했습니다.");
                return true;
            }
            return false;
        }
        if (session.isInteractive()) {
            cancelPreSettlement(session, player.getName() + "님이 취소");
            return true;
        }
        Msg.warn(player, "이미 정산이 시작되어 취소할 수 없습니다.");
        return false;
    }

    // ------------------------------------------------------------------ 거래 창 클릭 · 대화창 처리

    public void handleTradeClick(Player player, TradeSession session, ClickRules.Action action) {
        if (session == null || !session.isInteractive() || action instanceof ClickRules.Ignore) {
            return;
        }
        TradeSession.Side side = session.sideOf(player.getUniqueId());
        if (side == null) {
            return;
        }
        if (session.participant(side).inDialog()) {
            return;
        }

        switch (action) {
            case ClickRules.Ignore ignored -> { }
            case ClickRules.AddOfferAll(int invSlot) -> handleAddOffer(player, session, side, invSlot, false);
            case ClickRules.AddOfferOne(int invSlot) -> handleAddOffer(player, session, side, invSlot, true);
            case ClickRules.RemoveOfferAll(int offerIndex) -> handleRemoveOffer(player, session, side, offerIndex, false);
            case ClickRules.RemoveOfferOne(int offerIndex) -> handleRemoveOffer(player, session, side, offerIndex, true);
            case ClickRules.OpenMoneyDialog ignored -> scheduleOpenMoneyDialog(player, session, side);
            case ClickRules.ToggleReady ignored -> handleToggleReady(player, session, side);
            case ClickRules.CancelTrade ignored -> cancelPreSettlement(session, player.getName() + "님이 취소");
        }
    }

    private void handleAddOffer(Player player, TradeSession session, TradeSession.Side side, int invSlot, boolean oneOnly) {
        ItemStack[] storage = player.getInventory().getStorageContents();
        if (invSlot < 0 || invSlot >= storage.length) {
            return;
        }
        ItemStack live = storage[invSlot];
        if (TradeInventoryHelper.isEmpty(live)) {
            return;
        }
        Map<Integer, ItemStack> snaps = snapshotsFor(session.tradeId(), side);
        ItemStack existingSnap = snaps.get(invSlot);
        if (existingSnap != null && !live.isSimilar(existingSnap)) {
            // 칸 내용이 이미 바뀌었으면 기존 제시를 지우고 새 스냅샷으로
            session.removeOfferSlot(side, invSlot);
            snaps.remove(invSlot);
        }

        TradeSession.OfferChangeResult res = oneOnly
                ? session.addOneOffer(side, invSlot, live.getAmount())
                : session.setOfferAmount(side, invSlot, live.getAmount(), live.getAmount());
        switch (res) {
            case CHANGED -> {
                snaps.put(invSlot, live.clone());
                countdownTicks.remove(session.tradeId());
                uiHooks.refreshSessionMenus(session);
            }
            case MAX_SLOTS_REACHED -> Msg.warn(player, "아이템은 최대 " + OfferValidation.MAX_OFFER_SLOTS + "칸까지만 제시할 수 있습니다.");
            default -> { }
        }
    }

    private void handleRemoveOffer(Player player, TradeSession session, TradeSession.Side side, int offerIndex, boolean oneOnly) {
        Integer invSlot = session.invSlotAtOfferIndex(side, offerIndex);
        if (invSlot == null) {
            return;
        }
        TradeSession.OfferChangeResult res = oneOnly
                ? session.removeOneOfferByIndex(side, offerIndex)
                : session.removeOfferByIndex(side, offerIndex);
        if (res == TradeSession.OfferChangeResult.CHANGED) {
            if (session.participant(side).offeredAmount(invSlot) <= 0) {
                snapshotsFor(session.tradeId(), side).remove(invSlot);
            }
            countdownTicks.remove(session.tradeId());
            uiHooks.refreshSessionMenus(session);
        }
    }

    private void handleToggleReady(Player player, TradeSession session, TradeSession.Side side) {
        long nowMs = System.currentTimeMillis();
        if (!isAllowedGameMode(player.getGameMode())) {
            cancelPreSettlement(session, "게임 모드 변경");
            return;
        }
        if (travel.inCombat(player, nowMs)) {
            cancelPreSettlement(session, "전투 상태 진입");
            return;
        }
        if (revalidateOfferedSlots(session, side, player)) {
            countdownTicks.remove(session.tradeId());
            uiHooks.refreshSessionMenus(session);
            return;
        }

        boolean turningOn = !session.participant(side).ready();
        if (turningOn) {
            // 받을 물건이 내 보관함(자기 제시분 제외)에 다 들어가는지 미리 안내 (차단은 하지 않고 5단계 claim 으로 이어짐)
            Player partner = server.getPlayer(session.participant(side.other()).uuid());
            if (partner != null && partner.isOnline()) {
                List<ItemStack> expectedIncoming = buildExpectedIncoming(partner, session.participant(side.other()).offers());
                int missing = TradeInventoryHelper.missingSlots(
                        player.getInventory(), session.participant(side).offers(), expectedIncoming);
                if (missing > 0) {
                    Msg.warn(player, "참고: 현재 인벤토리 빈칸이 " + missing + "칸 부족합니다. "
                            + "거래 완료 후 부족한 물건은 <#FFDF6B>/trade claim<#AAAAAA> 으로 받을 수 있습니다.");
                }
            }
        }

        if (session.toggleReady(side)) {
            countdownTicks.remove(session.tradeId());
            uiHooks.refreshSessionMenus(session);
            if (session.state() == TradeSession.State.COUNTDOWN) {
                Player a = server.getPlayer(session.sideA().uuid());
                Player b = server.getPlayer(session.sideB().uuid());
                if (a != null) {
                    a.playSound(a.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.3f);
                }
                if (b != null) {
                    b.playSound(b.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.3f);
                }
            }
        }
    }

    private void scheduleOpenMoneyDialog(Player player, TradeSession session, TradeSession.Side side) {
        UUID tradeId = session.tradeId();
        long clickedGen = session.gen();
        // 이벤트 안에서 창 닫기·대화창 열기를 직접 부르지 않고 다음 틱으로 미룸 (AGENTS.md 6·7장)
        server.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            Optional<TradeSession> currentOpt = sessions.byId(tradeId);
            if (currentOpt.isEmpty() || currentOpt.get() != session || !session.isInteractive()) {
                return;
            }
            if (session.gen() != clickedGen || session.participant(side).inDialog()) {
                return;
            }
            long balance = bank.balance(player);
            if (balance < 0L) {
                Msg.error(player, "잔액을 확인할 수 없어 금액을 입력할 수 없습니다.");
                return;
            }
            UUID token = session.beginDialog(side, UUID.randomUUID(), System.currentTimeMillis(), config.dialogTimeoutMs());
            if (token == null) {
                return;
            }
            countdownTicks.remove(tradeId);
            uiHooks.refreshSessionMenus(session);
            // 먼저 서버에서 거래 창을 닫아 InventoryCloseEvent(Reason.PLUGIN) 를 여기서 즉시 소비(1회 예외)한 뒤 Dialog 를 연다.
            player.closeInventory();
            try {
                uiHooks.openMoneyDialog(player, session, token, balance);
            } catch (RuntimeException e) {
                logger.warning("금액 입력 대화창 열기 실패 (" + player.getName() + "): " + e.getMessage());
                session.cancelDialog(side, token);
                reopenTradeMenuOrCancel(player, session);
                uiHooks.refreshSessionMenus(session);
            }
        });
    }

    /** Paper Dialog 확인 버튼 응답 처리 (반드시 메인 스레드에서 호출). */
    public void onDialogSubmit(Player player, UUID tradeId, UUID dialogToken, long capturedGen, String rawAmount) {
        Optional<TradeSession> opt = sessions.byId(tradeId);
        if (opt.isEmpty()) {
            return;
        }
        TradeSession session = opt.get();
        TradeSession.Side side = session.sideOf(player.getUniqueId());
        if (side == null || !session.isInteractive()) {
            return;
        }
        TradeSession.Participant p = session.participant(side);
        if (!p.inDialog() || !dialogToken.equals(p.dialogToken())) {
            return;
        }

        // 세대가 바뀌었으면 응답 무시 (TRADE.md 2·7장)
        if (session.gen() != capturedGen) {
            session.submitDialogMoney(side, dialogToken, 0L);
            Msg.warn(player, "거래 내용이 바뀌어 금액 입력이 무시되었습니다.");
            reopenTradeMenuOrCancel(player, session);
            uiHooks.refreshSessionMenus(session);
            return;
        }

        long balance = bank.balance(player);
        OfferValidation.MoneyParseResult parsed = OfferValidation.parseAndCheckMoney(rawAmount, balance);
        if (!parsed.ok()) {
            session.cancelDialog(side, dialogToken);
            Msg.error(player, "금액은 0 이상 정수, 잔액 이하만 가능합니다.");
            reopenTradeMenuOrCancel(player, session);
            uiHooks.refreshSessionMenus(session);
            return;
        }

        TradeSession.DialogSubmitResult result = session.submitDialogMoney(side, dialogToken, parsed.amount());
        if (result == TradeSession.DialogSubmitResult.APPLIED) {
            countdownTicks.remove(tradeId);
            Msg.info(player, "제시 금액을 <#FFDF6B>" + OfferValidation.formatWon(parsed.amount()) + "<#AAAAAA>으로 설정했습니다.");
        }
        reopenTradeMenuOrCancel(player, session);
        uiHooks.refreshSessionMenus(session);
    }

    /** Paper Dialog 취소·ESC 응답 처리 (메인 스레드). */
    public void onDialogCancel(Player player, UUID tradeId, UUID dialogToken) {
        Optional<TradeSession> opt = sessions.byId(tradeId);
        if (opt.isEmpty()) {
            return;
        }
        TradeSession session = opt.get();
        TradeSession.Side side = session.sideOf(player.getUniqueId());
        if (side == null || !session.isInteractive()) {
            return;
        }
        if (session.cancelDialog(side, dialogToken)) {
            reopenTradeMenuOrCancel(player, session);
            uiHooks.refreshSessionMenus(session);
        }
    }

    private void checkDialogExpiration(TradeSession session, TradeSession.Side side, Player player, long nowMs) {
        if (!session.isDialogExpired(side, nowMs)) {
            return;
        }
        UUID token = session.participant(side).dialogToken();
        if (session.cancelDialog(side, token)) {
            uiHooks.closeDialog(player);
            Msg.warn(player, "금액 입력 시간이 만료되어 거래 창으로 돌아갑니다.");
            reopenTradeMenuOrCancel(player, session);
            uiHooks.refreshSessionMenus(session);
        }
    }

    private void reopenTradeMenuOrCancel(Player player, TradeSession session) {
        if (!player.isOnline() || !session.isInteractive()) {
            return;
        }
        if (!TradeInventoryHelper.isEmpty(player.getItemOnCursor())) {
            Msg.error(player, "손에 든 아이템이 있어 거래 창을 다시 열지 못하고 거래를 취소했습니다.");
            cancelPreSettlement(session, "커서 아이템 보유");
            return;
        }
        uiHooks.openTradeMenu(player, session);
    }

    // ------------------------------------------------------------------ 정산 (TRADE.md 5장 1~7단계)

    private void executeSettlement(TradeSession session, long expectedGen) {
        UUID tradeId = session.tradeId();
        UUID aId = session.sideA().uuid();
        UUID bId = session.sideB().uuid();
        if (!inFlight.add(aId)) {
            session.revertSettlingToOpen();
            return;
        }
        if (!inFlight.add(bId)) {
            inFlight.remove(aId);
            session.revertSettlingToOpen();
            return;
        }
        try {
            if (!session.beginSettling(expectedGen)) {
                return;
            }
            Player pA = server.getPlayer(aId);
            Player pB = server.getPlayer(bId);
            long nowMs = System.currentTimeMillis();

            // [1단계] 재검증 (TRADE.md 5장 1번)
            if (pA == null || !pA.isOnline() || pB == null || !pB.isOnline()) {
                abortSettling(session, "상대방 접속 종료");
                return;
            }
            if (pA.isDead() || pB.isDead()) {
                abortSettling(session, "사망");
                return;
            }
            if (isGloballyLocked()) {
                session.revertSettlingToOpen();
                notifyBoth(pA, pB, "<#FF5555>거래 기능이 현재 잠겨 있어 정산할 수 없습니다.");
                uiHooks.refreshSessionMenus(session);
                return;
            }
            if (hasOpenJournal(aId) || hasOpenJournal(bId)
                    || saveFailedLockedPlayers.contains(aId) || saveFailedLockedPlayers.contains(bId)) {
                abortSettling(session, "이전 정산 확인 대기");
                return;
            }
            if (!isAllowedGameMode(pA.getGameMode()) || !isAllowedGameMode(pB.getGameMode())) {
                abortSettling(session, "크리에이티브·관전 모드");
                return;
            }
            if (travel.inCombat(pA, nowMs) || travel.inCombat(pB, nowMs)) {
                abortSettling(session, "전투 상태");
                return;
            }
            if (session.sideA().inDialog() || session.sideB().inDialog()) {
                session.revertSettlingToOpen();
                uiHooks.refreshSessionMenus(session);
                return;
            }
            if (!TradeInventoryHelper.isEmpty(pA.getItemOnCursor()) || !TradeInventoryHelper.isEmpty(pB.getItemOnCursor())) {
                session.revertSettlingToOpen();
                notifyBoth(pA, pB, "<#FFDF6B>손에 든 아이템을 내려놓은 뒤 다시 준비해 주세요.");
                uiHooks.refreshSessionMenus(session);
                return;
            }
            if (OfferValidation.isCompletelyEmpty(
                    session.sideA().offerCount(), session.sideA().money(),
                    session.sideB().offerCount(), session.sideB().money())) {
                session.revertSettlingToOpen();
                notifyBoth(pA, pB, "<#FFDF6B>양쪽 모두 제시한 아이템과 금액이 없습니다.");
                uiHooks.refreshSessionMenus(session);
                return;
            }

            // 제시 칸 최종 스냅샷·수량 검증 (TRADE.md 3·5·14장)
            Map<Integer, ItemStack> snapsA = snapshotsFor(tradeId, TradeSession.Side.A);
            Map<Integer, ItemStack> snapsB = snapshotsFor(tradeId, TradeSession.Side.B);
            var checkA = OfferValidation.validateOffers(session.sideA().offers(),
                    TradeInventoryHelper.inspectOfferSlots(pA.getInventory(), session.sideA().offers(), snapsA));
            var checkB = OfferValidation.validateOffers(session.sideB().offers(),
                    TradeInventoryHelper.inspectOfferSlots(pB.getInventory(), session.sideB().offers(), snapsB));
            if (!checkA.valid() || !checkB.valid()) {
                session.revertSettlingToOpen();
                for (int slot : checkA.invalidSlots()) {
                    session.removeOfferSlot(TradeSession.Side.A, slot);
                    snapsA.remove(slot);
                }
                for (int slot : checkB.invalidSlots()) {
                    session.removeOfferSlot(TradeSession.Side.B, slot);
                    snapsB.remove(slot);
                }
                notifyBoth(pA, pB, "<#FFDF6B>제시된 아이템 상태가 바뀌어 준비가 해제되었습니다.");
                uiHooks.refreshSessionMenus(session);
                return;
            }

            // 잔액 및 최종 잔액 상한 검증 (잔액 부족·상한 초과는 거래 취소, TRADE.md 4·5장 1번)
            long aPays = session.sideA().money();
            long bPays = session.sideB().money();
            if (aPays > 0 || bPays > 0) {
                long balA = bank.balance(pA);
                long balB = bank.balance(pB);
                OfferValidation.BalanceCheck balCheckA = OfferValidation.checkSettlementBalance(balA, aPays, bPays);
                OfferValidation.BalanceCheck balCheckB = OfferValidation.checkSettlementBalance(balB, bPays, aPays);
                if (balCheckA == OfferValidation.BalanceCheck.INSUFFICIENT
                        || balCheckB == OfferValidation.BalanceCheck.INSUFFICIENT) {
                    abortSettling(session, "잔액 부족");
                    return;
                }
                if (balCheckA == OfferValidation.BalanceCheck.BALANCE_LIMIT
                        || balCheckB == OfferValidation.BalanceCheck.BALANCE_LIMIT) {
                    abortSettling(session, "잔액 한도 초과");
                    return;
                }
            }

            // [2단계 전] 실제 스택 직렬화 및 크기 제한 검증 — 제한 초과는 차감 전 정상 실패(준비 해제, TRADE.md 5장)
            List<byte[]> bytesFromA;
            List<byte[]> bytesFromB;
            try {
                bytesFromA = TradeInventoryHelper.serializeOfferedStacks(pA.getInventory(), session.sideA().offers());
                bytesFromB = TradeInventoryHelper.serializeOfferedStacks(pB.getInventory(), session.sideB().offers());
            } catch (RuntimeException e) {
                logger.warning("거래 아이템 직렬화 실패 (" + tradeId + "): " + e.getMessage());
                session.revertSettlingToOpen();
                notifyBoth(pA, pB, "<#FF5555>아이템 데이터가 너무 커서 거래할 수 없습니다.");
                uiHooks.refreshSessionMenus(session);
                return;
            }
            if (!Journal.fitsItemSizeLimits(bytesFromA, bytesFromB)) {
                session.revertSettlingToOpen();
                notifyBoth(pA, pB, "<#FF5555>아이템 데이터가 너무 커서 거래할 수 없습니다.");
                uiHooks.refreshSessionMenus(session);
                return;
            }

            // [2단계] 의도 기록 TAKING — 그 순간 실제 스택에서 직렬화해 fsync (TRADE.md 5장 2번)
            List<String> summaryA = TradeInventoryHelper.summarizeOfferedStacks(pA.getInventory(), session.sideA().offers());
            List<String> summaryB = TradeInventoryHelper.summarizeOfferedStacks(pB.getInventory(), session.sideB().offers());

            Journal.Entry entry = new Journal.Entry(
                    tradeId, nowMs, nowMs, Journal.Stage.TAKING,
                    aId, pA.getName(), bId, pB.getName(),
                    aPays, bPays,
                    bytesFromA, bytesFromB, summaryA, summaryB,
                    bytesFromA.isEmpty() ? Journal.SideStatus.FOUND : Journal.SideStatus.PENDING,
                    bytesFromB.isEmpty() ? Journal.SideStatus.FOUND : Journal.SideStatus.PENDING,
                    bytesFromB.isEmpty() ? Journal.SideStatus.FOUND : Journal.SideStatus.PENDING,
                    bytesFromA.isEmpty() ? Journal.SideStatus.FOUND : Journal.SideStatus.PENDING,
                    Journal.SideStatus.NONE, Journal.SideStatus.NONE,
                    1, 1, 1, 1,
                    0, 0,
                    0L, 0L,
                    "", false, ""
            );
            try {
                journalStore.write(entry);
            } catch (Journal.SizeLimitExceededException e) {
                session.revertSettlingToOpen();
                notifyBoth(pA, pB, "<#FF5555>아이템 데이터가 너무 커서 거래할 수 없습니다.");
                uiHooks.refreshSessionMenus(session);
                return;
            } catch (IOException e) {
                logger.severe("거래 저널 TAKING 쓰기 실패 (" + tradeId + "): " + e.getMessage());
                session.revertSettlingToOpen();
                notifyBoth(pA, pB, "<#FF5555>저장에 실패해 거래를 시작하지 못했습니다. 관리자에게 문의해 주세요.");
                uiHooks.refreshSessionMenus(session);
                return;
            }
            openJournals.put(tradeId, entry);

            // 여기부터는 저널이 생겼으므로 거래 창을 닫고 정산 3~7단계로 진행 (SETTLING 이므로 닫기 이벤트가 취소하지 않음)
            pA.closeInventory();
            pB.closeInventory();

            // [3단계] 가져오기 (양쪽 같은 틱, 실패 시 빼낸 아이템을 절대 돌려주지 않음, TRADE.md 5장 3번)
            Long connA = activeConnGen.get(aId);
            Long connB = activeConnGen.get(bId);

            if (!bytesFromA.isEmpty()) {
                entry = executeTakeForSide(entry, TradeSession.Side.A, pA, session.sideA().offers(), snapsA, nowMs);
                if (entry.takeA() == Journal.SideStatus.PENDING && connA != null) {
                    takingConnGenA.put(tradeId, connA);
                }
            }
            if (!bytesFromB.isEmpty()) {
                entry = executeTakeForSide(entry, TradeSession.Side.B, pB, session.sideB().offers(), snapsB, nowMs);
                if (entry.takeB() == Journal.SideStatus.PENDING && connB != null) {
                    takingConnGenB.put(tradeId, connB);
                }
            }
            persistJournalQuietly(entry);

            // 세션 메모리는 이제 저널로 넘어감
            cleanupSessionMemory(session);

            if (entry.takeA() == Journal.SideStatus.PENDING || entry.takeB() == Journal.SideStatus.PENDING) {
                notifyBoth(pA, pB, "<#FFDF6B>거래 저장 확인이 대기 중입니다. 잠시 후 자동으로 마무리됩니다.");
                return;
            }
            if (entry.takeA() == Journal.SideStatus.ABSENT || entry.takeB() == Journal.SideStatus.ABSENT) {
                // Bank settleTrade 호출 전이며 양쪽 모두 확정 상태(PENDING 없음) → 즉시 단방향 RETURNING
                RecoveryPolicy.TakingSideState stateA = entry.takeA() == Journal.SideStatus.FOUND
                        ? RecoveryPolicy.TakingSideState.FINAL_FOUND : RecoveryPolicy.TakingSideState.FINAL_ABSENT;
                RecoveryPolicy.TakingSideState stateB = entry.takeB() == Journal.SideStatus.FOUND
                        ? RecoveryPolicy.TakingSideState.FINAL_FOUND : RecoveryPolicy.TakingSideState.FINAL_ABSENT;
                transitionToReturningAndProcess(entry, stateA, stateB, "인벤토리 차감 실패", false);
                return;
            }

            // [4단계] 돈·확정 COMMITTED → [5단계] 지급 → [6단계] 완료 또는 [7단계] 취소
            proceedAfterBothTakesFound(entry, false);
        } finally {
            inFlight.remove(aId);
            inFlight.remove(bId);
        }
    }

    private Journal.Entry executeTakeForSide(Journal.Entry entry, TradeSession.Side side, Player player,
                                             Map<Integer, Integer> offers, Map<Integer, ItemStack> snapshots,
                                             long nowMs) {
        UUID tradeId = entry.tradeId();
        UUID uuid = player.getUniqueId();
        if (!TradeInventoryHelper.removeOfferedItems(player.getInventory(), offers, snapshots)) {
            // 1단계 직후 같은 틱이라 정상적이면 발생하지 않지만 방어적으로 확정 ABSENT 기록
            logger.warning("거래 3단계 인벤토리 차감 실패 (" + tradeId + ", " + player.getName() + ")");
            return entry.withTake(side, Journal.SideStatus.ABSENT, 0, nowMs);
        }

        PersistentDataContainer pdc = player.getPersistentDataContainer();
        String prevApplied = pdc.getOrDefault(keyApplied, PersistentDataType.STRING, "");
        String token = TradeTokens.takeToken(tradeId, uuid);
        String newApplied = TradeTokens.append(prevApplied, token);

        boolean saveOk = false;
        try {
            pdc.set(keyApplied, PersistentDataType.STRING, newApplied);
            player.saveData();
            saveOk = true;
        } catch (Throwable e) {
            logger.severe("거래 가져오기 saveData() 중 예외 (" + tradeId + ", " + player.getName() + "): " + e);
        }

        PlayerDatReader.MarkerCheck check = saveOk
                ? PlayerDatReader.check(worldRoot, uuid, TradeTokens.DAT_KEYS,
                        raw -> TradeTokens.containsTake(raw, tradeId, uuid))
                : new PlayerDatReader.MarkerCheck(PlayerDatReader.MarkerStatus.UNREADABLE, null, 0L, "saveData 예외");

        if (check.status() == PlayerDatReader.MarkerStatus.FOUND) {
            return entry.withTake(side, Journal.SideStatus.FOUND, 0, nowMs);
        }
        // TRADE.md 5장 3번: FOUND 가 아니어도 빼낸 아이템을 절대 돌려주지 않음! PENDING 유지
        return entry.withTake(side, Journal.SideStatus.PENDING, 0, nowMs);
    }

    /** 양쪽 take == FOUND 가 확인된 뒤 4단계(Bank 정산 / 0원 확정) → 5~7단계를 실행합니다. */
    private void proceedAfterBothTakesFound(Journal.Entry entry, boolean isRecovery) {
        long nowMs = System.currentTimeMillis();
        UUID tradeId = entry.tradeId();
        if (entry.hasMoney()) {
            String context = entry.nameA() + " <-> " + entry.nameB();
            BankBridge.SettleResult res = bank.settleTrade(
                    tradeId, entry.playerA(), entry.aPays(), entry.playerB(), entry.bPays(), context);
            switch (res.status()) {
                case OK -> {
                    entry = entry.withCommitted(res.txAId(), res.txBId(), isRecovery, nowMs);
                    if (!persistJournalQuietly(entry)) {
                        return;
                    }
                    deliverCommittedAndFinish(entry, true);
                }
                case REJECTED -> {
                    // TRADE.md 5장 4·7번: 잔액 부족·한도 초과 등 확정 거절 → 단방향 RETURNING
                    String reason = res.message().isEmpty() ? "정산 거절" : res.message();
                    transitionToReturningAndProcess(entry,
                            RecoveryPolicy.TakingSideState.FINAL_FOUND,
                            RecoveryPolicy.TakingSideState.FINAL_FOUND,
                            reason, isRecovery);
                }
                case MISMATCH -> {
                    entry = entry.withSealed("Bank MISMATCH: " + res.message(), nowMs);
                    persistJournalQuietly(entry);
                    logger.severe("거래 " + tradeId + " Bank 정산 MISMATCH 감지! 거래를 봉인했습니다: " + res.message());
                    notifyBothOnline(entry, "<#FF5555>거래 정산 기록 불일치가 감지되어 보류되었습니다. 관리자에게 문의해 주세요.");
                }
                case UNKNOWN -> {
                    logger.warning("거래 " + tradeId + " Bank settleTrade 결과 불확실(UNKNOWN): " + res.message());
                    notifyBothOnline(entry, "<#FFDF6B>결제 결과를 확인 중입니다. 1분 안에 자동으로 재확인합니다.");
                }
            }
        } else {
            // 0원 거래: 저널 COMMITTED 가 커밋 지점 (TRADE.md 5장 4번)
            entry = entry.withCommitted(0L, 0L, isRecovery, nowMs);
            if (!persistJournalQuietly(entry)) {
                return;
            }
            deliverCommittedAndFinish(entry, true);
        }
    }

    /**
     * [7단계] 확정 전 취소: 첫 반환 전에 반드시 저널을 단방향 {@code RETURNING} 으로 fsync 한 뒤
     * {@code take=FOUND} 였던 쪽에만 자기 물건을 돌려줍니다 (TRADE.md 5장 7번, 13-1장).
     */
    private void transitionToReturningAndProcess(
            Journal.Entry entry,
            RecoveryPolicy.TakingSideState finalA,
            RecoveryPolicy.TakingSideState finalB,
            String reason,
            boolean isRecovery
    ) {
        long nowMs = System.currentTimeMillis();
        Journal.SideStatus retA = RecoveryPolicy.initialReturnStatus(!entry.itemsFromA().isEmpty(), finalA);
        Journal.SideStatus retB = RecoveryPolicy.initialReturnStatus(!entry.itemsFromB().isEmpty(), finalB);
        Journal.Entry returning = entry.withReturning(retA, retB, reason, isRecovery, nowMs);
        if (!persistJournalQuietly(returning)) {
            logger.severe("거래 " + entry.tradeId() + " RETURNING 전환 fsync 실패 — 반환을 시작하지 않습니다.");
            return;
        }
        processReturningAndFinish(returning, true);
    }

    /** [5~6단계] COMMITTED 거래의 양쪽 지급 시도 및 완료 처리. */
    private void deliverCommittedAndFinish(Journal.Entry entry, boolean notifyOnline) {
        entry = attemptDeliveryForSide(entry, TradeSession.Side.A, false, notifyOnline);
        if (entry.isSealed()) {
            return;
        }
        entry = attemptDeliveryForSide(entry, TradeSession.Side.B, false, notifyOnline);
        if (entry.isSealed()) {
            return;
        }

        if (RecoveryPolicy.isCommittedComplete(entry.giveA(), entry.giveB())) {
            finishAndDeleteJournal(entry, "COMPLETED", "");
        }
    }

    /** [7단계] RETURNING 거래의 양쪽 반환 시도 및 취소 완료 처리. */
    private void processReturningAndFinish(Journal.Entry entry, boolean notifyOnline) {
        entry = attemptDeliveryForSide(entry, TradeSession.Side.A, true, notifyOnline);
        if (entry.isSealed()) {
            return;
        }
        entry = attemptDeliveryForSide(entry, TradeSession.Side.B, true, notifyOnline);
        if (entry.isSealed()) {
            return;
        }

        if (RecoveryPolicy.isReturningComplete(entry.returnA(), entry.returnB())) {
            finishAndDeleteJournal(entry, "CANCELLED", entry.cancelReason());
        }
    }

    /**
     * 한쪽의 지급({@code isReturn=false}) 또는 돌려주기({@code isReturn=true})를 같은 틱에서 시도합니다.
     *
     * <p>확인 실패 뒤 같은 틱 회수 시 디스크가 회수 전(지급됨) 상태일 수 있으므로 그 쪽을
     * {@link Journal.SideStatus#UNCERTAIN} 으로 두고, 같은 접속에서는 {@code saveData} → 디스크가
     * ABSENT 로 확정될 때까지 완료·재지급 모두 보류합니다. 디스크에 토큰이 FOUND 면 메모리(회수 상태)에
     * 다시 지급·저장해 디스크 기준으로 수렴하고, 회수 중 예외가 나면 해당 플레이어를 Trade 잠금 +
     * 거래 봉인 + severe 처리합니다 (P1-1, TRADE.md 5·6장).
     */
    private Journal.Entry attemptDeliveryForSide(Journal.Entry entry, TradeSession.Side side,
                                                 boolean isReturn, boolean notifyOnline) {
        UUID tradeId = entry.tradeId();
        UUID targetUuid = entry.uuidOf(side);
        List<byte[]> itemBytes = isReturn ? entry.ownItems(side) : entry.incomingItems(side);
        Journal.SideStatus currentStatus = isReturn ? entry.returnOf(side) : entry.giveOf(side);

        if (itemBytes.isEmpty() || currentStatus == Journal.SideStatus.FOUND
                || (isReturn && currentStatus == Journal.SideStatus.NONE)) {
            if (!isReturn && currentStatus != Journal.SideStatus.FOUND) {
                entry = entry.withGive(side, Journal.SideStatus.FOUND, entry.giveAttemptOf(side), false, System.currentTimeMillis());
                persistJournalQuietly(entry);
            }
            return entry;
        }

        Player online = server.getPlayer(targetUuid);
        boolean isOnline = online != null && online.isOnline();
        boolean isAlive = isOnline && !online.isDead();
        boolean isUncertain = currentStatus == Journal.SideStatus.UNCERTAIN;
        boolean sameUncertainSession = isUncertain && isSameUncertainSession(tradeId, side, isReturn, targetUuid);

        // 1) 사전 디스크 확인: 같은 접속의 UNCERTAIN 상태라면 saveData() 로 회수 상태 저장을 먼저 시도한 뒤 확인
        TradeTokens.Purpose purpose = isReturn ? TradeTokens.Purpose.RETURN : TradeTokens.Purpose.GIVE;
        String[] foundDiskToken = {null};
        PlayerDatReader.MarkerCheck preCheck;
        if (sameUncertainSession) {
            boolean preSaveOk = false;
            try {
                online.saveData();
                preSaveOk = true;
            } catch (Throwable e) {
                logger.severe("거래 불확실 지급/반환 확인 전 saveData() 예외 ("
                        + tradeId + ", " + online.getName() + "): " + e);
            }
            preCheck = preSaveOk
                    ? PlayerDatReader.check(worldRoot, targetUuid, TradeTokens.DAT_KEYS, raw -> {
                        String tok = TradeTokens.findAnyAttemptToken(raw, tradeId, purpose, targetUuid);
                        if (tok != null) {
                            foundDiskToken[0] = tok;
                            return true;
                        }
                        return false;
                    })
                    : new PlayerDatReader.MarkerCheck(PlayerDatReader.MarkerStatus.UNREADABLE, null, 0L, "saveData 예외");
        } else {
            preCheck = PlayerDatReader.check(worldRoot, targetUuid, TradeTokens.DAT_KEYS, raw -> {
                String tok = TradeTokens.findAnyAttemptToken(raw, tradeId, purpose, targetUuid);
                if (tok != null) {
                    foundDiskToken[0] = tok;
                    return true;
                }
                return false;
            });
        }

        boolean needsItemsCheck = isAlive && (preCheck.status() == PlayerDatReader.MarkerStatus.ABSENT
                || (sameUncertainSession && preCheck.status() == PlayerDatReader.MarkerStatus.FOUND));
        List<ItemStack> items = null;
        int missingSlots = 0;
        if (needsItemsCheck) {
            try {
                items = TradeInventoryHelper.deserializeStacks(itemBytes);
            } catch (RuntimeException e) {
                logger.severe("거래 " + tradeId + " 아이템 역직렬화 실패: " + e.getMessage());
                entry = entry.withSealed("아이템 역직렬화 실패: " + e.getMessage(), System.currentTimeMillis());
                openJournals.put(tradeId, entry);
                persistJournalQuietly(entry);
                return entry;
            }
            missingSlots = TradeInventoryHelper.missingSlots(online.getInventory(), Map.of(), items);
        }

        RecoveryPolicy.DeliveryDecision decision = isReturn
                ? RecoveryPolicy.decideReturnSide(!itemBytes.isEmpty(), currentStatus, sameUncertainSession,
                        preCheck.status(), isOnline, isAlive, missingSlots == 0)
                : RecoveryPolicy.decideGiveSide(!itemBytes.isEmpty(), currentStatus, sameUncertainSession,
                        preCheck.status(), isOnline, isAlive, missingSlots == 0);

        int attempt = isReturn ? entry.returnAttemptOf(side) : entry.giveAttemptOf(side);

        switch (decision) {
            case ALREADY_DONE -> {
                long nowMs = System.currentTimeMillis();
                clearUncertainConnGen(tradeId, side, isReturn);
                entry = isReturn
                        ? entry.withReturn(side, Journal.SideStatus.FOUND, attempt, true, nowMs)
                        : entry.withGive(side, Journal.SideStatus.FOUND, attempt, true, nowMs);
                persistJournalQuietly(entry);
                return entry;
            }
            case WAIT_INVENTORY_FULL -> {
                if (notifyOnline && isOnline) {
                    Msg.warn(online, "인벤토리 빈칸이 " + missingSlots + "칸 필요합니다. 빈칸을 비운 뒤 "
                            + "<#FFDF6B>/trade claim<#AAAAAA> 으로 받아 주세요.");
                }
                return entry;
            }
            case WAIT_DEAD, WAIT_OFFLINE, WAIT_UNREADABLE -> {
                return entry;
            }
            case CONVERGE_TO_DISK -> {
                // 같은 접속에서 회수 후 불확실인데 디스크에 토큰이 FOUND:
                // 메모리(회수 상태)와 디스크(지급 상태)가 다르므로 메모리에 다시 지급·저장해 디스크 기준으로 수렴
                String diskToken = foundDiskToken[0] != null
                        ? foundDiskToken[0]
                        : (isReturn
                                ? TradeTokens.returnToken(tradeId, targetUuid, attempt)
                                : TradeTokens.giveToken(tradeId, targetUuid, attempt));
                return convergeMemoryToDisk(entry, side, isReturn, online, items, attempt, diskToken, notifyOnline);
            }
            case CLEAR_UNCERTAIN -> {
                // 디스크에 이번(및 예전) 시도 토큰이 ABSENT 로 확정 → UNCERTAIN 해제 + 시도 번호 증가 후 PENDING 전환
                long nowMs = System.currentTimeMillis();
                int nextAttempt = attempt + 1;
                Journal.Entry cleared = isReturn
                        ? entry.withReturn(side, Journal.SideStatus.PENDING, nextAttempt, true, nowMs)
                        : entry.withGive(side, Journal.SideStatus.PENDING, nextAttempt, true, nowMs);
                if (!persistJournalQuietly(cleared)) {
                    return entry;
                }
                clearUncertainConnGen(tradeId, side, isReturn);
                entry = cleared;
                attempt = nextAttempt;
                RecoveryPolicy.DeliveryDecision nextDecision = isReturn
                        ? RecoveryPolicy.decideReturnSide(!itemBytes.isEmpty(), Journal.SideStatus.PENDING, false,
                                PlayerDatReader.MarkerStatus.ABSENT, isOnline, isAlive, missingSlots == 0)
                        : RecoveryPolicy.decideGiveSide(!itemBytes.isEmpty(), Journal.SideStatus.PENDING, false,
                                PlayerDatReader.MarkerStatus.ABSENT, isOnline, isAlive, missingSlots == 0);
                if (nextDecision == RecoveryPolicy.DeliveryDecision.WAIT_INVENTORY_FULL) {
                    if (notifyOnline && isOnline) {
                        Msg.warn(online, "인벤토리 빈칸이 " + missingSlots + "칸 필요합니다. 빈칸을 비운 뒤 "
                                + "<#FFDF6B>/trade claim<#AAAAAA> 으로 받아 주세요.");
                    }
                    return entry;
                }
                if (nextDecision != RecoveryPolicy.DeliveryDecision.DELIVER_NOW) {
                    return entry;
                }
                // DELIVER_NOW 로 진행 (아래에서 nextAttempt 로 지급 시도)
            }
            case DELIVER_NOW -> {
                // 아래에서 같은 틱 지급 + saveData + 디스크 확인 + 실패 시 같은 틱 회수 실행
            }
        }

        final int deliveryAttempt = attempt;
        String token = isReturn
                ? TradeTokens.returnToken(tradeId, targetUuid, deliveryAttempt)
                : TradeTokens.giveToken(tradeId, targetUuid, deliveryAttempt);

        ItemStack[] invSnapshot = TradeInventoryHelper.snapshotStorage(online.getInventory());
        PersistentDataContainer pdc = online.getPersistentDataContainer();
        String prevApplied = pdc.getOrDefault(keyApplied, PersistentDataType.STRING, "");
        String newApplied = TradeTokens.append(prevApplied, token);

        if (!TradeInventoryHelper.placeAllItems(online.getInventory(), items)) {
            if (notifyOnline) {
                Msg.warn(online, "인벤토리 빈칸이 부족합니다. 빈칸을 비운 뒤 <#FFDF6B>/trade claim<#AAAAAA> 으로 받아 주세요.");
            }
            return entry;
        }

        boolean saveOk = false;
        try {
            pdc.set(keyApplied, PersistentDataType.STRING, newApplied);
            online.saveData();
            saveOk = true;
        } catch (Throwable e) {
            logger.severe("거래 지급/반환 saveData() 중 예외 (" + tradeId + ", " + online.getName() + "): " + e);
        }

        PlayerDatReader.MarkerCheck postCheck = saveOk
                ? PlayerDatReader.check(worldRoot, targetUuid, TradeTokens.DAT_KEYS,
                        raw -> isReturn
                                ? TradeTokens.containsReturnExact(raw, tradeId, targetUuid, deliveryAttempt)
                                : TradeTokens.containsGiveExact(raw, tradeId, targetUuid, deliveryAttempt))
                : new PlayerDatReader.MarkerCheck(PlayerDatReader.MarkerStatus.UNREADABLE, null, 0L, "saveData 예외");

        long nowMs = System.currentTimeMillis();
        if (postCheck.status() == PlayerDatReader.MarkerStatus.FOUND) {
            clearUncertainConnGen(tradeId, side, isReturn);
            entry = isReturn
                    ? entry.withReturn(side, Journal.SideStatus.FOUND, deliveryAttempt, false, nowMs)
                    : entry.withGive(side, Journal.SideStatus.FOUND, deliveryAttempt, false, nowMs);
            persistJournalQuietly(entry);
            return entry;
        }

        // TRADE.md 5·6장 (P1-1): FOUND 가 아니면 같은 틱에 회수하고 UNCERTAIN 으로 전환.
        // 디스크가 회수 전(지급됨) 상태일 수 있으므로 ABSENT 확정 전까지 완료·재지급 모두 보류.
        if (!restoreInventoryAndPdc(online, invSnapshot, prevApplied)) {
            return lockAndSealOnRollbackFailure(entry, online, nowMs);
        }
        entry = isReturn
                ? entry.withReturn(side, Journal.SideStatus.UNCERTAIN, deliveryAttempt, false, nowMs)
                : entry.withGive(side, Journal.SideStatus.UNCERTAIN, deliveryAttempt, false, nowMs);
        recordUncertainConnGen(tradeId, side, isReturn, targetUuid);
        openJournals.put(tradeId, entry);
        persistJournalQuietly(entry);
        if (notifyOnline) {
            Msg.warn(online, "저장 확인이 지연되어 지급 상태를 재확인 중입니다. 잠시 후 자동 처리되거나 "
                    + "<#FFDF6B>/trade claim<#AAAAAA> 으로 확인할 수 있습니다.");
        }
        return entry;
    }

    /**
     * 같은 접속에서 회수 후 {@code UNCERTAIN} 상태인데 디스크에 토큰이 {@code FOUND} 인 경우,
     * 회수된 메모리에 아이템과 토큰을 다시 반영하고 {@code saveData()} 후 디스크를 재확인해 수렴합니다 (P1-1).
     */
    private Journal.Entry convergeMemoryToDisk(
            Journal.Entry entry,
            TradeSession.Side side,
            boolean isReturn,
            Player online,
            List<ItemStack> items,
            int attempt,
            String diskToken,
            boolean notifyOnline
    ) {
        UUID tradeId = entry.tradeId();
        UUID targetUuid = online.getUniqueId();
        TradeTokens.Purpose purpose = isReturn ? TradeTokens.Purpose.RETURN : TradeTokens.Purpose.GIVE;

        ItemStack[] invSnapshot = TradeInventoryHelper.snapshotStorage(online.getInventory());
        PersistentDataContainer pdc = online.getPersistentDataContainer();
        String prevApplied = pdc.getOrDefault(keyApplied, PersistentDataType.STRING, "");
        String newApplied = TradeTokens.append(prevApplied, diskToken);

        if (!TradeInventoryHelper.placeAllItems(online.getInventory(), items)) {
            if (notifyOnline) {
                Msg.warn(online, "인벤토리 빈칸이 부족합니다. 빈칸을 비운 뒤 <#FFDF6B>/trade claim<#AAAAAA> 으로 받아 주세요.");
            }
            return entry;
        }

        boolean saveOk = false;
        try {
            pdc.set(keyApplied, PersistentDataType.STRING, newApplied);
            online.saveData();
            saveOk = true;
        } catch (Throwable e) {
            logger.severe("거래 불확실 수렴 saveData() 중 예외 (" + tradeId + ", " + online.getName() + "): " + e);
        }

        PlayerDatReader.MarkerCheck postCheck = saveOk
                ? PlayerDatReader.check(worldRoot, targetUuid, TradeTokens.DAT_KEYS,
                        raw -> TradeTokens.containsAnyAttempt(raw, tradeId, purpose, targetUuid))
                : new PlayerDatReader.MarkerCheck(PlayerDatReader.MarkerStatus.UNREADABLE, null, 0L, "saveData 예외");

        long nowMs = System.currentTimeMillis();
        if (postCheck.status() == PlayerDatReader.MarkerStatus.FOUND) {
            clearUncertainConnGen(tradeId, side, isReturn);
            entry = isReturn
                    ? entry.withReturn(side, Journal.SideStatus.FOUND, attempt, true, nowMs)
                    : entry.withGive(side, Journal.SideStatus.FOUND, attempt, true, nowMs);
            persistJournalQuietly(entry);
            return entry;
        }

        if (!restoreInventoryAndPdc(online, invSnapshot, prevApplied)) {
            return lockAndSealOnRollbackFailure(entry, online, nowMs);
        }
        return entry;
    }

    private Journal.Entry lockAndSealOnRollbackFailure(Journal.Entry entry, Player online, long nowMs) {
        UUID tradeId = entry.tradeId();
        UUID targetUuid = online.getUniqueId();
        saveFailedLockedPlayers.add(targetUuid);
        Journal.Entry sealed = entry.withSealed("지급/반환 같은 틱 회수 실패: " + online.getName(), nowMs);
        openJournals.put(tradeId, sealed);
        persistJournalQuietly(sealed);
        logger.severe("거래 " + tradeId + " 지급/반환 같은 틱 회수 중 예외가 발생해 플레이어 "
                + online.getName() + " (" + targetUuid + ")의 Trade 를 잠그고 거래를 봉인했습니다. "
                + "재시도로 넘어가지 않습니다. 관리자가 인벤토리를 확인한 뒤 /trade resolve "
                + tradeId + " dismiss 로 정리하세요.");
        if (online.isOnline()) {
            Msg.error(online, "거래 물건 처리 중 오류가 발생해 거래 기능이 일시 잠겼습니다. 관리자에게 문의해 주세요.");
        }
        return sealed;
    }

    private boolean restoreInventoryAndPdc(Player player, ItemStack[] invSnapshot, String prevApplied) {
        try {
            player.getInventory().setStorageContents(invSnapshot);
            PersistentDataContainer pdc = player.getPersistentDataContainer();
            if (prevApplied == null || prevApplied.isEmpty()) {
                pdc.remove(keyApplied);
            } else {
                pdc.set(keyApplied, PersistentDataType.STRING, prevApplied);
            }
            return true;
        } catch (Throwable t) {
            logger.severe("거래 지급 같은 틱 회수 실패 (" + player.getName() + "): " + t);
            return false;
        }
    }

    // ------------------------------------------------------------------ 복구 (TRADE.md 6장)

    public void reconcileAllOpenJournals(boolean notifyOnline) {
        if (journalStore.isLocked()) {
            return;
        }
        for (UUID tradeId : new ArrayList<>(openJournals.keySet())) {
            Journal.Entry entry = openJournals.get(tradeId);
            if (entry != null) {
                reconcileEntry(entry, notifyOnline);
            }
        }
    }

    public String reconcileSingleByAdmin(UUID tradeId) {
        if (journalStore.isLocked()) {
            return "Trade 가 손상 저널 잠금 모드입니다. 먼저 /trade resolve unlock 을 확인하세요.";
        }
        Journal.Entry entry = openJournals.get(tradeId);
        if (entry == null) {
            return "열려 있는 거래 저널을 찾을 수 없습니다: " + tradeId;
        }
        if (entry.isSealed()) {
            return "봉인된 거래입니다 (" + entry.sealedReason() + "). 원장과 플레이어 인벤토리를 대조한 뒤 "
                    + "/trade resolve " + tradeId + " dismiss 로 봉인을 정리하세요.";
        }
        reconcileEntry(entry, true);
        Journal.Entry after = openJournals.get(tradeId);
        if (after == null) {
            return "거래 " + tradeId + " 복구가 완료되어 저널이 정리되었습니다.";
        }
        return "거래 " + tradeId + " 재확인 완료 (현재 단계=" + after.stage()
                + ", take=" + after.takeA() + "/" + after.takeB()
                + ", give=" + after.giveA() + "/" + after.giveB()
                + ", return=" + after.returnA() + "/" + after.returnB() + ").";
    }

    /** 관리자 수동 정리: 봉인된 저널 삭제 및 플레이어 저장 실패 잠금 해제. */
    public String dismissSealedJournal(UUID tradeId, String adminName) {
        Journal.Entry entry = openJournals.get(tradeId);
        if (entry == null) {
            return "열려 있는 거래 저널을 찾을 수 없습니다: " + tradeId;
        }
        if (!entry.isSealed() && !saveFailedLockedPlayers.contains(entry.playerA())
                && !saveFailedLockedPlayers.contains(entry.playerB())) {
            return "봉인·잠금 상태의 거래가 아닙니다. 먼저 /trade resolve " + tradeId + " 로 자동 복구를 시도하세요.";
        }
        finishAndDeleteJournal(entry, "ADMIN_RESOLVED", "admin=" + adminName + " " + entry.sealedReason());
        saveFailedLockedPlayers.remove(entry.playerA());
        saveFailedLockedPlayers.remove(entry.playerB());
        return "거래 " + tradeId + " 저널을 관리자(" + adminName + ")가 수동 정리하고 잠금을 해제했습니다.";
    }

    private void reconcileEntry(Journal.Entry entry, boolean notifyOnline) {
        UUID tradeId = entry.tradeId();
        UUID aId = entry.playerA();
        UUID bId = entry.playerB();
        if (entry.isSealed()) {
            return;
        }
        if (!inFlight.add(aId)) {
            return;
        }
        if (!inFlight.add(bId)) {
            inFlight.remove(aId);
            return;
        }
        try {
            // 최신 스냅샷 재확인
            entry = openJournals.get(tradeId);
            if (entry == null || entry.isSealed()) {
                return;
            }
            switch (entry.stage()) {
                case TAKING -> reconcileTaking(entry);
                case COMMITTED -> deliverCommittedAndFinish(entry.withRecovered(System.currentTimeMillis()), notifyOnline);
                case RETURNING -> processReturningAndFinish(entry.withRecovered(System.currentTimeMillis()), notifyOnline);
            }
        } finally {
            inFlight.remove(aId);
            inFlight.remove(bId);
        }
    }

    private void reconcileTaking(Journal.Entry entry) {
        long nowMs = System.currentTimeMillis();
        UUID tradeId = entry.tradeId();

        // 각 쪽을 독립적으로 평가 (TRADE.md 6·13-2장)
        RecoveryPolicy.TakingSideEvaluation evalA = evaluateTakingSideFromDisk(entry, TradeSession.Side.A);
        RecoveryPolicy.TakingSideEvaluation evalB = evaluateTakingSideFromDisk(entry, TradeSession.Side.B);

        Journal.Entry updated = applyTakingEvaluation(entry, TradeSession.Side.A, evalA, nowMs);
        updated = applyTakingEvaluation(updated, TradeSession.Side.B, evalB, nowMs);
        if (updated != entry) {
            entry = updated;
            persistJournalQuietly(entry);
        }

        RecoveryPolicy.BankLookupState bankState = RecoveryPolicy.BankLookupState.NOT_CHECKED;
        long foundTxA = 0L;
        long foundTxB = 0L;
        String bankDetail = "";

        RecoveryPolicy.TakingDecision decision = RecoveryPolicy.decideTaking(
                evalA.state(), evalB.state(), entry.hasMoney(), bankState);

        if (decision == RecoveryPolicy.TakingDecision.QUERY_BANK) {
            BankBridge.LookupResult lookup = bank.findTradeByExtRef(
                    tradeId, entry.playerA(), entry.aPays(), entry.playerB(), entry.bPays());
            bankState = lookup.state();
            foundTxA = lookup.txAId();
            foundTxB = lookup.txBId();
            bankDetail = lookup.detail();
            decision = RecoveryPolicy.decideTaking(evalA.state(), evalB.state(), entry.hasMoney(), bankState);
        }

        switch (decision) {
            case PROMOTE_TO_COMMITTED -> {
                entry = entry.withCommitted(foundTxA, foundTxB, true, nowMs);
                if (persistJournalQuietly(entry)) {
                    deliverCommittedAndFinish(entry, true);
                }
            }
            case SETTLE_BANK -> proceedAfterBothTakesFound(entry, true);
            case TRANSITION_TO_RETURNING -> transitionToReturningAndProcess(
                    entry, evalA.state(), evalB.state(), "가져오기 미반영으로 거래 취소", true);
            case SEAL_MISMATCH -> {
                entry = entry.withSealed("Bank 원장 불일치: " + bankDetail, nowMs);
                persistJournalQuietly(entry);
                logger.severe("복구 중 거래 " + tradeId + " Bank MISMATCH 감지 — 거래 봉인: " + bankDetail);
            }
            case DEFER, QUERY_BANK -> {
                // 결정 유예: 다른 쪽이 끝날 때까지 또는 읽기/조회 가능해질 때까지 기다림 (TRADE.md 6·13-2장)
            }
        }
    }

    private RecoveryPolicy.TakingSideEvaluation evaluateTakingSideFromDisk(Journal.Entry entry, TradeSession.Side side) {
        UUID tradeId = entry.tradeId();
        UUID uuid = entry.uuidOf(side);
        boolean hasItems = !entry.ownItems(side).isEmpty();
        Journal.SideStatus persisted = entry.takeOf(side);
        if (!hasItems || persisted == Journal.SideStatus.FOUND) {
            return RecoveryPolicy.evaluateTakingSide(hasItems, persisted, false,
                    PlayerDatReader.MarkerStatus.FOUND, entry.takeSaveRetriesOf(side));
        }

        boolean sameConn = isSameTakingSession(tradeId, side, uuid);
        Player online = server.getPlayer(uuid);
        if (sameConn && online != null && online.isOnline()) {
            // 같은 접속 세션에서 저장 확인 재시도: saveData() 먼저 호출
            try {
                online.saveData();
            } catch (Throwable e) {
                logger.severe("거래 복구 saveData() 재시도 예외 (" + tradeId + ", " + online.getName() + "): " + e);
                return RecoveryPolicy.evaluateTakingSide(true, persisted, true,
                        PlayerDatReader.MarkerStatus.UNREADABLE, entry.takeSaveRetriesOf(side));
            }
        }

        PlayerDatReader.MarkerCheck check = PlayerDatReader.check(worldRoot, uuid, TradeTokens.DAT_KEYS,
                raw -> TradeTokens.containsTake(raw, tradeId, uuid));
        return RecoveryPolicy.evaluateTakingSide(true, persisted, sameConn, check.status(), entry.takeSaveRetriesOf(side));
    }

    private Journal.Entry applyTakingEvaluation(Journal.Entry entry, TradeSession.Side side,
                                                RecoveryPolicy.TakingSideEvaluation eval, long nowMs) {
        UUID uuid = entry.uuidOf(side);
        if (eval.lockPlayer()) {
            if (saveFailedLockedPlayers.add(uuid)) {
                logger.severe("플레이어 " + entry.nameOf(side) + " (" + uuid + ")의 거래 " + entry.tradeId()
                        + " 저장 확인이 " + RecoveryPolicy.MAX_TAKE_SAVE_RETRIES
                        + "회 연속 실패(ABSENT)하여 해당 플레이어의 Trade 를 잠갔습니다. 관리자가 확인해 주세요.");
            }
        }
        Journal.SideStatus nextStatus = entry.takeOf(side);
        if (eval.updatePersistedToFound()) {
            nextStatus = Journal.SideStatus.FOUND;
            saveFailedLockedPlayers.remove(uuid);
        } else if (eval.updatePersistedToAbsent()) {
            nextStatus = Journal.SideStatus.ABSENT;
        }
        if (nextStatus != entry.takeOf(side) || eval.nextSaveRetries() != entry.takeSaveRetriesOf(side)) {
            return entry.withTake(side, nextStatus, eval.nextSaveRetries(), nowMs);
        }
        return entry;
    }

    private boolean isSameTakingSession(UUID tradeId, TradeSession.Side side, UUID playerId) {
        Player online = server.getPlayer(playerId);
        if (online == null || !online.isOnline()) {
            return false;
        }
        Long current = activeConnGen.get(playerId);
        Long recorded = side == TradeSession.Side.A ? takingConnGenA.get(tradeId) : takingConnGenB.get(tradeId);
        return current != null && current.equals(recorded);
    }

    private Map<UUID, Long> uncertainConnGenMap(TradeSession.Side side, boolean isReturn) {
        if (isReturn) {
            return side == TradeSession.Side.A ? returnUncertainConnGenA : returnUncertainConnGenB;
        }
        return side == TradeSession.Side.A ? giveUncertainConnGenA : giveUncertainConnGenB;
    }

    private boolean isSameUncertainSession(UUID tradeId, TradeSession.Side side, boolean isReturn, UUID playerId) {
        Player online = server.getPlayer(playerId);
        if (online == null || !online.isOnline()) {
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

    // ------------------------------------------------------------------ /trade claim (TRADE.md 8장)

    public void claimPending(Player player) {
        if (journalStore.isLocked()) {
            Msg.error(player, "Trade 가 잠금 모드입니다. 관리자에게 문의해 주세요.");
            return;
        }
        if (player.isDead()) {
            Msg.warn(player, "사망 중에는 거래 물건을 수령할 수 없습니다. 리스폰 후 다시 시도해 주세요.");
            return;
        }
        UUID uuid = player.getUniqueId();
        List<UUID> related = relatedJournalIds(uuid);
        if (related.isEmpty()) {
            Msg.info(player, "받을 거래 물건이 없습니다.");
            return;
        }

        int deliveredCount = 0;
        int remainingCount = 0;
        for (UUID tradeId : related) {
            Journal.Entry entry = openJournals.get(tradeId);
            if (entry == null) {
                continue;
            }
            if (entry.isSealed()) {
                Msg.error(player, "거래 " + tradeId + " 는 관리자 확인이 필요해 보류 중입니다.");
                remainingCount++;
                continue;
            }
            TradeSession.Side side = entry.sideOf(uuid);
            if (side == null) {
                continue;
            }
            if (entry.stage() == Journal.Stage.TAKING) {
                reconcileEntry(entry, true);
                entry = openJournals.get(tradeId);
                if (entry == null) {
                    deliveredCount++;
                    continue;
                }
            }
            if (entry.stage() == Journal.Stage.COMMITTED || entry.stage() == Journal.Stage.RETURNING) {
                boolean isReturn = entry.stage() == Journal.Stage.RETURNING;
                Journal.SideStatus before = isReturn ? entry.returnOf(side) : entry.giveOf(side);
                if (before == Journal.SideStatus.PENDING || before == Journal.SideStatus.UNCERTAIN) {
                    reconcileEntry(entry, true);
                    Journal.Entry after = openJournals.get(tradeId);
                    boolean doneNow = after == null
                            || (isReturn ? after.returnOf(side) == Journal.SideStatus.FOUND
                                         : after.giveOf(side) == Journal.SideStatus.FOUND);
                    if (doneNow) {
                        deliveredCount++;
                    } else {
                        remainingCount++;
                    }
                }
            }
        }

        if (deliveredCount > 0) {
            Msg.success(player, "대기 중이던 거래 물건 " + deliveredCount + "건을 받았습니다.");
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
        } else if (remainingCount == 0) {
            Msg.info(player, "받을 거래 물건이 없습니다.");
        }
    }

    // ------------------------------------------------------------------ 내부 헬퍼

    private void finishAndDeleteJournal(Journal.Entry entry, String result, String reason) {
        UUID tradeId = entry.tradeId();
        long nowMs = System.currentTimeMillis();
        try {
            journalStore.delete(tradeId);
        } catch (IOException e) {
            logger.severe("완료된 거래 저널 삭제 실패 (" + tradeId + "): " + e.getMessage());
            return;
        }
        openJournals.remove(tradeId);
        takingConnGenA.remove(tradeId);
        takingConnGenB.remove(tradeId);
        giveUncertainConnGenA.remove(tradeId);
        giveUncertainConnGenB.remove(tradeId);
        returnUncertainConnGenA.remove(tradeId);
        returnUncertainConnGenB.remove(tradeId);
        tradeLogger.log(entry, result, reason, nowMs, config.zone());

        Player pA = server.getPlayer(entry.playerA());
        Player pB = server.getPlayer(entry.playerB());
        if ("COMPLETED".equals(result)) {
            if (pA != null && pA.isOnline()) {
                Msg.raw(pA, "<#55FF55>[거래] 완료: <#AAAAAA>받은 것 <white>"
                        + formatSideSummary(entry.summaryFromB(), entry.bPays())
                        + "<#AAAAAA>, 보낸 것 <white>"
                        + formatSideSummary(entry.summaryFromA(), entry.aPays()));
                pA.playSound(pA.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
            }
            if (pB != null && pB.isOnline()) {
                Msg.raw(pB, "<#55FF55>[거래] 완료: <#AAAAAA>받은 것 <white>"
                        + formatSideSummary(entry.summaryFromA(), entry.aPays())
                        + "<#AAAAAA>, 보낸 것 <white>"
                        + formatSideSummary(entry.summaryFromB(), entry.bPays()));
                pB.playSound(pB.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
            }
        } else if ("CANCELLED".equals(result)) {
            String msg = "<#FF5555>[거래] 취소됨 (" + (reason == null || reason.isEmpty() ? "취소" : reason) + ")";
            if (pA != null && pA.isOnline()) {
                Msg.raw(pA, msg);
            }
            if (pB != null && pB.isOnline()) {
                Msg.raw(pB, msg);
            }
        }
    }

    private static String formatSideSummary(List<String> items, long money) {
        List<String> parts = new ArrayList<>();
        if (items != null && !items.isEmpty()) {
            if (items.size() <= 3) {
                parts.add(String.join(", ", items));
            } else {
                parts.add(items.get(0) + ", " + items.get(1) + " 외 " + (items.size() - 2) + "종");
            }
        }
        if (money > 0) {
            parts.add(OfferValidation.formatWon(money));
        }
        return parts.isEmpty() ? "없음" : String.join(" + ", parts);
    }

    private void cancelPreSettlement(TradeSession session, String reason) {
        if (!session.cancelIfPreSettlement(reason)) {
            return;
        }
        boolean wasOpen = offerSnapshots.containsKey(session.tradeId());
        cleanupSessionMemory(session);

        Player pA = server.getPlayer(session.sideA().uuid());
        Player pB = server.getPlayer(session.sideB().uuid());
        if (pA != null && pA.isOnline()) {
            uiHooks.closeDialog(pA);
            if (wasOpen) {
                uiHooks.closeTradeMenuNextTick(pA);
            }
            Msg.raw(pA, "<#FF5555>[거래] 취소됨 (" + reason + ")");
        }
        if (pB != null && pB.isOnline()) {
            uiHooks.closeDialog(pB);
            if (wasOpen) {
                uiHooks.closeTradeMenuNextTick(pB);
            }
            Msg.raw(pB, "<#FF5555>[거래] 취소됨 (" + reason + ")");
        }
    }

    private void abortSettling(TradeSession session, String reason) {
        session.cancelFromSettling(reason);
        cleanupSessionMemory(session);
        Player pA = server.getPlayer(session.sideA().uuid());
        Player pB = server.getPlayer(session.sideB().uuid());
        if (pA != null && pA.isOnline()) {
            uiHooks.closeTradeMenuNextTick(pA);
            Msg.raw(pA, "<#FF5555>[거래] 취소됨 (" + reason + ")");
        }
        if (pB != null && pB.isOnline()) {
            uiHooks.closeTradeMenuNextTick(pB);
            Msg.raw(pB, "<#FF5555>[거래] 취소됨 (" + reason + ")");
        }
    }

    private void cleanupSessionMemory(TradeSession session) {
        sessions.remove(session);
        offerSnapshots.remove(session.tradeId());
        countdownTicks.remove(session.tradeId());
    }

    private boolean revalidateOfferedSlots(TradeSession session, TradeSession.Side side, Player player) {
        Map<Integer, Integer> offers = session.participant(side).offers();
        if (offers.isEmpty()) {
            return false;
        }
        Map<Integer, ItemStack> snaps = snapshotsFor(session.tradeId(), side);
        Map<Integer, OfferValidation.SlotState> current =
                TradeInventoryHelper.inspectOfferSlots(player.getInventory(), offers, snaps);
        OfferValidation.OfferCheckResult check = OfferValidation.validateOffers(offers, current);
        if (check.valid()) {
            return false;
        }
        for (int slot : check.invalidSlots()) {
            session.removeOfferSlot(side, slot);
            snaps.remove(slot);
        }
        Player other = server.getPlayer(session.participant(side.other()).uuid());
        Msg.warn(player, "인벤토리의 제시 아이템이 변경되어 해당 제시가 취소되고 준비가 해제되었습니다.");
        if (other != null && other.isOnline()) {
            Msg.warn(other, player.getName() + "님의 제시 아이템이 변경되어 준비가 해제되었습니다.");
        }
        return true;
    }

    private List<ItemStack> buildExpectedIncoming(Player partner, Map<Integer, Integer> partnerOffers) {
        List<ItemStack> out = new ArrayList<>(partnerOffers.size());
        ItemStack[] storage = partner.getInventory().getStorageContents();
        for (Map.Entry<Integer, Integer> e : partnerOffers.entrySet()) {
            int slot = e.getKey();
            if (slot >= 0 && slot < storage.length && !TradeInventoryHelper.isEmpty(storage[slot])) {
                ItemStack copy = storage[slot].clone();
                copy.setAmount(Math.min(e.getValue(), copy.getAmount()));
                out.add(copy);
            }
        }
        return out;
    }

    public Map<Integer, ItemStack> snapshotsFor(UUID tradeId, TradeSession.Side side) {
        return offerSnapshots
                .computeIfAbsent(tradeId, k -> new HashMap<>())
                .computeIfAbsent(side, k -> new HashMap<>());
    }

    private boolean persistJournalQuietly(Journal.Entry entry) {
        try {
            journalStore.write(entry);
            openJournals.put(entry.tradeId(), entry);
            return true;
        } catch (IOException e) {
            logger.severe("거래 저널 쓰기 실패 (" + entry.tradeId() + "): " + e.getMessage());
            return false;
        }
    }

    private boolean ensureReadyForNewTrade(Player player) {
        if (journalStore.isLocked()) {
            Msg.error(player, "거래 기능이 잠금 모드입니다. 관리자에게 문의해 주세요.");
            return false;
        }
        if (!bank.available()) {
            Msg.error(player, "은행(Bank)과 연결되지 않아 거래할 수 없습니다.");
            return false;
        }
        UUID uuid = player.getUniqueId();
        if (saveFailedLockedPlayers.contains(uuid)) {
            Msg.error(player, "이전 거래 저장 확인 실패로 거래가 일시 잠겨 있습니다. 관리자에게 문의해 주세요.");
            return false;
        }
        if (hasOpenJournal(uuid)) {
            Msg.warn(player, "이전 거래 정산 또는 수령 대기가 남아 있어 새 거래를 시작할 수 없습니다. "
                    + "<#FFDF6B>/trade claim<#AAAAAA> 을 확인해 주세요.");
            return false;
        }
        return true;
    }

    private List<UUID> relatedJournalIds(UUID playerId) {
        List<UUID> ids = new ArrayList<>();
        for (Journal.Entry e : openJournals.values()) {
            if (e.involves(playerId)) {
                ids.add(e.tradeId());
            }
        }
        return ids;
    }

    private void notifyIfOnline(UUID uuid, String text) {
        Player p = server.getPlayer(uuid);
        if (p != null && p.isOnline()) {
            Msg.raw(p, Msg.PREFIX + text);
        }
    }

    private void notifyBoth(Player a, Player b, String text) {
        if (a != null && a.isOnline()) {
            Msg.raw(a, Msg.PREFIX + text);
        }
        if (b != null && b.isOnline()) {
            Msg.raw(b, Msg.PREFIX + text);
        }
    }

    private void notifyBothOnline(Journal.Entry entry, String text) {
        notifyIfOnline(entry.playerA(), text);
        notifyIfOnline(entry.playerB(), text);
    }
}
