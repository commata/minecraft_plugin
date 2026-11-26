package kr.commata.market.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 임시 폴더의 실제 파일형 SQLite 로 재기동·한도·복구·잠금을 검증합니다. */
class MarketStoreTest {

    @TempDir
    Path temp;

    private final AtomicLong now = new AtomicLong(1_764_144_000_000L);
    private final UUID alice = UUID.randomUUID();
    private final List<MarketStore> opened = new ArrayList<>();

    @AfterEach
    void closeAll() {
        opened.forEach(MarketStore::close);
    }

    private Path marketDir() {
        return temp.resolve("Market");
    }

    private MarketStore openStore() {
        MarketBootstrap.Result r = MarketBootstrap.open(marketDir(), now::get);
        MarketStore s = assertInstanceOf(MarketBootstrap.Ready.class, r, () -> r.toString()).store();
        opened.add(s);
        return s;
    }

    @Test
    void bootstrapAllowsPreplacedConfigOnFirstInstallButLocksWhenDbDeletedLater() throws Exception {
        // 운영 첫 배포처럼 config.yml 만 먼저 복사된 폴더에서도 첫 설치는 허용
        Files.createDirectories(marketDir());
        Files.writeString(marketDir().resolve("config.yml"), "timezone: Asia/Seoul\n");
        MarketStore first = openStore();
        first.closeClean();

        // 운영 중 market.db 가 삭제되면 새로 만들지 않고 잠금
        Files.delete(marketDir().resolve(MarketStore.DB_FILE));
        MarketBootstrap.Result locked = MarketBootstrap.open(marketDir(), now::get);
        assertInstanceOf(MarketBootstrap.Locked.class, locked);
        assertFalse(Files.exists(marketDir().resolve(MarketStore.DB_FILE)));
    }

    @Test
    void rareShopSharedBookLimitPersistsAcrossRestartAndRevertsOnAbort() throws Exception {
        MarketStore s = openStore();
        RareCatalog.Entry book1 = new RareCatalog.Entry(
                "efficiency_4", RareCatalog.Kind.BOOK, "효율 IV", null, "efficiency", 4, 19, 3000, 2);
        RareCatalog.Entry book2 = new RareCatalog.Entry(
                "protection_4", RareCatalog.Kind.BOOK, "보호 IV", null, "protection", 4, 21, 4000, 2);

        // 1권 구매 완료
        var r1 = s.reserveRarePurchase("tx-1", alice, book1, OrderPayload.encodeRareBook(book1), "2026-W48");
        assertTrue(r1.ok());
        // PENDING 동안 같은 플레이어는 추가 구매 불가(잠금)
        assertTrue(s.isPlayerLocked(alice));
        assertEquals(MarketStore.ReserveFailure.PLAYER_LOCKED,
                s.reserveRarePurchase("tx-dup", alice, book2, OrderPayload.encodeRareBook(book2), "2026-W48").failure());

        assertTrue(s.markOrderCharged("tx-1", 101L));
        assertTrue(s.markOrderDelivered("tx-1"));
        assertFalse(s.isPlayerLocked(alice));

        // 2권째 예약 후 취소 → 한도 반환 확인
        var r2 = s.reserveRarePurchase("tx-2", alice, book2, OrderPayload.encodeRareBook(book2), "2026-W48");
        assertTrue(r2.ok());
        assertEquals(2, s.weeklyUsed(alice, "2026-W48", RareCatalog.BOOK_LIMIT_KEY));
        assertTrue(s.abortOrder("tx-2", "잔액 부족"));
        assertEquals(1, s.weeklyUsed(alice, "2026-W48", RareCatalog.BOOK_LIMIT_KEY));

        // 2권째 정상 구매 → 공유 한도 2/2 소진
        var r3 = s.reserveRarePurchase("tx-3", alice, book2, OrderPayload.encodeRareBook(book2), "2026-W48");
        assertTrue(r3.ok());
        s.markOrderCharged("tx-3", 102L);
        s.markOrderDelivered("tx-3");
        s.closeClean();

        // 재기동 후에도 공유 한도 유지 및 초과 거부
        MarketStore reopened = openStore();
        assertEquals(2, reopened.weeklyUsed(alice, "2026-W48", RareCatalog.BOOK_LIMIT_KEY));
        assertEquals(MarketStore.ReserveFailure.LIMIT_EXCEEDED,
                reopened.reserveRarePurchase("tx-4", alice, book1, OrderPayload.encodeRareBook(book1), "2026-W48").failure());
        assertEquals(2, reopened.recentLogs(alice, 10).size());
    }

