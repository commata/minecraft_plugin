package kr.commata.bank.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 모두 임시 폴더의 파일형 SQLite 로 테스트합니다 (연결을 닫고 다시 열어 재기동을 확인하기 위해). */
class BankStoreTest {

    @TempDir
    Path dir;

    private final AtomicLong now = new AtomicLong(1_700_000_000_000L);
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final List<BankStore> opened = new ArrayList<>();

    @AfterEach
    void closeAll() {
        opened.forEach(BankStore::close);
    }

    private Path bankDir() {
        return dir.resolve("Bank");
    }

    /** 첫 설치. */
    private BankStore install() throws Exception {
        var r = Bootstrap.open(bankDir(), false, now::get);
        BankStore store = assertInstanceOf(Bootstrap.Ready.class, r).store();
        opened.add(store);
        store.startBoot("boot-1");
        return store;
    }

    /** 재기동 (폴더가 이미 있음). */
    private BankStore reboot(String bootId) throws Exception {
        var r = Bootstrap.open(bankDir(), true, now::get);
        BankStore store = assertInstanceOf(Bootstrap.Ready.class, r, () -> r.toString()).store();
        opened.add(store);
        store.startBoot(bootId);
        return store;
    }

    // ------------------------------------------------------------------ 기본 입출금

    @Test
    void depositWithdrawAndLogSurviveReopen() throws Exception {
        BankStore s = install();
        assertTrue(s.ensureAccount(alice, "Alice"));
        Outcome d = s.deposit(alice, "Alice", 1000, TxType.DEPOSIT, "VJobs", null);
        assertTrue(d.ok());
        assertEquals(1000, d.balance());
        Outcome w = s.withdraw(alice, 300, TxType.WITHDRAW, Sources.SHOP, "world 1,64,2 | 상점");
        assertTrue(w.ok());
        assertEquals(700, w.balance());
        s.closeClean();

        BankStore r = reboot("boot-2");
        assertEquals(700, r.balance(alice).orElseThrow());
        List<TxRecord> log = r.history(alice, 0, 10);
        assertEquals(2, log.size());
        TxRecord withdraw = log.get(0);
        assertEquals(TxType.WITHDRAW, withdraw.type());
        assertEquals(Sources.SHOP, withdraw.source());
        assertEquals("boot-1", withdraw.bootId());
        assertEquals(BootStatus.CLEAN, withdraw.bootStatus());
        assertEquals("world 1,64,2 | 상점", withdraw.context());
        assertEquals(700L, withdraw.fromBalanceAfter());
        assertEquals("VJobs", log.get(1).source());
    }

    @Test
    void successIsDurableEvenWithoutCleanShutdown() throws Exception {
        BankStore s = install();
        s.ensureAccount(alice, "Alice");
        s.ensureAccount(bob, "Bob");
        s.deposit(alice, "Alice", 500, TxType.DEPOSIT, "VJobs", null);
        Outcome pay = s.transfer(alice, bob, 120, Sources.BANK, null);
        assertTrue(pay.ok());
        s.close(); // 정상 종료 기록 없이 닫음 (강제 종료 흉내)

        BankStore r = reboot("boot-2");
        assertEquals(380, r.balance(alice).orElseThrow());
        assertEquals(120, r.balance(bob).orElseThrow());
        TxRecord tx = r.transaction(pay.txId()).orElseThrow();
        assertEquals(TxType.PAY, tx.type());
        assertEquals(alice, tx.from());
        assertEquals(bob, tx.to());
        assertEquals(380L, tx.fromBalanceAfter());
        assertEquals(120L, tx.toBalanceAfter());
    }

    @Test
    void rejectedChangesLeaveNoTraceAndKeepBalance() throws Exception {
        BankStore s = install();
        s.ensureAccount(alice, "Alice");
        s.ensureAccount(bob, "Bob");
        s.deposit(alice, "Alice", 100, TxType.DEPOSIT, "VJobs", null);
        long before = s.transactionCount();

        assertEquals(Failure.INSUFFICIENT_FUNDS, s.withdraw(alice, 101, TxType.WITHDRAW, "x", null).failure());
        assertEquals(Failure.INSUFFICIENT_FUNDS, s.transfer(alice, bob, 101, Sources.BANK, null).failure());
        assertEquals(Failure.SELF_TRANSFER, s.transfer(alice, alice, 10, Sources.BANK, null).failure());
        assertEquals(Failure.INVALID_AMOUNT, s.transfer(alice, bob, 0, Sources.BANK, null).failure());
        assertEquals(Failure.INVALID_AMOUNT, s.transfer(alice, bob, -5, Sources.BANK, null).failure());
        assertEquals(Failure.INVALID_AMOUNT, s.deposit(alice, "Alice", 0, TxType.DEPOSIT, "x", null).failure());
        assertEquals(Failure.NO_ACCOUNT, s.transfer(alice, UUID.randomUUID(), 10, Sources.BANK, null).failure());
        assertEquals(Failure.INSUFFICIENT_FUNDS,
                s.withdraw(UUID.randomUUID(), 1, TxType.WITHDRAW, "x", null).failure());
        assertEquals(Failure.LIMIT_EXCEEDED,
                s.deposit(alice, "Alice", Amounts.MAX, TxType.DEPOSIT, "x", null).failure());

        assertEquals(before, s.transactionCount());
        assertEquals(100, s.balance(alice).orElseThrow());
        assertEquals(0, s.balance(bob).orElseThrow());
        s.close();
        BankStore r = reboot("boot-2");
        assertEquals(100, r.balance(alice).orElseThrow());
        assertEquals(before, r.transactionCount());
    }

