package kr.commata.bank.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link BankStore#settleTrade} · {@link BankStore#findTradeByExtRef} 및 v3→v4 마이그레이션 검증 (TRADE.md 4·14장).
 * 모두 임시 폴더의 실제 파일형 SQLite DB 로 테스트합니다.
 */
class TradeSettlementTest {

    @TempDir
    Path dir;

    private final AtomicLong now = new AtomicLong(1_700_000_000_000L);
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID carol = UUID.randomUUID();
    private final List<BankStore> opened = new ArrayList<>();

    @AfterEach
    void closeAll() {
        opened.forEach(BankStore::close);
    }

    private Path bankDir(String sub) {
        return dir.resolve(sub);
    }

    private BankStore install(String sub) throws Exception {
        var r = Bootstrap.open(bankDir(sub), false, now::get);
        BankStore store = assertInstanceOf(Bootstrap.Ready.class, r).store();
        opened.add(store);
        store.startBoot("boot-1");
        return store;
    }

    private BankStore reboot(String sub, String bootId) throws Exception {
        var r = Bootstrap.open(bankDir(sub), true, now::get);
        BankStore store = assertInstanceOf(Bootstrap.Ready.class, r, () -> r.toString()).store();
        opened.add(store);
        store.startBoot(bootId);
        return store;
    }

