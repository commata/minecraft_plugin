package kr.commata.trade.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 1:1 거래 세션의 순수 상태 기계와 세대 번호({@code gen}) 관리 (TRADE.md 2·3·4·7장).
 *
 * <p>규칙 요약:
 * <ul>
 *   <li>아이템·금액(제시 내용)이 바뀔 때만 {@code gen += 1} + 양쪽 준비 해제 + 카운트다운 취소.</li>
 *   <li>준비 토글은 {@code gen} 을 올리지 않습니다(해제하면 카운트다운만 취소).</li>
 *   <li>대화창 열림은 본인 준비 해제 + 카운트다운 취소만 하며, 응답 때 {@code (tradeId, dialogToken, gen)} 이
 *       모두 일치해야 반영합니다.</li>
 *   <li>{@code SETTLING} 진입 이후에는 사전 취소({@link #cancelIfPreSettlement})가 동작하지 않습니다.</li>
 * </ul>
 */
public final class TradeSession {

    public enum State {
        REQUESTED,
        OPEN,
        COUNTDOWN,
        SETTLING,
        COMMITTED,
        COMPLETED,
        CANCELLED
    }

    public enum Side {
        A, B;

        public Side other() {
            return this == A ? B : A;
        }
    }

    /** 한쪽의 제시·준비·대화창 상태 */
    public static final class Participant {
        private final UUID uuid;
        private final String name;
        /** 보관함 슬롯 번호(0..35) → 제시 개수(1 이상), 최대 16칸 */
        private final LinkedHashMap<Integer, Integer> offers = new LinkedHashMap<>();
        private long money;
        private boolean ready;
        private boolean inDialog;
        private UUID expectedDialogCloseMenuId;
        private UUID dialogToken;
        private long dialogGen;
        private long dialogExpiresAtMs;

        private Participant(UUID uuid, String name) {
            this.uuid = Objects.requireNonNull(uuid, "uuid");
            this.name = Objects.requireNonNull(name, "name");
        }

        public UUID uuid() {
            return uuid;
        }

        public String name() {
            return name;
        }

        public Map<Integer, Integer> offers() {
            return Collections.unmodifiableMap(offers);
        }

        public List<Map.Entry<Integer, Integer>> offerList() {
            return new ArrayList<>(offers.entrySet());
        }

        public int offerCount() {
            return offers.size();
        }

        public int offeredAmount(int invSlot) {
            return offers.getOrDefault(invSlot, 0);
        }

        public long money() {
            return money;
        }

        public boolean ready() {
            return ready;
        }

        public boolean inDialog() {
            return inDialog;
        }

        public boolean expectDialogClose() {
            return expectedDialogCloseMenuId != null;
        }

        public UUID expectedDialogCloseMenuId() {
            return expectedDialogCloseMenuId;
        }

        public UUID dialogToken() {
            return dialogToken;
        }

        public long dialogGen() {
            return dialogGen;
        }

        public long dialogExpiresAtMs() {
            return dialogExpiresAtMs;
        }
    }

    public enum OfferChangeResult {
        CHANGED,
        NO_CHANGE,
        MAX_SLOTS_REACHED,
        INVALID_STATE,
        INVALID_ARGUMENT
    }

    public enum DialogSubmitResult {
        APPLIED,
        NO_CHANGE,
        STALE_GEN,
        EXPIRED,
        IGNORED_NOT_ACTIVE
    }

    private final UUID tradeId;
    private final Participant sideA;
    private final Participant sideB;
    private final long requestedAtMs;
    private final long requestExpiresAtMs;
    private final int countdownSeconds;

    private State state = State.REQUESTED;
    private long gen = 0L;
    private int countdownRemaining;
    private long countdownGen = -1L;
    private String cancelReason = "";

    public TradeSession(UUID tradeId, UUID requesterId, String requesterName,
                        UUID targetId, String targetName,
                        long nowMs, long requestTimeoutMs, int countdownSeconds) {
        this.tradeId = Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(requesterId, "requesterId");
        Objects.requireNonNull(targetId, "targetId");
        if (requesterId.equals(targetId)) {
            throw new IllegalArgumentException("자기 자신과는 거래할 수 없습니다.");
        }
        if (countdownSeconds < 1) {
            throw new IllegalArgumentException("카운트다운은 1초 이상이어야 합니다: " + countdownSeconds);
        }
        this.sideA = new Participant(requesterId, requesterName);
        this.sideB = new Participant(targetId, targetName);
        this.requestedAtMs = nowMs;
        this.requestExpiresAtMs = nowMs + Math.max(1000L, requestTimeoutMs);
        this.countdownSeconds = countdownSeconds;
    }

    public UUID tradeId() {
        return tradeId;
    }

    public Participant sideA() {
        return sideA;
    }

    public Participant sideB() {
        return sideB;
    }

    public Participant participant(Side side) {
        return side == Side.A ? sideA : sideB;
    }

    public Side sideOf(UUID uuid) {
        if (sideA.uuid.equals(uuid)) {
            return Side.A;
        }
        if (sideB.uuid.equals(uuid)) {
            return Side.B;
        }
        return null;
    }

    public Participant partnerOf(UUID uuid) {
        Side s = sideOf(uuid);
        return s == null ? null : participant(s.other());
    }

    public State state() {
        return state;
    }

    public long gen() {
        return gen;
    }

    public long requestedAtMs() {
        return requestedAtMs;
    }

    public long requestExpiresAtMs() {
        return requestExpiresAtMs;
    }

    public long requestRemainingMs(long nowMs) {
        return Math.max(0L, requestExpiresAtMs - nowMs);
    }

    public boolean isRequestExpired(long nowMs) {
        return state == State.REQUESTED && nowMs >= requestExpiresAtMs;
    }

    public int countdownRemaining() {
        return countdownRemaining;
    }

    public long countdownGen() {
        return countdownGen;
    }

    public String cancelReason() {
        return cancelReason;
    }

    public boolean isPreSettlement() {
        return state == State.REQUESTED || state == State.OPEN || state == State.COUNTDOWN;
    }

    public boolean isInteractive() {
        return state == State.OPEN || state == State.COUNTDOWN;
    }

    public boolean isTerminal() {
        return state == State.COMPLETED || state == State.CANCELLED;
    }

    /** 요청 수락: {@code REQUESTED → OPEN}. */
    public boolean accept() {
        if (state != State.REQUESTED) {
            return false;
        }
        state = State.OPEN;
        return true;
    }

    /**
     * 아래 인벤토리 칸의 제시 수량을 지정합니다(좌클릭 = 그 칸 전부, 우클릭 = +1).
     * 내용이 실제로 바뀌면 {@code gen += 1}, 양쪽 준비 해제, 카운트다운 취소.
     */
    public OfferChangeResult setOfferAmount(Side side, int invSlot, int newAmount, int slotMaxAvailable) {
        if (!isInteractive()) {
            return OfferChangeResult.INVALID_STATE;
        }
        if (invSlot < 0 || invSlot >= OfferValidation.STORAGE_SIZE || slotMaxAvailable <= 0) {
            return OfferChangeResult.INVALID_ARGUMENT;
        }
        Participant p = participant(side);
        if (p.inDialog) {
            return OfferChangeResult.INVALID_STATE;
        }
        int clamped = Math.min(newAmount, slotMaxAvailable);
        if (clamped <= 0) {
            return removeOfferSlot(side, invSlot);
        }
        Integer existing = p.offers.get(invSlot);
        if (existing == null && p.offers.size() >= OfferValidation.MAX_OFFER_SLOTS) {
            return OfferChangeResult.MAX_SLOTS_REACHED;
        }
        if (existing != null && existing == clamped) {
            return OfferChangeResult.NO_CHANGE;
        }
        p.offers.put(invSlot, clamped);
        onOfferMutated();
        return OfferChangeResult.CHANGED;
    }

    /** 아래 인벤토리 칸 우클릭: 현재 제시 수량에 1 추가(최대 그 칸 보유량까지). */
    public OfferChangeResult addOneOffer(Side side, int invSlot, int slotMaxAvailable) {
        if (!isInteractive()) {
            return OfferChangeResult.INVALID_STATE;
        }
        Participant p = participant(side);
        int current = p.offers.getOrDefault(invSlot, 0);
        if (current >= slotMaxAvailable) {
            return OfferChangeResult.NO_CHANGE;
        }
        return setOfferAmount(side, invSlot, current + 1, slotMaxAvailable);
    }

    /** 위 제시 칸 인덱스(0..15) 좌클릭: 해당 제시 제거. */
    public OfferChangeResult removeOfferByIndex(Side side, int offerIndex) {
        Integer invSlot = invSlotAtOfferIndex(side, offerIndex);
        if (invSlot == null) {
            return OfferChangeResult.NO_CHANGE;
        }
        return removeOfferSlot(side, invSlot);
    }

    /** 위 제시 칸 인덱스(0..15) 우클릭: 1개 빼기(0이 되면 제거). */
    public OfferChangeResult removeOneOfferByIndex(Side side, int offerIndex) {
        if (!isInteractive()) {
            return OfferChangeResult.INVALID_STATE;
        }
        Participant p = participant(side);
        if (p.inDialog) {
            return OfferChangeResult.INVALID_STATE;
        }
        Integer invSlot = invSlotAtOfferIndex(side, offerIndex);
        if (invSlot == null) {
            return OfferChangeResult.NO_CHANGE;
        }
        int current = p.offers.getOrDefault(invSlot, 0);
        if (current <= 1) {
            return removeOfferSlot(side, invSlot);
        }
        p.offers.put(invSlot, current - 1);
        onOfferMutated();
        return OfferChangeResult.CHANGED;
    }

    /** 특정 보관함 칸의 제시를 제거합니다(인벤토리 변화 감지 시 호출). */
    public OfferChangeResult removeOfferSlot(Side side, int invSlot) {
        if (!isInteractive()) {
            return OfferChangeResult.INVALID_STATE;
        }
        Participant p = participant(side);
        if (p.offers.remove(invSlot) == null) {
            return OfferChangeResult.NO_CHANGE;
        }
        onOfferMutated();
        return OfferChangeResult.CHANGED;
    }

    /** 제시 목록에서 0..15번째 항목의 보관함 슬롯 번호. 없으면 {@code null}. */
    public Integer invSlotAtOfferIndex(Side side, int offerIndex) {
        Participant p = participant(side);
        if (offerIndex < 0 || offerIndex >= p.offers.size()) {
            return null;
        }
        int i = 0;
        for (Integer invSlot : p.offers.keySet()) {
            if (i == offerIndex) {
                return invSlot;
            }
            i++;
        }
        return null;
    }

    /**
     * 준비 토글 (TRADE.md 2장): {@code gen} 을 올리지 않습니다.
     * 양쪽 모두 준비되고 양쪽 다 대화창이 닫혀 있으면 {@code COUNTDOWN} 으로 전환합니다.
     */
    public boolean toggleReady(Side side) {
        if (!isInteractive()) {
            return false;
        }
        Participant p = participant(side);
        if (p.inDialog) {
            return false;
        }
        p.ready = !p.ready;
        if (p.ready) {
            if (sideA.ready && sideB.ready && !sideA.inDialog && !sideB.inDialog) {
                state = State.COUNTDOWN;
                countdownRemaining = countdownSeconds;
                countdownGen = gen;
            }
        } else if (state == State.COUNTDOWN) {
            state = State.OPEN;
            countdownRemaining = 0;
            countdownGen = -1L;
        }
        return true;
    }

    /**
     * 금액 입력 대화창 열기 준비 (TRADE.md 7장, P3-10):
     * 본인 준비 해제 + 카운트다운 취소 + {@code (dialogToken, targetMenuId, dialogGen, dialogExpiresAtMs)} 등록.
     * {@code gen} 은 유지합니다.
     *
     * @param targetMenuId 대화창으로 전환되며 닫힐 거래 창의 고유 ID (이 창의 단일 닫기만 예외)
     * @return 등록된 일회성 {@code dialogToken}, 열 수 없는 상태면 {@code null}
     */
    public UUID beginDialog(Side side, UUID dialogToken, UUID targetMenuId, long nowMs, long timeoutMs) {
        if (!isInteractive()) {
            return null;
        }
        Participant p = participant(side);
        if (p.inDialog) {
            return null;
        }
        p.ready = false;
        if (state == State.COUNTDOWN) {
            state = State.OPEN;
            countdownRemaining = 0;
            countdownGen = -1L;
        }
        p.inDialog = true;
        p.expectedDialogCloseMenuId = Objects.requireNonNull(targetMenuId, "targetMenuId");
        p.dialogToken = Objects.requireNonNull(dialogToken, "dialogToken");
        p.dialogGen = gen;
        p.dialogExpiresAtMs = nowMs + Math.max(1000L, timeoutMs);
        return dialogToken;
    }

    /**
     * 대화창 전환 대상 창({@code closedMenuId})의 닫기 예외 1회를 소비합니다 (TRADE.md 7장, P3-10).
     * 전환 대상 창이 일치할 때만 딱 1회 {@code true} 를 반환하고 예외를 소진합니다.
     */
    public boolean consumeExpectedDialogClose(Side side, UUID closedMenuId) {
        Participant p = participant(side);
        if (closedMenuId != null && closedMenuId.equals(p.expectedDialogCloseMenuId)) {
            p.expectedDialogCloseMenuId = null;
            return true;
        }
        return false;
    }

    /**
     * 대화창 응답 처리: 절대 만료 시각({@code nowMs < dialogExpiresAtMs})과
     * {@code (dialogToken, dialogGen)} 이 모두 유효할 때만 금액을 반영합니다 (TRADE.md 2·7장, P3-10).
     */
    public DialogSubmitResult submitDialogMoney(Side side, UUID token, long newMoney, long nowMs) {
        Participant p = participant(side);
        if (!isInteractive() || !p.inDialog || !Objects.equals(p.dialogToken, token)) {
            return DialogSubmitResult.IGNORED_NOT_ACTIVE;
        }
        long capturedGen = p.dialogGen;
        long expiresAt = p.dialogExpiresAtMs;
        clearDialogFields(p);
        if (nowMs >= expiresAt) {
            return DialogSubmitResult.EXPIRED;
        }
        if (capturedGen != gen) {
            return DialogSubmitResult.STALE_GEN;
        }
        if (newMoney < 0 || newMoney > OfferValidation.MAX_AMOUNT) {
            return DialogSubmitResult.IGNORED_NOT_ACTIVE;
        }
        if (p.money == newMoney) {
            return DialogSubmitResult.NO_CHANGE;
        }
        p.money = newMoney;
        onOfferMutated();
        return DialogSubmitResult.APPLIED;
    }

    /** 대화창 취소·ESC·만료 시 대화창 상태만 닫습니다. */
    public boolean cancelDialog(Side side, UUID token) {
        Participant p = participant(side);
        if (!p.inDialog || !Objects.equals(p.dialogToken, token)) {
            return false;
        }
        clearDialogFields(p);
        return true;
    }

    /** 대화창 만료 여부 */
    public boolean isDialogExpired(Side side, long nowMs) {
        Participant p = participant(side);
        return p.inDialog && nowMs >= p.dialogExpiresAtMs;
    }

    /**
     * 카운트다운 1초 진행. {@code expectedGen} 이 현재 {@code gen} 과 다르거나 상태가 {@code COUNTDOWN} 이 아니면 무시합니다.
     *
     * @return 카운트다운이 0에 도달해 정산을 시작할 시점이면 {@code true}
     */
    public boolean tickCountdownSecond(long expectedGen) {
        if (state != State.COUNTDOWN || gen != expectedGen || countdownGen != expectedGen) {
            return false;
        }
        if (!sideA.ready || !sideB.ready || sideA.inDialog || sideB.inDialog) {
            state = State.OPEN;
            countdownRemaining = 0;
            countdownGen = -1L;
            return false;
        }
        countdownRemaining--;
        return countdownRemaining <= 0;
    }

    /** 카운트다운 완료 → {@code SETTLING} 진입. 조건이 맞지 않으면 {@code false}. */
    public boolean beginSettling(long expectedGen) {
        if (state != State.COUNTDOWN || gen != expectedGen || countdownGen != expectedGen) {
            return false;
        }
        if (!sideA.ready || !sideB.ready || sideA.inDialog || sideB.inDialog) {
            return false;
        }
        state = State.SETTLING;
        countdownRemaining = 0;
        countdownGen = -1L;
        return true;
    }

    /** 1단계 재검증 실패·2단계 저널 쓰기 실패 시 {@code SETTLING → OPEN} 복귀 (양쪽 준비 해제). */
    public boolean revertSettlingToOpen() {
        if (state != State.SETTLING) {
            return false;
        }
        state = State.OPEN;
        sideA.ready = false;
        sideB.ready = false;
        return true;
    }

    /** 4단계 확정: {@code SETTLING → COMMITTED}. */
    public boolean markCommitted() {
        if (state != State.SETTLING) {
            return false;
        }
        state = State.COMMITTED;
        return true;
    }

    /** 6단계 완료: {@code COMMITTED → COMPLETED}. */
    public boolean markCompleted() {
        if (state != State.COMMITTED) {
            return false;
        }
        state = State.COMPLETED;
        return true;
    }

    /**
     * {@code REQUESTED / OPEN / COUNTDOWN} 상태에서만 즉시 {@code CANCELLED} 로 전이합니다 (TRADE.md 2장).
     * {@code SETTLING} 이후에는 무시({@code false})합니다.
     */
    public boolean cancelIfPreSettlement(String reason) {
        if (!isPreSettlement()) {
            return false;
        }
        state = State.CANCELLED;
        cancelReason = reason == null ? "" : reason;
        clearDialogFields(sideA);
        clearDialogFields(sideB);
        countdownRemaining = 0;
        countdownGen = -1L;
        return true;
    }

    /** 정산 1단계 잔액 부족·7단계 취소로 인한 {@code SETTLING → CANCELLED}. */
    public boolean cancelFromSettling(String reason) {
        if (state != State.SETTLING) {
            return false;
        }
        state = State.CANCELLED;
        cancelReason = reason == null ? "" : reason;
        return true;
    }

    private void onOfferMutated() {
        gen++;
        sideA.ready = false;
        sideB.ready = false;
        if (state == State.COUNTDOWN) {
            state = State.OPEN;
            countdownRemaining = 0;
            countdownGen = -1L;
        }
    }

    private static void clearDialogFields(Participant p) {
        p.inDialog = false;
        p.expectedDialogCloseMenuId = null;
        p.dialogToken = null;
        p.dialogGen = -1L;
        p.dialogExpiresAtMs = 0L;
    }
}