    @Test
    void depositCreatesAccountWithdrawDoesNot() throws Exception {
        BankStore s = install();
        UUID stranger = UUID.randomUUID();
        assertFalse(s.withdraw(stranger, 1, TxType.WITHDRAW, "x", null).ok());
        assertFalse(s.hasAccount(stranger));
        assertTrue(s.deposit(stranger, "Stranger", 5, TxType.DEPOSIT, "x", null).ok());
        assertTrue(s.hasAccount(stranger));
        assertEquals("Stranger", s.account(stranger).orElseThrow().name());
    }

    @Test
    void adminSetLogsDirectionAndAmount() throws Exception {
        BankStore s = install();
        s.ensureAccount(alice, "Alice");
        s.deposit(alice, "Alice", 500, TxType.DEPOSIT, "x", null);
        Outcome up = s.set(alice, 800, Sources.BANK, "admin=op");
        Outcome down = s.set(alice, 200, Sources.BANK, "admin=op");
        TxRecord upTx = s.transaction(up.txId()).orElseThrow();
        TxRecord downTx = s.transaction(down.txId()).orElseThrow();
        assertEquals(alice, upTx.to());
        assertEquals(300, upTx.amount());
        assertEquals(alice, downTx.from());
        assertEquals(600, downTx.amount());
        assertEquals(200, s.balance(alice).orElseThrow());
        assertEquals(Failure.NO_ACCOUNT, s.set(UUID.randomUUID(), 5, Sources.BANK, null).failure());
    }

    @Test
    void findByNameIgnoresCaseAndFollowsRenames() throws Exception {
        BankStore s = install();
        s.ensureAccount(alice, "Steve");
        now.addAndGet(1000);
        // 다른 사람이 같은 닉네임을 쓰게 되면 그 사람 계좌로 찾음
        s.ensureAccount(bob, "steve");
        assertEquals(bob, s.findByName("STEVE").orElseThrow().uuid());
        assertEquals(null, s.account(alice).orElseThrow().name());
        assertTrue(s.findByName("nobody").isEmpty());
    }

    @Test
    void topSortsByBalanceAndSkipsZero() throws Exception {
        BankStore s = install();
        UUID carol = UUID.randomUUID();
        s.ensureAccount(alice, "Alice");
        s.ensureAccount(bob, "Bob");
        s.ensureAccount(carol, "Carol");
        s.deposit(alice, "Alice", 50, TxType.DEPOSIT, "x", null);
        s.deposit(bob, "Bob", 900, TxType.DEPOSIT, "x", null);
        List<Account> top = s.top(10);
        assertEquals(List.of(bob, alice), top.stream().map(Account::uuid).toList());
    }

    @Test
    void closedStoreRefusesChanges() throws Exception {
        BankStore s = install();
        s.ensureAccount(alice, "Alice");
        s.closeClean();
        assertEquals(Failure.CLOSED, s.deposit(alice, "Alice", 5, TxType.DEPOSIT, "x", null).failure());
        assertFalse(s.ensureAccount(bob, "Bob"));
    }

    // ------------------------------------------------------------------ 동시성