    private static Connection rawConnection(Path dbFile) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile);
    }

    private static void exec(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    // ------------------------------------------------------------------ 정상 정산 (양방향 / 단방향)

    @Test
    void settlesBidirectionalTradeAtomicallyAndLogsBothLegs() throws Exception {
        BankStore s = install("bi");
        s.deposit(alice, "Alice", 1000, TxType.DEPOSIT, "VJobs", null);
        s.deposit(bob, "Bob", 500, TxType.DEPOSIT, "VJobs", null);

        TradeOutcome out = s.settleTrade("trade:t-1", alice, 400, bob, 150, Sources.TRADE, "tradeId=t-1");
        assertTrue(out.ok());
        assertEquals(TradeOutcome.Status.OK, out.status());
        assertNull(out.failure());
        assertFalse(out.idempotent());
        assertTrue(out.txAId() > 0);
        assertTrue(out.txBId() > 0);
        assertEquals(750L, s.balance(alice).orElseThrow());
        assertEquals(750L, s.balance(bob).orElseThrow());

        // /bank log 조회에 TRADE 유형과 순차 잔액이 남음
        TxRecord legA = s.transaction(out.txAId()).orElseThrow();
        assertEquals(TxType.TRADE, legA.type());
        assertEquals(alice, legA.from());
        assertEquals(bob, legA.to());
        assertEquals(400L, legA.amount());
        assertEquals(600L, legA.fromBalanceAfter());
        assertEquals(900L, legA.toBalanceAfter());
        assertEquals(Sources.TRADE, legA.source());
        assertEquals("tradeId=t-1", legA.context());

        TxRecord legB = s.transaction(out.txBId()).orElseThrow();
        assertEquals(TxType.TRADE, legB.type());
        assertEquals(bob, legB.from());
        assertEquals(alice, legB.to());
        assertEquals(150L, legB.amount());
        assertEquals(750L, legB.fromBalanceAfter());
        assertEquals(750L, legB.toBalanceAfter());

        // 강제 종료 후 재기동해도 잔액·원장·조회 기록 일치
        s.close();
        BankStore r = reboot("bi", "boot-2");
        assertEquals(750L, r.balance(alice).orElseThrow());
        assertEquals(750L, r.balance(bob).orElseThrow());
        TradeLookup lookup = r.findTradeByExtRef("trade:t-1");
        assertEquals(TradeLookup.Status.FOUND, lookup.status());
        assertNotNull(lookup.record());
        assertTrue(lookup.record().matches(alice, 400, bob, 150));
        assertEquals(out.txAId(), lookup.record().txAId());
        assertEquals(out.txBId(), lookup.record().txBId());
        assertEquals(Sources.TRADE, lookup.record().source());

        // /bank refund 는 TRADE 거래를 거부
        assertEquals(Failure.NOT_WITHDRAW, r.refund(out.txAId(), Sources.BANK, "admin=op").failure());
    }

    @Test
    void settlesOneSidedTradeAndCreatesReceiverAccountLikeDeposit() throws Exception {
        BankStore s = install("one-sided");
        s.deposit(alice, "Alice", 1000, TxType.DEPOSIT, "VJobs", null);

        // A 만 지급 (bPays == 0), 받는 쪽 B 계좌가 아직 없어도 자동 생성
        assertFalse(s.hasAccount(bob));
        TradeOutcome outA = s.settleTrade("trade:only-a", alice, 300, bob, 0, Sources.TRADE, null);
        assertTrue(outA.ok());
        assertTrue(outA.txAId() > 0);
        assertEquals(0L, outA.txBId());
        assertTrue(s.hasAccount(bob));
        assertEquals(700L, s.balance(alice).orElseThrow());
        assertEquals(300L, s.balance(bob).orElseThrow());

        // B 만 지급 (aPays == 0), 받는 쪽 Carol 계좌가 없어도 자동 생성
        assertFalse(s.hasAccount(carol));
        TradeOutcome outB = s.settleTrade("trade:only-b", carol, 0, bob, 120, Sources.TRADE, null);
        assertTrue(outB.ok());
        assertEquals(0L, outB.txAId());
        assertTrue(outB.txBId() > 0);
        assertTrue(s.hasAccount(carol));
        assertEquals(120L, s.balance(carol).orElseThrow());
        assertEquals(180L, s.balance(bob).orElseThrow());

        TradeLookup foundB = s.findTradeByExtRef("trade:only-b");
        assertEquals(TradeLookup.Status.FOUND, foundB.status());
        assertTrue(foundB.record().matches(carol, 0, bob, 120));
        assertEquals(0L, foundB.record().txAId());
        assertEquals(outB.txBId(), foundB.record().txBId());
    }

    // ------------------------------------------------------------------ 멱등 & MISMATCH

    @Test
    void idempotentRetryReturnsOriginalResultEvenAfterRestartAndBalanceDrop() throws Exception {
        BankStore s = install("idemp");
        s.deposit(alice, "Alice", 500, TxType.DEPOSIT, "VJobs", null);
        s.deposit(bob, "Bob", 200, TxType.DEPOSIT, "VJobs", null);

        TradeOutcome first = s.settleTrade("trade:id-1", alice, 500, bob, 100, Sources.TRADE, "first");
        assertTrue(first.ok());
        assertFalse(first.idempotent());
        assertEquals(100L, s.balance(alice).orElseThrow());
        assertEquals(600L, s.balance(bob).orElseThrow());

        // Alice 잔액이 0원이 되어도 같은 extRef·같은 요청 재호출은 멱등 OK (중복 출금 없음)
        assertTrue(s.withdraw(alice, 100, TxType.WITHDRAW, Sources.SHOP, null).ok());
        assertEquals(0L, s.balance(alice).orElseThrow());
        s.close();

        BankStore r = reboot("idemp", "boot-2");
        TradeOutcome retry = r.settleTrade("trade:id-1", alice, 500, bob, 100, Sources.TRADE, "retry");
        assertTrue(retry.ok());
        assertTrue(retry.idempotent());
        assertEquals(first.txAId(), retry.txAId());
        assertEquals(first.txBId(), retry.txBId());
        assertEquals(0L, r.balance(alice).orElseThrow());
        assertEquals(600L, r.balance(bob).orElseThrow());
    }

    @Test
    void detectsMismatchOnDifferentPartyAmountSourceOrMarketExtRefCollision() throws Exception {
        BankStore s = install("mismatch");
        s.deposit(alice, "Alice", 1000, TxType.DEPOSIT, "VJobs", null);
        s.deposit(bob, "Bob", 1000, TxType.DEPOSIT, "VJobs", null);
        s.deposit(carol, "Carol", 1000, TxType.DEPOSIT, "VJobs", null);

        TradeOutcome first = s.settleTrade("trade:m-1", alice, 100, bob, 100, Sources.TRADE, null);
        assertTrue(first.ok());

        // 금액 불일치
        TradeOutcome diffAmountA = s.settleTrade("trade:m-1", alice, 101, bob, 100, Sources.TRADE, null);
        assertEquals(TradeOutcome.Status.MISMATCH, diffAmountA.status());
        assertEquals(TradeFailure.MISMATCH, diffAmountA.failure());

        TradeOutcome diffAmountB = s.settleTrade("trade:m-1", alice, 100, bob, 99, Sources.TRADE, null);
        assertEquals(TradeOutcome.Status.MISMATCH, diffAmountB.status());

        // 당사자 불일치 (다른 사람, 또는 순서 뒤바뀜)
        TradeOutcome diffParty = s.settleTrade("trade:m-1", alice, 100, carol, 100, Sources.TRADE, null);
        assertEquals(TradeOutcome.Status.MISMATCH, diffParty.status());
        TradeOutcome swapped = s.settleTrade("trade:m-1", bob, 100, alice, 100, Sources.TRADE, null);
        assertEquals(TradeOutcome.Status.MISMATCH, swapped.status());

        // 출처 불일치
        TradeOutcome diffSource = s.settleTrade("trade:m-1", alice, 100, bob, 100, Sources.MARKET, null);
        assertEquals(TradeOutcome.Status.MISMATCH, diffSource.status());

        // 이미 쓰인 ref 에 형식이 틀린 값(같은 사람끼리, 0/0)이 와도 REJECTED 가 아니라 MISMATCH (QA ④-1)
        TradeOutcome sameParty = s.settleTrade("trade:m-1", alice, 100, alice, 100, Sources.TRADE, null);
        assertEquals(TradeOutcome.Status.MISMATCH, sameParty.status());
        TradeOutcome zeroZero = s.settleTrade("trade:m-1", alice, 0, bob, 0, Sources.TRADE, null);
        assertEquals(TradeOutcome.Status.MISMATCH, zeroZero.status());

        // Market external() 가 이미 쓴 extRef 를 trade 로 재사용하면 MISMATCH, findTradeByExtRef 는 ERROR
        var marketTx = s.external(alice, 50, Sources.MARKET, "shared-ref", TxType.WITHDRAW);
        assertEquals(BankStore.ExternalStatus.OK, marketTx.status());
        TradeOutcome crossToTrade = s.settleTrade("shared-ref", alice, 50, bob, 0, Sources.TRADE, null);
        assertEquals(TradeOutcome.Status.MISMATCH, crossToTrade.status());
        assertEquals(TradeLookup.Status.ERROR, s.findTradeByExtRef("shared-ref").status());

        // 반대로 trade 가 쓴 extRef 를 Market external() 이 재사용해도 거절
        var crossToMarket = s.external(alice, 100, Sources.MARKET, "trade:m-1", TxType.WITHDRAW);
        assertEquals(BankStore.ExternalStatus.REJECTED, crossToMarket.status());
    }

    // ------------------------------------------------------------------ 거절 사유 (잔액 부족, MAX 초과, a==b, 잘못된 입력)

    @Test
    void rejectsInsufficientFundsAndIdentifiesWhichPartyLacksBalance() throws Exception {
        BankStore s = install("insuff");
        s.deposit(alice, "Alice", 50, TxType.DEPOSIT, "VJobs", null);
        s.deposit(bob, "Bob", 500, TxType.DEPOSIT, "VJobs", null);
        long txBefore = s.transactionCount();

        // A 잔액 부족 (상대가 더 많이 주더라도 자기 잔액 < 자기 지급액이면 거절)
        TradeOutcome aShort = s.settleTrade("trade:ins-a", alice, 100, bob, 300, Sources.TRADE, null);
        assertEquals(TradeOutcome.Status.REJECTED, aShort.status());
        assertEquals(TradeFailure.INSUFFICIENT, aShort.failure());
        assertEquals(alice, aShort.affectedPlayer());

        // B 잔액 부족
        TradeOutcome bShort = s.settleTrade("trade:ins-b", alice, 10, bob, 501, Sources.TRADE, null);
        assertEquals(TradeOutcome.Status.REJECTED, bShort.status());
        assertEquals(TradeFailure.INSUFFICIENT, bShort.failure());
        assertEquals(bob, bShort.affectedPlayer());

        // 계좌 없는 쪽이 지급하려 하면 거절되고 계좌도 생성되지 않음
        TradeOutcome missingPayer = s.settleTrade("trade:ins-c", carol, 1, alice, 0, Sources.TRADE, null);
        assertEquals(TradeOutcome.Status.REJECTED, missingPayer.status());
        assertEquals(TradeFailure.INSUFFICIENT, missingPayer.failure());
        assertEquals(carol, missingPayer.affectedPlayer());
        assertFalse(s.hasAccount(carol));

        assertEquals(txBefore, s.transactionCount());
        assertEquals(50L, s.balance(alice).orElseThrow());
        assertEquals(500L, s.balance(bob).orElseThrow());
        assertEquals(TradeLookup.Status.NOT_FOUND, s.findTradeByExtRef("trade:ins-a").status());
    }

    @Test
    void enforcesBalanceLimitOnFinalBalances() throws Exception {
        BankStore s = install("limit");
        s.deposit(alice, "Alice", Amounts.MAX - 100, TxType.DEPOSIT, "VJobs", null);
        s.deposit(bob, "Bob", 500, TxType.DEPOSIT, "VJobs", null);

        // A 최종 잔액 MAX 초과
        TradeOutcome overA = s.settleTrade("trade:lim-a", alice, 0, bob, 101, Sources.TRADE, null);
        assertEquals(TradeOutcome.Status.REJECTED, overA.status());
        assertEquals(TradeFailure.BALANCE_LIMIT, overA.failure());
        assertEquals(alice, overA.affectedPlayer());

        // B 최종 잔액 MAX 초과
        s.deposit(carol, "Carol", Amounts.MAX - 50, TxType.DEPOSIT, "VJobs", null);
        TradeOutcome overB = s.settleTrade("trade:lim-b", bob, 51, carol, 0, Sources.TRADE, null);
        assertEquals(TradeOutcome.Status.REJECTED, overB.status());
        assertEquals(TradeFailure.BALANCE_LIMIT, overB.failure());
        assertEquals(carol, overB.affectedPlayer());

        // 양방향 상쇄로 최종 잔액이 MAX 이하이면 허용
        TradeOutcome withinMax = s.settleTrade("trade:lim-ok", alice, 200, bob, 300, Sources.TRADE, null);
        assertTrue(withinMax.ok());
        assertEquals(Amounts.MAX, s.balance(alice).orElseThrow());
        assertEquals(400L, s.balance(bob).orElseThrow());
    }

    @Test
    void rejectsSelfTradeAndInvalidInputs() throws Exception {
        BankStore s = install("invalid");
        s.deposit(alice, "Alice", 500, TxType.DEPOSIT, "VJobs", null);

        // a == b
        TradeOutcome self = s.settleTrade("trade:self", alice, 10, alice, 0, Sources.TRADE, null);
        assertEquals(TradeOutcome.Status.REJECTED, self.status());
        assertEquals(TradeFailure.SELF_TRADE, self.failure());
        assertEquals(alice, self.affectedPlayer());

        // 둘 다 0, 음수, MAX 초과
        assertEquals(TradeFailure.INVALID_AMOUNT,
                s.settleTrade("trade:zero", alice, 0, bob, 0, Sources.TRADE, null).failure());
        assertEquals(TradeFailure.INVALID_AMOUNT,
                s.settleTrade("trade:neg-a", alice, -1, bob, 10, Sources.TRADE, null).failure());
        assertEquals(TradeFailure.INVALID_AMOUNT,
                s.settleTrade("trade:neg-b", alice, 10, bob, -1, Sources.TRADE, null).failure());
        assertEquals(TradeFailure.INVALID_AMOUNT,
                s.settleTrade("trade:big", alice, Amounts.MAX + 1, bob, 0, Sources.TRADE, null).failure());

        // 빈 extRef / null UUID / 빈 source
        assertEquals(TradeFailure.INVALID_REQUEST,
                s.settleTrade(" ", alice, 10, bob, 0, Sources.TRADE, null).failure());
        assertEquals(TradeFailure.INVALID_REQUEST,
                s.settleTrade("trade:na", null, 10, bob, 0, Sources.TRADE, null).failure());
        assertEquals(TradeFailure.INVALID_REQUEST,
                s.settleTrade("trade:nb", alice, 10, null, 0, Sources.TRADE, null).failure());
        assertEquals(TradeFailure.INVALID_REQUEST,
                s.settleTrade("trade:ns", alice, 10, bob, 0, " ", null).failure());

        // findTradeByExtRef 빈 값 → ERROR (NOT_FOUND 아님)
        assertEquals(TradeLookup.Status.ERROR, s.findTradeByExtRef("").status());
        assertEquals(TradeLookup.Status.ERROR, s.findTradeByExtRef(null).status());
    }

    // ------------------------------------------------------------------ 잠금·종료·롤백·UNKNOWN

    @Test
    void lockedModeAndClosedAndBrokenConnectionAreDistinguished() throws Exception {
        // 1) Bank 잠금 모드 (데이터 폴더는 있으나 bank.db 없음 → Bootstrap.Locked, store == null)
        Path missingDbDir = bankDir("locked-missing");
        Files.createDirectories(missingDbDir);
        Bootstrap.Locked locked = assertInstanceOf(Bootstrap.Locked.class,
                Bootstrap.open(missingDbDir, true, now::get));
        TradeOutcome lockedOut = BankStore.settleTrade(
                null, locked.reason(), "trade:l1", alice, 10, bob, 0, Sources.TRADE, null);
        assertEquals(TradeOutcome.Status.REJECTED, lockedOut.status());
        assertEquals(TradeFailure.LOCKED, lockedOut.failure());
        assertEquals(locked.reason(), lockedOut.detail());
        TradeLookup lockedLookup = BankStore.findTradeByExtRef(null, locked.reason(), "trade:l1");
        assertEquals(TradeLookup.Status.ERROR, lockedLookup.status());
        assertEquals(locked.reason(), lockedLookup.detail());

        // 2) 정상 종료된 저장소 → CLOSED (UNKNOWN 아님)
        BankStore closedStore = install("closed");
        closedStore.deposit(alice, "Alice", 500, TxType.DEPOSIT, "VJobs", null);
        closedStore.closeClean();
        TradeOutcome closedOut = closedStore.settleTrade("trade:c1", alice, 10, bob, 0, Sources.TRADE, null);
        assertEquals(TradeOutcome.Status.REJECTED, closedOut.status());
        assertEquals(TradeFailure.CLOSED, closedOut.failure());
        assertEquals(TradeLookup.Status.ERROR, closedStore.findTradeByExtRef("trade:c1").status());

        // 3) brokenReason(커밋·롤백 불확실) → UNKNOWN, 조회 ERROR
        BankStore brokenStore = install("broken");
        brokenStore.deposit(alice, "Alice", 500, TxType.DEPOSIT, "VJobs", null);
        brokenStore.simulateBrokenForTest("COMMIT 실패: simulated I/O error");
        TradeOutcome unknownOut = brokenStore.settleTrade("trade:u1", alice, 10, bob, 0, Sources.TRADE, null);
        assertEquals(TradeOutcome.Status.UNKNOWN, unknownOut.status());
        assertEquals(TradeFailure.UNKNOWN, unknownOut.failure());
        assertTrue(unknownOut.detail().contains("COMMIT 실패"));
        assertEquals(TradeLookup.Status.ERROR, brokenStore.findTradeByExtRef("trade:u1").status());
    }

    @Test
    void rollsBackBothBalancesAndTransactionsWhenSettlementInsertFails() throws Exception {
        BankStore s = install("rollback");
        s.deposit(alice, "Alice", 1000, TxType.DEPOSIT, "VJobs", null);
        s.deposit(bob, "Bob", 500, TxType.DEPOSIT, "VJobs", null);
        long txBefore = s.transactionCount();

        Path dbFile = bankDir("rollback").resolve(BankStore.DB_FILE);
        try (Connection other = rawConnection(dbFile)) {
            exec(other, "CREATE TRIGGER fail_trade BEFORE INSERT ON trade_settlements"
                    + " BEGIN SELECT RAISE(ABORT, 'boom'); END");
        }

        TradeOutcome failed = s.settleTrade("trade:rb-1", alice, 300, bob, 100, Sources.TRADE, null);
        assertEquals(TradeOutcome.Status.REJECTED, failed.status());
        assertEquals(TradeFailure.STORAGE_ERROR, failed.failure());
        assertNull(s.brokenReason());
        assertEquals(1000L, s.balance(alice).orElseThrow());
        assertEquals(500L, s.balance(bob).orElseThrow());
        assertEquals(1000L, s.account(alice).orElseThrow().balance());
        assertEquals(500L, s.account(bob).orElseThrow().balance());
        assertEquals(txBefore, s.transactionCount());
        assertEquals(TradeLookup.Status.NOT_FOUND, s.findTradeByExtRef("trade:rb-1").status());

        try (Connection other = rawConnection(dbFile)) {
            exec(other, "DROP TRIGGER fail_trade");
        }
        TradeOutcome recovered = s.settleTrade("trade:rb-1", alice, 300, bob, 100, Sources.TRADE, null);
        assertTrue(recovered.ok());
        assertEquals(800L, s.balance(alice).orElseThrow());
        assertEquals(700L, s.balance(bob).orElseThrow());
    }

    // ------------------------------------------------------------------ 스키마 v3→v4 마이그레이션 동치 검증

    @Test
    void schemaV3MigratesToV4MatchingFreshInstallExactly() throws Exception {
        // 1) 새 설치 v4 DB
        BankStore fresh = install("fresh-v4");
        assertEquals(String.valueOf(BankStore.SCHEMA_VERSION), fresh.readMeta("schema"));
        fresh.closeClean();

        // 2) v3 상태로 되돌린 DB 를 열어 v4 로 마이그레이션
        BankStore legacy = install("migrated-v4");
        legacy.deposit(alice, "Alice", 2000, TxType.DEPOSIT, "VJobs", null);
        var marketW = legacy.external(alice, 400, Sources.MARKET, "m-keep-1", TxType.WITHDRAW);
        assertEquals(BankStore.ExternalStatus.OK, marketW.status());
        legacy.closeClean();

        Path migratedDb = bankDir("migrated-v4").resolve(BankStore.DB_FILE);
        try (Connection c = rawConnection(migratedDb)) {
            exec(c, "DROP TABLE trade_settlements");
            exec(c, "UPDATE meta SET value = '3' WHERE key = 'schema'");
        }

        BankStore reopened = reboot("migrated-v4", "boot-2");
        assertEquals(String.valueOf(BankStore.SCHEMA_VERSION), reopened.readMeta("schema"));
        assertEquals(1600L, reopened.balance(alice).orElseThrow());
        assertEquals(BankStore.LookupStatus.FOUND, reopened.findByExtRef("m-keep-1").status());

        // 마이그레이션 직후 settleTrade 정상 동작
        TradeOutcome settled = reopened.settleTrade("trade:after-mig", alice, 600, bob, 0, Sources.TRADE, null);
        assertTrue(settled.ok());
        assertEquals(1000L, reopened.balance(alice).orElseThrow());
        assertEquals(600L, reopened.balance(bob).orElseThrow());
        reopened.closeClean();

        // 새 설치 DB 와 마이그레이션된 DB 의 테이블·인덱스·컬럼 정의가 완전히 같은지 비교 (AGENTS.md 5.2)
        Path freshDb = bankDir("fresh-v4").resolve(BankStore.DB_FILE);
        assertEquals(dumpSchema(freshDb), dumpSchema(migratedDb));
    }

    private static Map<String, String> dumpSchema(Path dbFile) throws Exception {
        Map<String, String> schema = new LinkedHashMap<>();
        try (Connection c = rawConnection(dbFile);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT type, name, sql FROM sqlite_master"
                             + " WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%'"
                             + " ORDER BY type, name")) {
            while (rs.next()) {
                String key = rs.getString(1) + ":" + rs.getString(2);
                String normalizedSql = rs.getString(3).replaceAll("\\s+", " ").trim();
                schema.put(key, normalizedSql);
            }
        }
        return schema;
    }
}
