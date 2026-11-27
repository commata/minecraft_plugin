package kr.commata.trade.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class TradeSessionTest {

    private static final UUID A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID C = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private TradeSession newOpenSession() {
        TradeSession s = new TradeSession(UUID.randomUUID(), A, "Alice", B, "Bob", 1_000L, 30_000L, 3);
        assertTrue(s.accept());
        assertEquals(TradeSession.State.OPEN, s.state());
        return s;
    }

    @Test
    void readyToggleDoesNotIncrementGenAndStartsCountdownWhenBothReady() {
        TradeSession s = newOpenSession();
        long initialGen = s.gen();

        assertTrue(s.toggleReady(TradeSession.Side.A));
        assertEquals(initialGen, s.gen());
        assertTrue(s.sideA().ready());
        assertEquals(TradeSession.State.OPEN, s.state());

        assertTrue(s.toggleReady(TradeSession.Side.B));
        assertEquals(initialGen, s.gen());
        assertEquals(TradeSession.State.COUNTDOWN, s.state());
        assertEquals(3, s.countdownRemaining());
        assertEquals(initialGen, s.countdownGen());

        // 한쪽 준비 해제 → gen 유지, 카운트다운만 취소, 상대 준비는 유지
        assertTrue(s.toggleReady(TradeSession.Side.A));
        assertEquals(initialGen, s.gen());
        assertFalse(s.sideA().ready());
        assertTrue(s.sideB().ready());
        assertEquals(TradeSession.State.OPEN, s.state());
    }

    @Test
    void offerChangeIncrementsGenResetsBothReadyAndCancelsCountdown() {
        TradeSession s = newOpenSession();
        assertTrue(s.toggleReady(TradeSession.Side.A));
        assertTrue(s.toggleReady(TradeSession.Side.B));
        assertEquals(TradeSession.State.COUNTDOWN, s.state());
        long beforeGen = s.gen();

        assertEquals(TradeSession.OfferChangeResult.CHANGED, s.setOfferAmount(TradeSession.Side.A, 0, 16, 16));
        assertEquals(beforeGen + 1, s.gen());
        assertEquals(TradeSession.State.OPEN, s.state());
        assertFalse(s.sideA().ready());
        assertFalse(s.sideB().ready());

        // 오래된 세대의 카운트다운 틱과 정산 진입은 무시됨
        assertFalse(s.tickCountdownSecond(beforeGen));
        assertFalse(s.beginSettling(beforeGen));
    }

    @Test
    void maxSixteenOfferSlotsAndOneItemIncrementDecrement() {
        TradeSession s = newOpenSession();
        for (int i = 0; i < OfferValidation.MAX_OFFER_SLOTS; i++) {
            assertEquals(TradeSession.OfferChangeResult.CHANGED, s.setOfferAmount(TradeSession.Side.A, i, 1, 64));
        }
        // 17번째 칸은 거부
        assertEquals(TradeSession.OfferChangeResult.MAX_SLOTS_REACHED,
                s.setOfferAmount(TradeSession.Side.A, 20, 1, 64));
        // 이미 제시한 칸 우클릭(+1)은 허용
        assertEquals(TradeSession.OfferChangeResult.CHANGED, s.addOneOffer(TradeSession.Side.A, 0, 64));
        assertEquals(2, s.sideA().offeredAmount(0));
        // 1개 빼기 → 1
        assertEquals(TradeSession.OfferChangeResult.CHANGED, s.removeOneOfferByIndex(TradeSession.Side.A, 0));
        assertEquals(1, s.sideA().offeredAmount(0));
        // 1개 더 빼기 → 제거
        assertEquals(TradeSession.OfferChangeResult.CHANGED, s.removeOneOfferByIndex(TradeSession.Side.A, 0));
        assertEquals(0, s.sideA().offeredAmount(0));
        assertEquals(15, s.sideA().offerCount());
    }

    @Test
    void dialogFlowCapturesGenAndConsumesTargetMenuCloseExceptionOnce() {
        TradeSession s = newOpenSession();
        s.toggleReady(TradeSession.Side.A);
        s.toggleReady(TradeSession.Side.B);
        assertEquals(TradeSession.State.COUNTDOWN, s.state());

        UUID token = UUID.randomUUID();
        UUID targetMenuId = UUID.randomUUID();
        UUID otherMenuId = UUID.randomUUID();
        long genAtOpen = s.gen();
        assertNotNull(s.beginDialog(TradeSession.Side.A, token, targetMenuId, 5_000L, 60_000L));
        // 대화창 열림 → 본인 준비 해제 + 카운트다운 취소 + gen 유지
        assertFalse(s.sideA().ready());
        assertTrue(s.sideB().ready());
        assertEquals(TradeSession.State.OPEN, s.state());
        assertEquals(genAtOpen, s.gen());
        // 대화창 중에는 본인 준비 토글 불가
        assertFalse(s.toggleReady(TradeSession.Side.A));

        // P3-10: 다른 창 ID 의 닫기는 예외로 허용하지 않고 예외권도 소진하지 않음
        assertFalse(s.consumeExpectedDialogClose(TradeSession.Side.A, null));
        assertFalse(s.consumeExpectedDialogClose(TradeSession.Side.A, otherMenuId));
        assertTrue(s.sideA().expectDialogClose());

        // 전환 대상 창의 단일 닫기만 딱 1회 소비됨 (두 번째 닫기는 불허)
        assertTrue(s.consumeExpectedDialogClose(TradeSession.Side.A, targetMenuId));
        assertFalse(s.sideA().expectDialogClose());
        assertFalse(s.consumeExpectedDialogClose(TradeSession.Side.A, targetMenuId));

        // 만료 전 정상 응답 적용 → gen 증가 + 양쪽 준비 해제
        assertEquals(TradeSession.DialogSubmitResult.APPLIED,
                s.submitDialogMoney(TradeSession.Side.A, token, 5_000L, 64_999L));
        assertEquals(5_000L, s.sideA().money());
        assertEquals(genAtOpen + 1, s.gen());
        assertFalse(s.sideB().ready());
        // 같은 일회성 토큰 재전송 무시
        assertEquals(TradeSession.DialogSubmitResult.IGNORED_NOT_ACTIVE,
                s.submitDialogMoney(TradeSession.Side.A, token, 9_000L, 64_999L));
    }

    @Test
    void dialogResponseWithStaleGenOrExpiredTimestampIsRejected() {
        TradeSession s = newOpenSession();
        UUID token = UUID.randomUUID();
        UUID menuId = UUID.randomUUID();
        assertNotNull(s.beginDialog(TradeSession.Side.A, token, menuId, 5_000L, 60_000L));

        // 상대 B가 아이템을 변경해 세대가 올라감
        assertEquals(TradeSession.OfferChangeResult.CHANGED, s.setOfferAmount(TradeSession.Side.B, 2, 10, 10));

        // A의 예전 세대 응답은 무시되어야 함 (TRADE.md 2·7장)
        assertEquals(TradeSession.DialogSubmitResult.STALE_GEN,
                s.submitDialogMoney(TradeSession.Side.A, token, 1_000L, 10_000L));
        assertEquals(0L, s.sideA().money());
        assertFalse(s.sideA().inDialog());

        // P3-10: 절대 만료 시각(nowMs >= dialogExpiresAtMs) 경과 후 도착한 콜백은 EXPIRED 로 거부
        UUID token2 = UUID.randomUUID();
        UUID menuId2 = UUID.randomUUID();
        long genBefore = s.gen();
        assertNotNull(s.beginDialog(TradeSession.Side.A, token2, menuId2, 20_000L, 60_000L));
        assertEquals(TradeSession.DialogSubmitResult.EXPIRED,
                s.submitDialogMoney(TradeSession.Side.A, token2, 7_000L, 80_000L));
        assertEquals(0L, s.sideA().money());
        assertEquals(genBefore, s.gen());
        assertFalse(s.sideA().inDialog());
    }

    @Test
    void cannotCancelViaPreSettlementAfterEnteringSettling() {
        TradeSession s = newOpenSession();
        s.setOfferAmount(TradeSession.Side.A, 0, 1, 1);
        long gen = s.gen();
        s.toggleReady(TradeSession.Side.A);
        s.toggleReady(TradeSession.Side.B);
        assertFalse(s.tickCountdownSecond(gen)); // 3 -> 2
        assertFalse(s.tickCountdownSecond(gen)); // 2 -> 1
        assertTrue(s.tickCountdownSecond(gen));  // 1 -> 0
        assertTrue(s.beginSettling(gen));
        assertEquals(TradeSession.State.SETTLING, s.state());

        // SETTLING 이후에는 로그아웃·사망·창 닫기 등으로 사전 취소되지 않음 (TRADE.md 2장)
        assertFalse(s.cancelIfPreSettlement("로그아웃"));
        assertEquals(TradeSession.State.SETTLING, s.state());

        assertTrue(s.markCommitted());
        assertTrue(s.markCompleted());
    }

    @Test
    void sessionRegistryEnforcesOneSessionPerPlayerAndTenSecondCooldown() {
        SessionRegistry registry = new SessionRegistry();
        TradeSession s1 = new TradeSession(UUID.randomUUID(), A, "Alice", B, "Bob", 1_000L, 30_000L, 3);
        assertTrue(registry.register(s1));

        // A 또는 B 가 이미 세션이 있으므로 새 요청 거부
        TradeSession s2 = new TradeSession(UUID.randomUUID(), A, "Alice", C, "Carol", 2_000L, 30_000L, 3);
        TradeSession s3 = new TradeSession(UUID.randomUUID(), C, "Carol", B, "Bob", 2_000L, 30_000L, 3);
        assertFalse(registry.register(s2));
        assertFalse(registry.register(s3));

        // 만료 감지 (30초 뒤)
        assertTrue(registry.findExpiredRequests(30_999L).isEmpty());
        assertEquals(1, registry.findExpiredRequests(31_000L).size());

        registry.remove(s1);
        registry.setReRequestCooldown(A, B, 31_000L, 10_000L);
        assertEquals(10_000L, registry.reRequestRemainingMs(A, B, 31_000L));
        assertEquals(0L, registry.reRequestRemainingMs(B, A, 31_000L)); // 방향별
        assertEquals(0L, registry.reRequestRemainingMs(A, B, 41_000L));

        assertNull(s1.partnerOf(C));
    }
}
