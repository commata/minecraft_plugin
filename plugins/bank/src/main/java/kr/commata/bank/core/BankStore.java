package kr.commata.bank.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteOpenMode;

/**
 * bank.db (파일형 SQLite, WAL, synchronous=FULL).
 *
 * <p>모든 잔액 변경은 "잔액 검사 → 변경 → 거래 기록"을 한 SQL 트랜잭션으로 처리하고,
 * 커밋이 끝난 뒤에만 성공을 반환합니다. 메모리의 잔액은 커밋 후에 갱신하는 조회용 사본입니다.
 * 연결은 하나이고 락으로 보호하므로 어느 스레드에서 불러도 됩니다.
 */
public final class BankStore implements AutoCloseable {

    public static final String DB_FILE = "bank.db";
    static final int SCHEMA_VERSION = 1;

    private static final String SCHEMA = """
            CREATE TABLE meta (
              key   TEXT PRIMARY KEY,
              value TEXT NOT NULL
            );
            CREATE TABLE accounts (
              uuid       TEXT PRIMARY KEY,
              name       TEXT,
              balance    INTEGER NOT NULL DEFAULT 0 CHECK (balance >= 0),
              updated_at INTEGER NOT NULL
            );
            CREATE INDEX accounts_name ON accounts (name COLLATE NOCASE);
            CREATE INDEX accounts_balance ON accounts (balance DESC);
            CREATE TABLE boots (
              boot_id            TEXT PRIMARY KEY,
              started_ms         INTEGER NOT NULL,
              last_heartbeat_ms  INTEGER,
              ended_ms           INTEGER,
              crash_estimated_ms INTEGER,
              status             TEXT NOT NULL
            );
            CREATE TABLE transactions (
              id                 INTEGER PRIMARY KEY AUTOINCREMENT,
              time_ms            INTEGER NOT NULL,
              type               TEXT NOT NULL,
              from_uuid          TEXT,
              to_uuid            TEXT,
              amount             INTEGER NOT NULL CHECK (amount >= 0),
              from_balance_after INTEGER,
              to_balance_after   INTEGER,
              source             TEXT NOT NULL,
              boot_id            TEXT NOT NULL,
              context            TEXT,
              ref                INTEGER UNIQUE REFERENCES transactions (id)
            );
            CREATE INDEX transactions_from ON transactions (from_uuid, id);
            CREATE INDEX transactions_to ON transactions (to_uuid, id);
            """;

    private final Connection conn;
    private final LongSupplier clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<UUID, Long> balances = new ConcurrentHashMap<>();
    private volatile String bootId;
    private volatile boolean closed;