    @Test
    void concurrentTransfersConserveMoneyAndMatchAfterReopen() throws Exception {
        BankStore s = install();
        int n = 6;
        UUID[] ids = new UUID[n];
        for (int i = 0; i < n; i++) {
            ids[i] = UUID.randomUUID();
            s.ensureAccount(ids[i], "P" + i);
            assertTrue(s.deposit(ids[i], "P" + i, 1000, TxType.DEPOSIT, "x", null).ok());
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                int ok = 0;
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                for (int k = 0; k < 150; k++) {
                    UUID a = ids[rnd.nextInt(n)];
                    UUID b = ids[rnd.nextInt(n)];
                    Outcome o = s.transfer(a, b, 1 + rnd.nextInt(400), Sources.BANK, null);
                    if (o.ok()) {
                        ok++;
                    }
                }
                return ok;
            }));
        }
        start.countDown();
        int succeeded = 0;
        for (Future<Integer> f : futures) {
            succeeded += f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        long total = Arrays.stream(ids).mapToLong(id -> s.balance(id).orElseThrow()).sum();
        assertEquals(n * 1000L, total);
        long[] cached = Arrays.stream(ids).mapToLong(id -> s.balance(id).orElseThrow()).toArray();
        assertEquals(n + succeeded, s.transactionCount());
        s.close();

        BankStore r = reboot("boot-2");
        for (int i = 0; i < n; i++) {
            assertEquals(cached[i], r.balance(ids[i]).orElseThrow());
            assertTrue(cached[i] >= 0);
        }
        assertEquals(n + succeeded, r.transactionCount());
    }

    // ------------------------------------------------------------------ 기동 기록·환불

    @Test
    void cleanShutdownIsRecorded() throws Exception {
        BankStore s = install();
        s.closeClean();
        BankStore r = reboot("boot-2");
        assertEquals("boot-1", r.readMeta("clean_shutdown"));
        assertEquals("boot-2", r.readMeta("boot_id"));
    }

    @Test
    void uncleanShutdownUsesLastHeartbeatNotLastTransaction() throws Exception {
        // 회귀 시나리오 (ECONOMY.md 8장): 구매 후 2시간 동안 거래 없이 비정상 종료
        BankStore s = install();
        s.ensureAccount(alice, "Alice");
        s.deposit(alice, "Alice", 5000, TxType.DEPOSIT, "VJobs", null);
        long purchaseAt = now.addAndGet(1000);
        Outcome buy = s.withdraw(alice, 1280, TxType.WITHDRAW, Sources.SHOP, null);
        for (int i = 0; i < 240; i++) { // 2시간, 30초 간격
            now.addAndGet(30_000);
            s.heartbeat();
        }
        long lastHeartbeat = now.get();
        now.addAndGet(12_000);
        s.close(); // 강제 종료

        var r = Bootstrap.open(bankDir(), true, now::get);
        BankStore store = assertInstanceOf(Bootstrap.Ready.class, r).store();
        opened.add(store);
        BootReport report = store.startBoot("boot-2");
        assertEquals("boot-1", report.previousBootId());
        assertTrue(report.previousUnclean());
        assertEquals(lastHeartbeat, report.crashEstimateMs());
        assertTrue(report.crashEstimateMs() - purchaseAt >= 2 * 60 * 60 * 1000L);
        assertEquals(BootStatus.UNCLEAN, store.transaction(buy.txId()).orElseThrow().bootStatus());
    }

    @Test
    void crashBeforeFirstHeartbeatUsesBootStart() throws Exception {
        long started = now.get();
        install();
        now.addAndGet(5000);
        opened.get(0).close(); // 첫 heartbeat 전에 강제 종료

        var r = Bootstrap.open(bankDir(), true, now::get);
        BankStore store = assertInstanceOf(Bootstrap.Ready.class, r).store();
        opened.add(store);
        BootReport report = store.startBoot("boot-2");
        assertTrue(report.previousUnclean());
        assertEquals(started, report.crashEstimateMs());
    }

    @Test
    void refundOnlyShopWithdrawalFromUncleanBootAndOnlyOnce() throws Exception {
        BankStore s = install();
        s.ensureAccount(alice, "Alice");
        s.ensureAccount(bob, "Bob");
        s.deposit(alice, "Alice", 10_000, TxType.DEPOSIT, "VJobs", null);
        Outcome shopClean = s.withdraw(alice, 100, TxType.WITHDRAW, Sources.SHOP, null);
        s.closeClean();

        BankStore b2 = reboot("boot-2");
        Outcome shop = b2.withdraw(alice, 1280, TxType.WITHDRAW, Sources.SHOP, "world 0,64,0 | 건축 블록");
        Outcome other = b2.withdraw(alice, 50, TxType.WITHDRAW, "SomePlugin", null);
        Outcome pay = b2.transfer(alice, bob, 70, Sources.BANK, null);
        // 지금 켜져 있는 기동의 거래는 아직 비정상 종료가 아님
        assertEquals(Failure.CLEAN_BOOT, b2.refund(shop.txId(), Sources.BANK, null).failure());
        b2.close(); // 강제 종료

        BankStore b3 = reboot("boot-3");
        long before = b3.balance(alice).orElseThrow();
        assertEquals(Failure.CLEAN_BOOT, b3.refund(shopClean.txId(), Sources.BANK, null).failure());
        assertEquals(Failure.NOT_SHOP, b3.refund(other.txId(), Sources.BANK, null).failure());
        assertEquals(Failure.NOT_WITHDRAW, b3.refund(pay.txId(), Sources.BANK, null).failure());
        assertEquals(Failure.TX_NOT_FOUND, b3.refund(999_999, Sources.BANK, null).failure());

        Outcome ok = b3.refund(shop.txId(), Sources.BANK, "admin=op");
        assertTrue(ok.ok(), ok::message);
        assertEquals(before + 1280, ok.balance());
        TxRecord refundTx = b3.transaction(ok.txId()).orElseThrow();
        assertEquals(TxType.REFUND, refundTx.type());
        assertEquals(shop.txId(), refundTx.ref());
        assertEquals(alice, refundTx.to());

        Outcome again = b3.refund(shop.txId(), Sources.BANK, "admin=op");
        assertEquals(Failure.ALREADY_REFUNDED, again.failure());
        // 환불 거래 자체도 출금이 아니므로 다시 환불 불가
        assertEquals(Failure.NOT_WITHDRAW, b3.refund(ok.txId(), Sources.BANK, null).failure());
        b3.closeClean();

        // 재기동 후에도 한 번만
        BankStore b4 = reboot("boot-4");
        assertEquals(Failure.ALREADY_REFUNDED, b4.refund(shop.txId(), Sources.BANK, null).failure());
        assertEquals(before + 1280, b4.balance(alice).orElseThrow());
    }

    // ------------------------------------------------------------------ 첫 설치 vs 분실

    @Test
    void firstInstallOnlyWhenFolderAbsent() throws Exception {
        BankStore s = install();
        assertEquals("true", s.readMeta("installed"));
        s.closeClean();
        assertTrue(Files.exists(bankDir().resolve(BankStore.DB_FILE)));
    }

    @Test
    void missingDatabaseLocksAndIsNotRecreated() throws Exception {
        install().closeClean();
        Files.delete(bankDir().resolve(BankStore.DB_FILE));
        var r = Bootstrap.open(bankDir(), true, now::get);
        assertInstanceOf(Bootstrap.Locked.class, r);
        assertFalse(Files.exists(bankDir().resolve(BankStore.DB_FILE)));
    }

    @Test
    void emptyFolderLocks() throws Exception {
        Files.createDirectories(bankDir());
        assertInstanceOf(Bootstrap.Locked.class, Bootstrap.open(bankDir(), true, now::get));
        assertFalse(Files.exists(bankDir().resolve(BankStore.DB_FILE)));
    }

    @Test
    void corruptOrForeignDatabaseLocks() throws Exception {
        install().closeClean();
        Path db = bankDir().resolve(BankStore.DB_FILE);
        Files.write(db, "this is not a database".repeat(500).getBytes());
        assertInstanceOf(Bootstrap.Locked.class, Bootstrap.open(bankDir(), true, now::get));

        Files.write(db, new byte[0]); // 빈 파일 = 빈 SQLite DB 지만 Bank 설치 표시가 없음
        assertInstanceOf(Bootstrap.Locked.class, Bootstrap.open(bankDir(), true, now::get));
    }

    @Test
    void createRefusesExistingFile() throws Exception {
        install().closeClean();
        var r = Bootstrap.open(bankDir(), false, now::get);
        assertInstanceOf(Bootstrap.Locked.class, r);
    }

    // ------------------------------------------------------------------ 성능

    @Test
    void commitTimeIsMeasured() throws Exception {
        BankStore s = install();
        s.ensureAccount(alice, "Alice");
        int n = 500;
        long[] nanos = new long[n];
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            assertTrue(s.deposit(alice, "Alice", 10, TxType.DEPOSIT, "VJobs", null).ok());
            nanos[i] = System.nanoTime() - t0;
        }
        Arrays.sort(nanos);
        double p50 = nanos[n / 2] / 1e6;
        double p99 = nanos[(int) (n * 0.99)] / 1e6;
        // 목표 p99 < 2ms 는 운영 디스크에서 확인 (CI 디스크는 편차가 큼). 여기서는 측정값만 출력
        System.out.printf("[Bank] 입금 커밋 시간 p50=%.3fms p99=%.3fms max=%.3fms (n=%d)%n",
                p50, p99, nanos[n - 1] / 1e6, n);
        assertEquals(n * 10L, s.balance(alice).orElseThrow());
    }
}