    @Test
    void gachaPityAndDailyLimitRevertOnAbortAndSurviveReset() throws Exception {
        MarketStore s = openStore();
        GachaCatalog gacha = GachaCatalog.of(true, 2000, 20, 80, List.of(
                new GachaCatalog.Tier("common", "일반", 9900, "#AAAAAA", "BOOK", 10, false,
                        List.of(new GachaCatalog.Book("efficiency", 1, "효율 I"))),
                new GachaCatalog.Tier("legendary", "전설", 100, "#F5C542", "NETHER_STAR", 16, true,
                        List.of(new GachaCatalog.Book("mending", 1, "수선")))
        ));
        List<GachaCatalog.Draw> draws = gacha.roll(10, 0, new Random(1));
        int pityAfter = draws.get(9).pityAfter();
        String payload = OrderPayload.encodeGacha(draws, 2000);

        // 예약 후 취소 → 천장 0, 하루 횟수 0으로 복원
        var aborted = s.reserveGachaPull("g-1", alice, 10, 20, 20_000, 0, pityAfter, payload, "2026-11-26");
        assertTrue(aborted.ok());
        assertEquals(pityAfter, s.pity(alice));
        assertTrue(s.abortOrder("g-1", "결제 거절"));
        assertEquals(0, s.pity(alice));
        assertEquals(0, s.dailyPullsUsed(alice, "2026-11-26"));

        // 정상 완료 → /market reset 후 한도는 0이지만 천장은 유지
        var ok = s.reserveGachaPull("g-2", alice, 10, 20, 20_000, 0, pityAfter, payload, "2026-11-26");
        assertTrue(ok.ok());
        s.markOrderCharged("g-2", 201L);
        s.markOrderDelivered("g-2");
        assertEquals(10, s.dailyPullsUsed(alice, "2026-11-26"));

        s.resetLimits(alice, "2026-W48", "2026-11-26");
        assertEquals(0, s.dailyPullsUsed(alice, "2026-11-26"));
        assertEquals(pityAfter, s.pity(alice));
        assertEquals(10, s.recentLogs(alice, 20).size());
    }

    @Test
    void sellQuotaDemotesOnLeaveAndSplitSaleRecordsTwoLinesOrRevertsOnAbort() throws Exception {
        MarketStore s = openStore();
        SellCatalog.Item cod = new SellCatalog.Item("COD", "fisherman", "생대구", 1, 4, 10, 0);

        // 직업 보유 중 3마리, 비직업 2마리 획득
        s.addQuota(alice, "COD", 3, true);
        s.addQuota(alice, "COD", 2, false);
        assertEquals(new MarketStore.Quota(3, 2), s.quota(alice, "COD"));

        // 보너스 3 + 기본 2 한 번에 판매 → 예약 후 취소하면 원복
        SellMath.Plan plan = SellMath.calculate(10, 3, 2, 1, 4, 10, 5000, Integer.MAX_VALUE);
        assertEquals(38L, plan.totalMoney());
        var r1 = s.reserveSale("s-1", alice, cod, plan, 5000, "2026-11-26");
        assertTrue(r1.ok());
        assertEquals(MarketStore.Quota.ZERO, s.quota(alice, "COD"));
        assertTrue(s.abortSale("s-1", "디스크 미기록"));
        assertEquals(new MarketStore.Quota(3, 2), s.quota(alice, "COD"));
        assertEquals(0L, s.dailySalesTotal(alice, "2026-11-26"));

        // 정상 완료 → sales 에 bonus 1줄 + base 1줄 총 2줄 기록 (ECONOMY.md 14.3)
        var r2 = s.reserveSale("s-2", alice, cod, plan, 5000, "2026-11-26");
        assertTrue(r2.ok());
        assertTrue(s.markSaleItemsTaken("s-2"));
        assertTrue(s.markSaleDone("s-2", 301L));
        assertEquals(38L, s.dailySalesTotal(alice, "2026-11-26"));
        assertEquals(2, s.recentLogs(alice, 10).size());

        // 추가 획득 후 탈퇴 이관 → job_amount 가 base_amount 로 이동
        s.addQuota(alice, "COD", 5, true);
        s.addQuota(alice, "COD", 1, false);
        assertEquals(1, s.demoteJobQuota(alice, List.of("COD")));
        assertEquals(new MarketStore.Quota(0, 6), s.quota(alice, "COD"));
    }