    private BankStore(Connection conn, LongSupplier clock) throws SQLException {
        this.conn = conn;
        this.clock = clock;
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA busy_timeout = 5000");
            try (ResultSet rs = st.executeQuery("PRAGMA journal_mode = WAL")) {
                String mode = rs.next() ? rs.getString(1) : "";
                if (!"wal".equalsIgnoreCase(mode)) {
                    throw new SQLException("journal_mode 를 WAL 로 바꾸지 못함: " + mode);
                }
            }
            st.execute("PRAGMA synchronous = FULL");
            try (ResultSet rs = st.executeQuery("PRAGMA synchronous")) {
                // FULL = 2
                if (!rs.next() || rs.getInt(1) != 2) {
                    throw new SQLException("synchronous 를 FULL 로 바꾸지 못함");
                }
            }
        }
        conn.setAutoCommit(true);
    }

    // ------------------------------------------------------------------ 열기·닫기

    /** 새 DB 를 만듭니다. 파일이 이미 있으면 실패합니다 (첫 설치에서만 호출). */
    public static BankStore create(Path dbFile, LongSupplier clock) throws SQLException {
        if (Files.exists(dbFile)) {
            throw new SQLException("이미 DB 파일이 있음: " + dbFile);
        }
        Connection conn = connect(dbFile, true);
        try {
            BankStore store = new BankStore(conn, clock);
            store.write(() -> {
                try (Statement st = conn.createStatement()) {
                    for (String sql : SCHEMA.split(";")) {
                        if (!sql.isBlank()) {
                            st.execute(sql);
                        }
                    }
                }
                store.putMeta("schema", String.valueOf(SCHEMA_VERSION));
                store.putMeta("installed", "true");
                store.putMeta("created_ms", String.valueOf(clock.getAsLong()));
                return null;
            });
            return store;
        } catch (SQLException | RuntimeException e) {
            closeQuietly(conn);
            throw e;
        }
    }

    /**
     * 기존 DB 를 엽니다. 파일을 새로 만들지 않고, 무결성 검사와 설치 표시(meta.installed)를 확인합니다.
     *
     * @throws SQLException 파일이 없거나, 손상됐거나, Bank DB 가 아닐 때 (→ 잠금 모드)
     */
    public static BankStore open(Path dbFile, LongSupplier clock) throws SQLException {
        if (!Files.isRegularFile(dbFile)) {
            throw new SQLException("DB 파일이 없음: " + dbFile);
        }
        Connection conn = connect(dbFile, false);
        try {
            // WAL 전환 등으로 파일을 건드리기 전에 먼저 검사
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
                String first = rs.next() ? rs.getString(1) : "";
                if (!"ok".equalsIgnoreCase(first) || rs.next()) {
                    throw new SQLException("integrity_check 실패: " + first);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM meta WHERE key = 'installed'");
                 ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || !"true".equals(rs.getString(1))) {
                    throw new SQLException("meta.installed 표시가 없음 (Bank DB 가 아니거나 생성 도중 중단됨)");
                }
            }
            BankStore store = new BankStore(conn, clock);
            store.loadBalances();
            return store;
        } catch (SQLException | RuntimeException e) {
            closeQuietly(conn);
            throw e;
        }
    }

    private static Connection connect(Path dbFile, boolean create) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        // 기본값에 CREATE 가 들어 있으므로, 기존 DB 를 열 때는 빼서 없는 파일을 새로 만들지 않게 함
        config.setOpenMode(SQLiteOpenMode.READWRITE);
        if (create) {
            config.setOpenMode(SQLiteOpenMode.CREATE);
        } else {
            config.resetOpenMode(SQLiteOpenMode.CREATE);
        }
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        // 서버 클래스로더 문제를 피하려고 DriverManager 대신 드라이버를 직접 사용
        return config.createConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
    }

    private void loadBalances() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT uuid, balance FROM accounts")) {
            while (rs.next()) {
                balances.put(UUID.fromString(rs.getString(1)), rs.getLong(2));
            }
        }
    }

    /**
     * 이번 기동을 시작합니다. 이전 기동이 정상 종료를 남기지 않았으면 비정상 종료로 기록하고,
     * 그 기동의 마지막 heartbeat 를 장애 추정 시각으로 남깁니다 (마지막 거래 시각은 쓰지 않음).
     */
    public BootReport startBoot(String newBootId) throws SQLException {
        return write(() -> {
            long now = clock.getAsLong();
            String prev = meta("boot_id");
            String clean = meta("clean_shutdown");
            boolean unclean = prev != null && !prev.equals(clean);
            long crashMs = 0;
            if (unclean) {
                crashMs = lastHeartbeatOf(prev);
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE boots SET status = ?, crash_estimated_ms = ? WHERE boot_id = ?")) {
                    ps.setString(1, BootStatus.UNCLEAN);
                    ps.setLong(2, crashMs);
                    ps.setString(3, prev);
                    if (ps.executeUpdate() == 0) {
                        // boots 줄이 없는 경우(이론상 없음)에도 환불 판정이 가능하도록 남김
                        try (PreparedStatement ins = conn.prepareStatement(
                                "INSERT INTO boots (boot_id, started_ms, last_heartbeat_ms, crash_estimated_ms, status)"
                                        + " VALUES (?, ?, ?, ?, ?)")) {
                            ins.setString(1, prev);
                            ins.setLong(2, crashMs);
                            ins.setLong(3, crashMs);
                            ins.setLong(4, crashMs);
                            ins.setString(5, BootStatus.UNCLEAN);
                            ins.executeUpdate();
                        }
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO boots (boot_id, started_ms, last_heartbeat_ms, status) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, newBootId);
                ps.setLong(2, now);
                ps.setLong(3, now);
                ps.setString(4, BootStatus.RUNNING);
                ps.executeUpdate();
            }
            putMeta("boot_id", newBootId);
            putMeta("heartbeat", newBootId + "@" + now);
            bootId = newBootId;
            return new BootReport(prev, unclean, crashMs);
        });
    }

    /** meta.heartbeat 가 그 기동 것이면 그 시각, 아니면 boots 의 마지막 heartbeat, 그것도 없으면 시작 시각. */
    private long lastHeartbeatOf(String boot) throws SQLException {
        String hb = meta("heartbeat");
        if (hb != null) {
            int at = hb.lastIndexOf('@');
            if (at > 0 && hb.substring(0, at).equals(boot)) {
                try {
                    return Long.parseLong(hb.substring(at + 1));
                } catch (NumberFormatException ignored) {
                    // 아래로
                }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COALESCE(last_heartbeat_ms, started_ms) FROM boots WHERE boot_id = ?")) {
            ps.setString(1, boot);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /** 켜져 있는 동안 주기적으로 호출. */
    public void heartbeat() throws SQLException {
        write(() -> {
            requireBoot();
            long now = clock.getAsLong();
            putMeta("heartbeat", bootId + "@" + now);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE boots SET last_heartbeat_ms = ? WHERE boot_id = ?")) {
                ps.setLong(1, now);
                ps.setString(2, bootId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** 정상 종료: clean_shutdown 을 기록하고 닫습니다. 이후 모든 변경은 CLOSED 로 실패합니다. */
    public void closeClean() throws SQLException {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            try {
                if (bootId != null) {
                    write(() -> {
                        long now = clock.getAsLong();
                        try (PreparedStatement ps = conn.prepareStatement(
                                "UPDATE boots SET status = ?, ended_ms = ?, last_heartbeat_ms = ? WHERE boot_id = ?")) {
                            ps.setString(1, BootStatus.CLEAN);
                            ps.setLong(2, now);
                            ps.setLong(3, now);
                            ps.setString(4, bootId);
                            ps.executeUpdate();
                        }
                        putMeta("heartbeat", bootId + "@" + now);
                        putMeta("clean_shutdown", bootId);
                        return null;
                    });
                }
                try (Statement st = conn.createStatement()) {
                    st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                }
            } finally {
                closed = true;
                conn.close();
            }
        } finally {
            lock.unlock();
        }
    }

    /** 정상 종료 기록 없이 닫습니다 (테스트에서 강제 종료 흉내, 또는 시작 실패 시). */
    @Override
    public void close() {
        lock.lock();
        try {
            closed = true;
            closeQuietly(conn);
        } finally {
            lock.unlock();
        }
    }

    public boolean isClosed() {
        return closed;
    }

    public String bootId() {
        return bootId;
    }

    // ------------------------------------------------------------------ 조회

    /** 조회용 사본에서 잔액. 계좌가 없으면 비어 있음. */
    public OptionalLong balance(UUID uuid) {
        Long b = balances.get(uuid);
        return b == null ? OptionalLong.empty() : OptionalLong.of(b);
    }

    public int accountCount() {
        return balances.size();
    }

    public boolean hasAccount(UUID uuid) {
        return balances.containsKey(uuid);
    }

    /** 접속 시 호출: 계좌가 없으면 0원으로 만들고, 닉네임을 갱신합니다 (같은 닉네임의 예전 계좌에서는 이름을 지움). */
    public boolean ensureAccount(UUID uuid, String name) {
        try {
            write(() -> {
                long now = clock.getAsLong();
                if (name != null) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE accounts SET name = NULL WHERE name = ? COLLATE NOCASE AND uuid <> ?")) {
                        ps.setString(1, name);
                        ps.setString(2, uuid.toString());
                        ps.executeUpdate();
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO accounts (uuid, name, balance, updated_at) VALUES (?, ?, 0, ?)"
                                + " ON CONFLICT (uuid) DO UPDATE SET name = COALESCE(excluded.name, name),"
                                + " updated_at = excluded.updated_at")) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, name);
                    ps.setLong(3, now);
                    ps.executeUpdate();
                }
                return null;
            });
            balances.putIfAbsent(uuid, 0L);
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    /** 닉네임으로 계좌 찾기 (대소문자 무시). */
    public Optional<Account> findByName(String name) throws SQLException {
        return read(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT uuid, name, balance FROM accounts WHERE name = ? COLLATE NOCASE"
                            + " ORDER BY updated_at DESC LIMIT 1")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(account(rs)) : Optional.empty();
                }
            }
        });
    }

    public Optional<Account> account(UUID uuid) throws SQLException {
        return read(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT uuid, name, balance FROM accounts WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(account(rs)) : Optional.empty();
                }
            }
        });
    }

    /** 잔액 순위 (0원 제외). */
    public List<Account> top(int limit) throws SQLException {
        return read(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT uuid, name, balance FROM accounts WHERE balance > 0"
                            + " ORDER BY balance DESC, name COLLATE NOCASE LIMIT ?")) {
                ps.setInt(1, limit);
                List<Account> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(account(rs));
                    }
                }
                return out;
            }
        });
    }

    /** 한 계좌의 거래 기록, 최신순. */
    public List<TxRecord> history(UUID uuid, int offset, int limit) throws SQLException {
        return read(() -> {
            try (PreparedStatement ps = conn.prepareStatement(TX_SELECT
                    + " WHERE t.from_uuid = ? OR t.to_uuid = ? ORDER BY t.id DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, uuid.toString());
                ps.setInt(3, limit);
                ps.setInt(4, offset);
                List<TxRecord> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(tx(rs));
                    }
                }
                return out;
            }
        });
    }

    public long historyCount(UUID uuid) throws SQLException {
        return read(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM transactions WHERE from_uuid = ? OR to_uuid = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    public Optional<TxRecord> transaction(long id) throws SQLException {
        return read(() -> findTx(id));
    }

    /** 거래 기록 전체 수 (테스트·점검용). */
    public long transactionCount() throws SQLException {
        return read(() -> {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM transactions")) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }

    // ------------------------------------------------------------------ 잔액 변경

    /** 입금. 계좌가 없으면 만듭니다. */
    public Outcome deposit(UUID who, String name, long amount, TxType type, String source, String context) {
        if (amount <= 0 || amount > Amounts.MAX) {
            return Outcome.fail(Failure.INVALID_AMOUNT, current(who));
        }
        return change(() -> {
            long cur = balanceForUpdate(who, name, true);
            long next = cur + amount;
            if (next > Amounts.MAX) {
                throw new Rejected(Failure.LIMIT_EXCEEDED, cur);
            }
            setBalance(who, next);
            long id = insertTx(type, null, who, amount, null, next, source, context, null);
            return new Applied(id, next, Map.of(who, next));
        });
    }

    /** 출금. 잔액이 부족하거나 계좌가 없으면 실패. */
    public Outcome withdraw(UUID who, long amount, TxType type, String source, String context) {
        if (amount <= 0 || amount > Amounts.MAX) {
            return Outcome.fail(Failure.INVALID_AMOUNT, current(who));
        }
        return change(() -> {
            long cur = balanceForUpdate(who, null, false);
            if (cur < 0) {
                throw new Rejected(Failure.INSUFFICIENT_FUNDS, 0);
            }
            if (cur < amount) {
                throw new Rejected(Failure.INSUFFICIENT_FUNDS, cur);
            }
            long next = cur - amount;
            setBalance(who, next);
            long id = insertTx(type, who, null, amount, next, null, source, context, null);
            return new Applied(id, next, Map.of(who, next));
        });
    }

    /** 송금: 출금·입금·기록이 한 트랜잭션. 받는 사람 계좌가 있어야 합니다. */
    public Outcome transfer(UUID from, UUID to, long amount, String source, String context) {
        if (from.equals(to)) {
            return Outcome.fail(Failure.SELF_TRANSFER, current(from));
        }
        if (amount <= 0 || amount > Amounts.MAX) {
            return Outcome.fail(Failure.INVALID_AMOUNT, current(from));
        }
        return change(() -> {
            long fromCur = balanceForUpdate(from, null, false);
            if (fromCur < 0) {
                throw new Rejected(Failure.INSUFFICIENT_FUNDS, 0);
            }
            long toCur = balanceForUpdate(to, null, false);
            if (toCur < 0) {
                throw new Rejected(Failure.NO_ACCOUNT, fromCur);
            }
            if (fromCur < amount) {
                throw new Rejected(Failure.INSUFFICIENT_FUNDS, fromCur);
            }
            long toNext = toCur + amount;
            if (toNext > Amounts.MAX) {
                throw new Rejected(Failure.LIMIT_EXCEEDED, fromCur);
            }
            long fromNext = fromCur - amount;
            setBalance(from, fromNext);
            setBalance(to, toNext);
            long id = insertTx(TxType.PAY, from, to, amount, fromNext, toNext, source, context, null);
            return new Applied(id, fromNext, Map.of(from, fromNext, to, toNext));
        });
    }

    /** 관리자 잔액 설정. 계좌가 있어야 합니다. */
    public Outcome set(UUID who, long amount, String source, String context) {
        if (amount < 0 || amount > Amounts.MAX) {
            return Outcome.fail(Failure.INVALID_AMOUNT, current(who));
        }
        return change(() -> {
            long cur = balanceForUpdate(who, null, false);
            if (cur < 0) {
                throw new Rejected(Failure.NO_ACCOUNT, 0);
            }
            setBalance(who, amount);
            long id = amount >= cur
                    ? insertTx(TxType.ADMIN_SET, null, who, amount - cur, null, amount, source, context, null)
                    : insertTx(TxType.ADMIN_SET, who, null, cur - amount, amount, null, source, context, null);
            return new Applied(id, amount, Map.of(who, amount));
        });
    }

    /**
     * /bank refund: 비정상 종료된 기동의 EconomyShopGUI 출금을 한 번만 되돌립니다 (ECONOMY.md 2.4).
     * 조건이 하나라도 맞지 않으면 실패와 이유를 반환합니다. 시간 조건은 관리자가 확인합니다.
     */
    public Outcome refund(long txId, String source, String context) {
        return change(() -> {
            TxRecord orig = findTx(txId).orElseThrow(() -> new Rejected(Failure.TX_NOT_FOUND, 0));
            if (orig.type() != TxType.WITHDRAW || orig.from() == null) {
                throw new Rejected(Failure.NOT_WITHDRAW, 0, orig.type().label());
            }
            if (!Sources.SHOP.equals(orig.source())) {
                throw new Rejected(Failure.NOT_SHOP, 0, "source=" + orig.source());
            }
            if (!BootStatus.UNCLEAN.equals(orig.bootStatus())) {
                throw new Rejected(Failure.CLEAN_BOOT, 0, "기동 상태=" + orig.bootStatus());
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM transactions WHERE ref = ?")) {
                ps.setLong(1, txId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        throw new Rejected(Failure.ALREADY_REFUNDED, 0, "환불 거래 #" + rs.getLong(1));
                    }
                }
            }
            UUID who = orig.from();
            long cur = balanceForUpdate(who, null, true);
            long next = cur + orig.amount();
            if (next > Amounts.MAX) {
                throw new Rejected(Failure.LIMIT_EXCEEDED, cur);
            }
            setBalance(who, next);
            long id = insertTx(TxType.REFUND, null, who, orig.amount(), null, next, source, context, txId);
            return new Applied(id, next, Map.of(who, next));
        });
    }

    // ------------------------------------------------------------------ 내부

    private record Applied(long txId, long balance, Map<UUID, Long> newBalances) {
    }

    /** 트랜잭션 안에서 던지면 롤백하고 그 이유로 실패를 반환. */
    private static final class Rejected extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final Failure failure;
        final long balance;
        final String detail;

        Rejected(Failure failure, long balance) {
            this(failure, balance, "");
        }

        Rejected(Failure failure, long balance, String detail) {
            super(failure.name(), null, false, false);
            this.failure = failure;
            this.balance = balance;
            this.detail = detail;
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run() throws SQLException;
    }

    private Outcome change(SqlWork<Applied> work) {
        Applied applied;
        try {
            applied = write(() -> {
                requireBoot();
                return work.run();
            });
        } catch (Rejected r) {
            return Outcome.fail(r.failure, r.balance, r.detail);
        } catch (ClosedException e) {
            return Outcome.fail(Failure.CLOSED, 0);
        } catch (SQLException | RuntimeException e) {
            return Outcome.fail(Failure.STORAGE_ERROR, 0, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        // 커밋이 끝난 뒤에만 조회용 사본 갱신
        balances.putAll(applied.newBalances());
        return Outcome.success(applied.balance(), applied.txId());
    }

    private static final class ClosedException extends SQLException {
        private static final long serialVersionUID = 1L;

        ClosedException() {
            super("closed");
        }
    }

    /** 쓰기 트랜잭션. 커밋이 끝나야 반환하고, 예외면 롤백. */
    private <T> T write(SqlWork<T> work) throws SQLException {
        lock.lock();
        try {
            if (closed) {
                throw new ClosedException();
            }
            if (!conn.getAutoCommit()) {
                // 이미 이 연결의 트랜잭션 안 (중첩 호출)
                return work.run();
            }
            conn.setAutoCommit(false);
            try {
                T result = work.run();
                conn.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    conn.rollback();
                } catch (SQLException suppressed) {
                    e.addSuppressed(suppressed);
                }
                throw e;
            } finally {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // 연결이 끊긴 경우. 다음 호출에서 오류가 납니다.
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private <T> T read(SqlWork<T> work) throws SQLException {
        lock.lock();
        try {
            if (closed) {
                throw new ClosedException();
            }
            return work.run();
        } finally {
            lock.unlock();
        }
    }

    private void requireBoot() throws SQLException {
        if (bootId == null) {
            throw new SQLException("startBoot 전에는 잔액을 바꿀 수 없음");
        }
    }

    private long current(UUID uuid) {
        Long b = balances.get(uuid);
        return b == null ? 0 : b;
    }

    /** 트랜잭션 안에서 DB 의 잔액 (사본이 아니라 DB 값). 계좌가 없으면 create 가 true 일 때 만들고 0, 아니면 -1. */
    private long balanceForUpdate(UUID uuid, String name, boolean create) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT balance FROM accounts WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        if (!create) {
            return -1;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO accounts (uuid, name, balance, updated_at) VALUES (?, ?, 0, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setLong(3, clock.getAsLong());
            ps.executeUpdate();
        }
        return 0;
    }

    private void setBalance(UUID uuid, long balance) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE accounts SET balance = ?, updated_at = ? WHERE uuid = ?")) {
            ps.setLong(1, balance);
            ps.setLong(2, clock.getAsLong());
            ps.setString(3, uuid.toString());
            if (ps.executeUpdate() != 1) {
                throw new SQLException("계좌 갱신 실패: " + uuid);
            }
        }
    }

    private long insertTx(TxType type, UUID from, UUID to, long amount, Long fromAfter, Long toAfter,
                          String source, String context, Long ref) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO transactions (time_ms, type, from_uuid, to_uuid, amount, from_balance_after,"
                        + " to_balance_after, source, boot_id, context, ref) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, clock.getAsLong());
            ps.setString(2, type.name());
            setNullable(ps, 3, from == null ? null : from.toString());
            setNullable(ps, 4, to == null ? null : to.toString());
            ps.setLong(5, amount);
            setNullable(ps, 6, fromAfter);
            setNullable(ps, 7, toAfter);
            ps.setString(8, source == null || source.isBlank() ? Sources.UNKNOWN : source);
            ps.setString(9, bootId);
            setNullable(ps, 10, context);
            setNullable(ps, 11, ref);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("거래번호를 받지 못함");
                }
                return keys.getLong(1);
            }
        }
    }

    private static void setNullable(PreparedStatement ps, int i, Object v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.NULL);
        } else if (v instanceof Long l) {
            ps.setLong(i, l);
        } else {
            ps.setString(i, v.toString());
        }
    }

    private static final String TX_SELECT = "SELECT t.id, t.time_ms, t.type, t.from_uuid, t.to_uuid, t.amount,"
            + " t.from_balance_after, t.to_balance_after, t.source, t.boot_id, t.context, t.ref, b.status"
            + " FROM transactions t LEFT JOIN boots b ON b.boot_id = t.boot_id";

    private Optional<TxRecord> findTx(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(TX_SELECT + " WHERE t.id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(tx(rs)) : Optional.empty();
            }
        }
    }

    private static TxRecord tx(ResultSet rs) throws SQLException {
        String from = rs.getString(4);
        String to = rs.getString(5);
        long fromAfter = rs.getLong(7);
        boolean fromAfterNull = rs.wasNull();
        long toAfter = rs.getLong(8);
        boolean toAfterNull = rs.wasNull();
        String status = rs.getString(13);
        return new TxRecord(rs.getLong(1), rs.getLong(2), TxType.valueOf(rs.getString(3)),
                from == null ? null : UUID.fromString(from), to == null ? null : UUID.fromString(to),
                rs.getLong(6), fromAfterNull ? null : fromAfter, toAfterNull ? null : toAfter,
                rs.getString(9), rs.getString(10), rs.getString(11), rs.getLong(12),
                status == null ? "unknown" : status);
    }

    private static Account account(ResultSet rs) throws SQLException {
        return new Account(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3));
    }

    private String meta(String key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void putMeta(String key, String value) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    /** 그 기동의 장애 추정 시각 (비정상 종료로 판정된 기동만, 아니면 비어 있음). */
    public OptionalLong crashEstimate(String boot) throws SQLException {
        return read(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT crash_estimated_ms FROM boots WHERE boot_id = ? AND status = ?")) {
                ps.setString(1, boot);
                ps.setString(2, BootStatus.UNCLEAN);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long v = rs.getLong(1);
                        if (!rs.wasNull()) {
                            return OptionalLong.of(v);
                        }
                    }
                    return OptionalLong.empty();
                }
            }
        });
    }

    /** 테스트·점검용 meta 조회. */
    public String readMeta(String key) throws SQLException {
        return read(() -> meta(key));
    }

    private static void closeQuietly(Connection c) {
        try {
            c.close();
        } catch (SQLException ignored) {
            // 이미 닫힘
        }
    }
}