    @Test
    void quarantineBlocksTransactionsUntilCleared() throws Exception {
        MarketStore s = openStore();
        s.quarantinePlayer(alice, "seq mismatch", "alice.dat");
        assertTrue(s.isPlayerLocked(alice));
        assertTrue(s.hasMarketHistory(alice));
        assertEquals(1, s.allQuarantined().size());

        assertTrue(s.unquarantinePlayer(alice));
        assertFalse(s.isPlayerLocked(alice));
    }

    @Test
    void addQuotaBatchCommitsMultipleEntriesInOneTransaction() throws Exception {
        MarketStore s = openStore();
        QuotaBuffer buf = new QuotaBuffer();
        buf.add(alice, "OAK_LOG", 12, true);
        buf.add(alice, "OAK_LOG", 8, true);
        buf.add(alice, "OAK_LOG", 5, false);
        buf.add(alice, "DIAMOND", 2, true);
        s.addQuotaBatch(buf.drainAll());
        s.closeClean();

        MarketStore reopened = openStore();
        assertEquals(new MarketStore.Quota(20, 5), reopened.quota(alice, "OAK_LOG"));
        assertEquals(new MarketStore.Quota(2, 0), reopened.quota(alice, "DIAMOND"));
    }

    @Test
    void adminRefundLocksDeliveryThenReturnsLimitsAndPity() throws Exception {
        MarketStore s = openStore();
        RareCatalog.Entry diamond = new RareCatalog.Entry(
                "diamond", RareCatalog.Kind.RESOURCE, "다이아몬드", "DIAMOND", null, 0, 13, 1500, 1);
        assertTrue(s.reserveRarePurchase("r-1", alice, diamond, OrderPayload.encodeRareResource(diamond), "2026-W48").ok());
        assertTrue(s.markOrderCharged("r-1", 11L));
        assertEquals(1, s.weeklyUsed(alice, "2026-W48", diamond.limitKey()));

        // 환불 시작: 지급 경로(openOrders)에서 빠지고, 플레이어는 계속 잠기며, /market pending 에는 보임
        assertTrue(s.beginRefund("r-1", "admin"));
        assertFalse(s.beginRefund("r-1", "admin"), "REFUNDING 은 다시 시작하지 않음");
        assertTrue(s.openOrders().isEmpty());
        assertTrue(s.isPlayerLocked(alice));
        assertEquals(1, s.unresolvedOrders().size());
        assertFalse(s.markOrderDelivered("r-1"), "환불 중인 주문은 지급 완료로 바뀌지 않음");

        // Bank 가 확실히 거부 → 다시 지급 대기
        assertTrue(s.cancelRefund("r-1", "거부"));
        assertEquals(MarketStore.ORDER_CHARGED, s.findOrder("r-1").orElseThrow().status());

        // 다시 환불 → 완료 시 주간 한도 반환
        assertTrue(s.beginRefund("r-1", "admin"));
        assertTrue(s.finishRefund("r-1", "done"));
        assertFalse(s.finishRefund("r-1", "done"), "두 번 반환하지 않음");
        assertEquals(MarketStore.ORDER_REFUNDED, s.findOrder("r-1").orElseThrow().status());
        assertEquals(0, s.weeklyUsed(alice, "2026-W48", diamond.limitKey()));
        assertFalse(s.isPlayerLocked(alice));

        // 뽑기 환불: 하루 횟수·천장 반환 (재기동 후에도 REFUNDING 유지)
        assertTrue(s.reserveGachaPull("g-1", alice, 10, 20, 20_000, 0, 10, "payload", "2026-11-26").ok());
        assertTrue(s.markOrderCharged("g-1", 12L));
        assertTrue(s.beginRefund("g-1", "admin"));
        s.close();
        MarketStore reopened = openStore();
        assertEquals(MarketStore.ORDER_REFUNDING, reopened.findOrder("g-1").orElseThrow().status());
        assertTrue(reopened.isPlayerLocked(alice));
        assertTrue(reopened.finishRefund("g-1", "done"));
        assertEquals(0, reopened.dailyPullsUsed(alice, "2026-11-26"));
        assertEquals(0, reopened.pity(alice));
    }

    @Test
    void raisePlayerSeqNeverLowers() throws Exception {
        MarketStore s = openStore();
        s.raisePlayerSeq(alice, 5);
        s.raisePlayerSeq(alice, 3);
        assertEquals(5, s.playerSeq(alice));
        s.raisePlayerSeq(alice, 7);
        assertEquals(7, s.playerSeq(alice));
    }
}
