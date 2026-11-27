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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
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
    static final int SCHEMA_VERSION = 4;
    /** 다른 프로세스가 쓰기 잠금을 잡고 있을 때 기다리는 시간 */
    static final int BUSY_TIMEOUT_MS = 2000;

    private static final String TRADE_SETTLEMENTS_BODY = """
            (
              ext_ref  TEXT PRIMARY KEY,
              a_uuid   TEXT NOT NULL,
              b_uuid   TEXT NOT NULL,
              a_pays   INTEGER NOT NULL CHECK (a_pays >= 0),
              b_pays   INTEGER NOT NULL CHECK (b_pays >= 0),
              tx_a_id  INTEGER REFERENCES transactions (id),
              tx_b_id  INTEGER REFERENCES transactions (id),
              source   TEXT NOT NULL,
              boot_id  TEXT NOT NULL,
              time_ms  INTEGER NOT NULL,
              context  TEXT,
              CHECK (a_uuid <> b_uuid),
              CHECK (a_pays > 0 OR b_pays > 0)
            )""";

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
              status             TEXT NOT NULL,
              world_dir          TEXT,
              unclean_reason     TEXT
            );
            CREATE TABLE shutdown_players (
              boot_id TEXT NOT NULL,
              uuid    TEXT NOT NULL,
              PRIMARY KEY (boot_id, uuid)
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
              ext_ref            TEXT,
              ref                INTEGER UNIQUE REFERENCES transactions (id)
            );
            CREATE UNIQUE INDEX transactions_ext_ref ON transactions(ext_ref);
            CREATE INDEX transactions_from ON transactions (from_uuid, id);
            CREATE INDEX transactions_to ON transactions (to_uuid, id);
            """ + "CREATE TABLE trade_settlements " + TRADE_SETTLEMENTS_BODY + ";\n";

    private final Connection conn;
    private final LongSupplier clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<UUID, Long> balances = new ConcurrentHashMap<>();
    private volatile String bootId;
    private volatile boolean closed;
    /** 커밋·롤백 결과를 확신할 수 없어 연결을 닫았을 때의 이유. 이후 모든 변경은 STORAGE_ERROR. */
    private volatile String brokenReason;
    /** write() 가 BEGIN 한 트랜잭션이 열려 있는지 (lock 안에서만 읽고 씀) */
    private boolean inTx;

    private BankStore(Connection conn, LongSupplier clock) throws SQLException {
        this.conn = conn;
        this.clock = clock;
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MS);
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
            store.migrate();
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
        // 서버 클래스로더 문제를 피하려고 DriverManager 대신 드라이버를 직접 사용
        return config.createConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
    }

    /**
     * 예전 스키마(v1~v3)에 누락된 칸·테이블을 더함(추가만). 단 v4 의 원장 {@code TRADE} 행은 v3 이하 JAR 의 {@code /bank log} 가
     * 읽지 못하므로, v4 이후 JAR 만 되돌리는 롤백은 안 되고 같은 시점 전체 스냅샷 복원으로만 되돌린다 (TRADE.md 4장).
     */
    private void migrate() throws SQLException {
        write(() -> {
            boolean hasWorldDir = false;
            boolean hasReason = false;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("PRAGMA table_info(boots)")) {
                while (rs.next()) {
                    hasWorldDir |= "world_dir".equals(rs.getString("name"));
                    hasReason |= "unclean_reason".equals(rs.getString("name"));
                }
            }
            try (Statement st = conn.createStatement()) {
                if (!hasWorldDir) {
                    st.execute("ALTER TABLE boots ADD COLUMN world_dir TEXT");
                }
                if (!hasReason) {
                    st.execute("ALTER TABLE boots ADD COLUMN unclean_reason TEXT");
                }
                st.execute("CREATE TABLE IF NOT EXISTS shutdown_players (boot_id TEXT NOT NULL,"
                        + " uuid TEXT NOT NULL, PRIMARY KEY (boot_id, uuid))");
            }
            boolean hasRef = false;
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(transactions)")) {
                while (rs.next()) hasRef |= "ext_ref".equals(rs.getString("name"));
            }
            if (!hasRef) exec("ALTER TABLE transactions ADD COLUMN ext_ref TEXT");
            exec("CREATE UNIQUE INDEX IF NOT EXISTS transactions_ext_ref ON transactions(ext_ref)");
            exec("CREATE TABLE IF NOT EXISTS trade_settlements " + TRADE_SETTLEMENTS_BODY);
            String schema = meta("schema");
            if (schema == null || Integer.parseInt(schema) < SCHEMA_VERSION) {
                putMeta("schema", String.valueOf(SCHEMA_VERSION));
            }
            return null;
        });
    }

    private void loadBalances() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT uuid, balance FROM accounts")) {
            while (rs.next()) {
                balances.put(UUID.fromString(rs.getString(1)), rs.getLong(2));
            }
        }
    }

    /** 종료 때 접속해 있던 플레이어의 데이터가 Bank 종료 뒤에 저장됐는지 확인하는 방법. */
    @FunctionalInterface
    public interface SaveCheck {
        /** worldDir 의 플레이어 파일이 sinceMs 이후에 저장됐는지. 확인할 수 없으면 false. */
        boolean savedAfter(String worldDir, UUID player, long sinceMs);
    }

    /** 확인 수단이 없을 때: 저장됐다고 보지 않음 (환불 대상에서 빠지지 않게). */
    public static final SaveCheck NO_SAVE_EVIDENCE = (dir, player, since) -> false;

    /** {@link #startBoot(String, SaveCheck)} 를 저장 근거 없이 호출. */
    public BootReport startBoot(String newBootId) throws SQLException {
        return startBoot(newBootId, NO_SAVE_EVIDENCE);
    }

    /**
     * 이번 기동을 시작하면서 이전 기동을 판정합니다.
     * <ul>
     *   <li>정상 종료(clean_shutdown) 기록이 없음 → 비정상 종료. 장애 추정 시각 = 마지막 heartbeat (마지막 거래 시각 아님)</li>
     *   <li>Bank 는 정상 종료했지만, 그때 접속해 있던 플레이어 중 그 뒤에 플레이어 파일이 저장되지 않은 사람이 있음
     *       → 비정상 종료. Paper 는 플러그인을 끈 뒤에 플레이어를 저장하므로, 그 사이에 꺼지면 구매한 상품이 유실될 수 있음.
     *       장애 추정 시각 = Bank 종료 시각</li>
     *   <li>둘 다 아니면 정상 종료</li>
     * </ul>
     */
    public BootReport startBoot(String newBootId, SaveCheck saveCheck) throws SQLException {
        return write(() -> {
            long now = clock.getAsLong();
            String prev = meta("boot_id");
            String clean = meta("clean_shutdown");
            String reason = null;
            long crashMs = 0;
            List<UUID> unsaved = List.of();
            if (prev != null && !prev.equals(clean)) {
                reason = BootReport.NO_CLEAN_SHUTDOWN;
                crashMs = lastHeartbeatOf(prev);
            } else if (prev != null) {
                String status = null;
                long ended = 0;
                String worldDir = null;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT status, ended_ms, world_dir FROM boots WHERE boot_id = ?")) {
                    ps.setString(1, prev);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            status = rs.getString(1);
                            ended = rs.getLong(2);
                            worldDir = rs.getString(3);
                        }
                    }
                }
                if (BootStatus.CLOSED.equals(status)) {
                    List<UUID> missing = new ArrayList<>();
                    for (UUID player : shutdownPlayers(prev)) {
                        boolean saved;
                        try {
                            saved = worldDir != null && saveCheck.savedAfter(worldDir, player, ended);
                        } catch (RuntimeException e) {
                            saved = false;
                        }
                        if (!saved) {
                            missing.add(player);
                        }
                    }
                    if (missing.isEmpty()) {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "UPDATE boots SET status = ? WHERE boot_id = ?")) {
                            ps.setString(1, BootStatus.CLEAN);
                            ps.setString(2, prev);
                            ps.executeUpdate();
                        }
                    } else {
                        reason = BootReport.PLAYER_SAVE_MISSING;
                        crashMs = ended;
                        unsaved = List.copyOf(missing);
                    }
                }
            }
            if (reason != null) {
                markUnclean(prev, crashMs, reason);
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
            return new BootReport(prev, reason != null, crashMs, reason, unsaved);
        });
    }

    private void markUnclean(String boot, long crashMs, String reason) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE boots SET status = ?, crash_estimated_ms = ?, unclean_reason = ? WHERE boot_id = ?")) {
            ps.setString(1, BootStatus.UNCLEAN);
            ps.setLong(2, crashMs);
            ps.setString(3, reason);
            ps.setString(4, boot);
            if (ps.executeUpdate() == 0) {
                // boots 줄이 없는 경우(이론상 없음)에도 환불 판정이 가능하도록 남김
                try (PreparedStatement ins = conn.prepareStatement(
                        "INSERT INTO boots (boot_id, started_ms, last_heartbeat_ms, crash_estimated_ms, status,"
                                + " unclean_reason) VALUES (?, ?, ?, ?, ?, ?)")) {
                    ins.setString(1, boot);
                    ins.setLong(2, crashMs);
                    ins.setLong(3, crashMs);
                    ins.setLong(4, crashMs);
                    ins.setString(5, BootStatus.UNCLEAN);
                    ins.setString(6, reason);
                    ins.executeUpdate();
                }
            }
        }
    }

    private List<UUID> shutdownPlayers(String boot) throws SQLException {
        List<UUID> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT uuid FROM shutdown_players WHERE boot_id = ?")) {
            ps.setString(1, boot);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(UUID.fromString(rs.getString(1)));
                }
            }
        }
        return out;
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

    /** 접속자 없이 정상 종료 (테스트용). */
    public void closeClean() throws SQLException {
        closeClean(List.of(), null);
    }

    /**
     * Bank 정상 종료: clean_shutdown 을 기록하고 닫습니다. 이후 모든 변경은 CLOSED 로 실패합니다.
     * 이 시점에는 아직 서버가 플레이어를 저장하기 전이므로, 기동 상태는 clean 이 아니라 closed 로 두고
     * 접속해 있던 플레이어 목록과 월드 폴더를 남깁니다. 다음 기동에서 그 플레이어 파일이 이 시각 뒤에
     * 저장됐는지 확인한 뒤에야 clean 으로 판정합니다 ({@link #startBoot(String, SaveCheck)}).
     *
     * @param onlinePlayers 종료 순간 접속해 있던 플레이어
     * @param worldDir      플레이어 파일이 있는 기본 월드 폴더 (모르면 null → 접속자가 있으면 비정상 종료로 판정)
     */
    public void closeClean(Collection<UUID> onlinePlayers, String worldDir) throws SQLException {
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
                                "UPDATE boots SET status = ?, ended_ms = ?, last_heartbeat_ms = ?, world_dir = ?"
                                        + " WHERE boot_id = ?")) {
                            ps.setString(1, BootStatus.CLOSED);
                            ps.setLong(2, now);
                            ps.setLong(3, now);
                            setNullable(ps, 4, worldDir);
                            ps.setString(5, bootId);
                            ps.executeUpdate();
                        }
                        try (PreparedStatement ps = conn.prepareStatement(
                                "INSERT OR IGNORE INTO shutdown_players (boot_id, uuid) VALUES (?, ?)")) {
                            for (UUID player : onlinePlayers) {
                                ps.setString(1, bootId);
                                ps.setString(2, player.toString());
                                ps.executeUpdate();
                            }
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
                closeQuietly(conn);
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
            }, ignored -> balances.putIfAbsent(uuid, 0L));
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
            if (Sources.MARKET.equals(orig.source())) {
                // Market 출금은 market.db 주문 상태와 한 쌍으로 움직여야 하므로 /market resolve 로만 환불 (ECONOMY.md 2.4, 13.6).
                // 여기서 돈만 돌려주면 market.db 는 CHARGED 로 남아 접속·1분 복구·/market claim 이 상품까지 지급함
                throw new Rejected(Failure.MARKET_ORDER, 0, "/market resolve <Market 거래 ID> refund");
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

    public enum ExternalStatus { OK, REJECTED, UNKNOWN }
    public enum LookupStatus { FOUND, NOT_FOUND, ERROR }
    public record Receipt(ExternalStatus status, long txId) { }
    public record Lookup(LookupStatus status, long txId) { }

    /** 거래번호로 이미 커밋된 원장을 조회한다. 조회 실패는 거래 없음과 구분한다. */
    public Lookup findByExtRef(String extRef) {
        lock.lock();
        try {
            ensureUsable();
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM transactions WHERE ext_ref=?")) {
                ps.setString(1, extRef);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new Lookup(LookupStatus.FOUND, rs.getLong(1)) : new Lookup(LookupStatus.NOT_FOUND, 0);
                }
            }
        } catch (SQLException e) {
            return new Lookup(LookupStatus.ERROR, 0);
        } finally {
            lock.unlock();
        }
    }

    /** 같은 요청의 재시도는 첫 거래번호를 반환한다. 거래번호의 용도를 바꾸는 요청은 거절한다. */
    public Receipt external(UUID who, long amount, String source, String extRef, TxType type) {
        return external(who, amount, source, extRef, type, null);
    }

    /** 거래 상황(context)을 함께 남기는 외부 멱등 입출금 API (ECONOMY.md 13.6). */
    public Receipt external(UUID who, long amount, String source, String extRef, TxType type, String context) {
        if (who == null || amount <= 0 || amount > Amounts.MAX || source == null || source.isBlank()
                || extRef == null || extRef.isBlank() || (type != TxType.DEPOSIT && type != TxType.WITHDRAW)) {
            return new Receipt(ExternalStatus.REJECTED, 0);
        }
        boolean credit = type == TxType.DEPOSIT;
        try {
            Applied applied = write(() -> {
                requireBoot();
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT id,type,from_uuid,to_uuid,amount,source FROM transactions WHERE ext_ref=?")) {
                    ps.setString(1, extRef);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            boolean same = type.name().equals(rs.getString(2)) && who.toString().equals(rs.getString(credit ? 4 : 3))
                                    && amount == rs.getLong(5) && source.equals(rs.getString(6));
                            if (!same) throw new Rejected(Failure.INVALID_AMOUNT, 0);
                            return new Applied(rs.getLong(1), 0, Map.of());
                        }
                    }
                }
                if (hasTradeExtRef(extRef)) {
                    throw new Rejected(Failure.INVALID_AMOUNT, 0);
                }
                long balance = balanceForUpdate(who, null, credit);
                if (balance < 0 || (!credit && balance < amount)) throw new Rejected(Failure.INSUFFICIENT_FUNDS, 0);
                long next = credit ? balance + amount : balance - amount;
                if (next > Amounts.MAX) throw new Rejected(Failure.LIMIT_EXCEEDED, balance);
                setBalance(who, next);
                long id = insertTx(type, credit ? null : who, credit ? who : null, amount,
                        credit ? null : next, credit ? next : null, source, context, null);
                try (PreparedStatement ps = conn.prepareStatement("UPDATE transactions SET ext_ref=? WHERE id=?")) {
                    ps.setString(1, extRef);
                    ps.setLong(2, id);
                    ps.executeUpdate();
                }
                return new Applied(id, next, Map.of(who, next));
            }, a -> balances.putAll(a.newBalances()));
            return new Receipt(ExternalStatus.OK, applied.txId());
        } catch (SQLException | Rejected e) {
            return new Receipt(brokenReason == null ? ExternalStatus.REJECTED : ExternalStatus.UNKNOWN, 0);
        }
    }

    // ------------------------------------------------------------------ Trade 1:1 교환 정산 API

    private record TradeApplied(long txAId, long txBId, boolean idempotent, Map<UUID, Long> newBalances) {
    }

    private static final class TradeRejected extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final TradeFailure failure;
        final UUID affectedPlayer;
        final String detail;

        TradeRejected(TradeFailure failure, UUID affectedPlayer, String detail) {
            super(failure.name(), null, false, false);
            this.failure = failure;
            this.affectedPlayer = affectedPlayer;
            this.detail = detail;
        }
    }

    private static final class TradeMismatch extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String detail;

        TradeMismatch(String detail) {
            super(detail, null, false, false);
            this.detail = detail;
        }
    }

    /** 잠금 모드({@code store == null})를 포함한 1:1 교환 정산 진입점 (TRADE.md 4장). */
    public static TradeOutcome settleTrade(BankStore store, String lockReason,
                                           String extRef, UUID a, long aPays, UUID b, long bPays,
                                           String source, String context) {
        if (store == null) {
            return TradeOutcome.rejected(TradeFailure.LOCKED, null, lockReason == null ? "" : lockReason);
        }
        return store.settleTrade(extRef, a, aPays, b, bPays, source, context);
    }

    /** 잠금 모드({@code store == null})를 포함한 1:1 교환 정산 조회 진입점 (TRADE.md 4장). */
    public static TradeLookup findTradeByExtRef(BankStore store, String lockReason, String extRef) {
        if (store == null) {
            return TradeLookup.error(lockReason == null || lockReason.isBlank()
                    ? TradeFailure.LOCKED.message() : lockReason);
        }
        return store.findTradeByExtRef(extRef);
    }

    /**
     * 1:1 교환 정산: 한 SQLite 트랜잭션(BEGIN IMMEDIATE) 안에서 양쪽 잔액 검사·이동·기록을 처리합니다 (TRADE.md 4장).
     *
     * <p>같은 {@code extRef}가 이미 있으면 기록된 두 UUID·양쪽 금액·출처가 모두 일치할 때만 기존 결과(OK, {@code idempotent=true})를
     * 반환하고, 하나라도 다르면 {@link TradeOutcome.Status#MISMATCH}를 반환합니다.
     * 계좌가 없는 쪽은 지급액이 0이고 받는 금액이 양수일 때만 기존 {@link #deposit}과 같이 0원 계좌를 새로 만듭니다.
     */
    public TradeOutcome settleTrade(String extRef, UUID a, long aPays, UUID b, long bPays,
                                    String source, String context) {
        if (extRef == null || extRef.isBlank() || a == null || b == null || source == null || source.isBlank()) {
            return TradeOutcome.rejected(TradeFailure.INVALID_REQUEST, null, "");
        }
        boolean selfTrade = a.equals(b);
        boolean invalidAmount = aPays < 0 || aPays > Amounts.MAX || bPays < 0 || bPays > Amounts.MAX
                || (aPays == 0 && bPays == 0);
        try {
            TradeApplied applied = write(() -> {
                requireBoot();
                // 기존 ref 비교를 입력 검사보다 먼저: 이미 쓰인 ref 에 다른 값이 오면 형식이 틀려도 MISMATCH (QA ④-1)
                Optional<TradeLookup.TradeRecord> existing = findTradeRecord(extRef);
                if (existing.isPresent()) {
                    TradeLookup.TradeRecord rec = existing.get();
                    if (!selfTrade && !invalidAmount && rec.matches(a, aPays, b, bPays) && source.equals(rec.source())) {
                        return new TradeApplied(rec.txAId(), rec.txBId(), true, Map.of());
                    }
                    throw new TradeMismatch("기존=" + rec.a() + ":" + rec.aPays() + "/" + rec.b() + ":" + rec.bPays()
                            + " (" + rec.source() + ")");
                }
                if (hasTransactionExtRef(extRef)) {
                    throw new TradeMismatch("다른 종류의 거래에 이미 사용된 extRef: " + extRef);
                }
                if (selfTrade) {
                    throw new TradeRejected(TradeFailure.SELF_TRADE, a, "");
                }
                if (invalidAmount) {
                    throw new TradeRejected(TradeFailure.INVALID_AMOUNT, null, "");
                }

                long aRaw = balanceForUpdate(a, null, false);
                boolean aExists = aRaw >= 0;
                long aCur = aExists ? aRaw : 0L;
                long bRaw = balanceForUpdate(b, null, false);
                boolean bExists = bRaw >= 0;
                long bCur = bExists ? bRaw : 0L;

                if (aPays > 0 && aCur < aPays) {
                    throw new TradeRejected(TradeFailure.INSUFFICIENT, a, "현재 잔액 " + Amounts.format(aCur));
                }
                if (bPays > 0 && bCur < bPays) {
                    throw new TradeRejected(TradeFailure.INSUFFICIENT, b, "현재 잔액 " + Amounts.format(bCur));
                }

                long aNext = aCur - aPays + bPays;
                long bNext = bCur - bPays + aPays;
                if (aNext > Amounts.MAX) {
                    throw new TradeRejected(TradeFailure.BALANCE_LIMIT, a, "");
                }
                if (bNext > Amounts.MAX) {
                    throw new TradeRejected(TradeFailure.BALANCE_LIMIT, b, "");
                }

                // 지급액이 0이고 받는 쪽만 계좌가 없을 수 있음 (기존 deposit 계좌 생성 규칙과 동일)
                if (!aExists) {
                    balanceForUpdate(a, null, true);
                }
                if (!bExists) {
                    balanceForUpdate(b, null, true);
                }
                setBalance(a, aNext);
                setBalance(b, bNext);

                Long txAId = null;
                Long txBId = null;
                if (aPays > 0 && bPays > 0) {
                    long aMid = aCur - aPays;
                    long bMid = bCur + aPays;
                    txAId = insertTx(TxType.TRADE, a, b, aPays, aMid, bMid, source, context, null);
                    txBId = insertTx(TxType.TRADE, b, a, bPays, bNext, aNext, source, context, null);
                } else if (aPays > 0) {
                    txAId = insertTx(TxType.TRADE, a, b, aPays, aNext, bNext, source, context, null);
                } else {
                    txBId = insertTx(TxType.TRADE, b, a, bPays, bNext, aNext, source, context, null);
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO trade_settlements (ext_ref, a_uuid, b_uuid, a_pays, b_pays,"
                                + " tx_a_id, tx_b_id, source, boot_id, time_ms, context)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, extRef);
                    ps.setString(2, a.toString());
                    ps.setString(3, b.toString());
                    ps.setLong(4, aPays);
                    ps.setLong(5, bPays);
                    setNullable(ps, 6, txAId);
                    setNullable(ps, 7, txBId);
                    ps.setString(8, source);
                    ps.setString(9, bootId);
                    ps.setLong(10, clock.getAsLong());
                    setNullable(ps, 11, context);
                    ps.executeUpdate();
                }

                return new TradeApplied(txAId == null ? 0L : txAId, txBId == null ? 0L : txBId, false,
                        Map.of(a, aNext, b, bNext));
            }, res -> balances.putAll(res.newBalances()));
            return TradeOutcome.ok(applied.txAId(), applied.txBId(), applied.idempotent());
        } catch (TradeRejected r) {
            if (brokenReason != null) {
                return TradeOutcome.unknown(brokenReason);
            }
            return TradeOutcome.rejected(r.failure, r.affectedPlayer, r.detail);
        } catch (TradeMismatch m) {
            if (brokenReason != null) {
                return TradeOutcome.unknown(brokenReason);
            }
            return TradeOutcome.mismatch(m.detail);
        } catch (ClosedException e) {
            return TradeOutcome.rejected(TradeFailure.CLOSED, null, "");
        } catch (SQLException | RuntimeException e) {
            if (brokenReason != null) {
                return TradeOutcome.unknown(brokenReason);
            }
            return TradeOutcome.rejected(TradeFailure.STORAGE_ERROR, null,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 거래번호로 이미 커밋된 1:1 교환 정산 기록을 조회합니다. 조회 오류는 거래 없음과 구분합니다 (TRADE.md 4장). */
    public TradeLookup findTradeByExtRef(String extRef) {
        if (extRef == null || extRef.isBlank()) {
            return TradeLookup.error("extRef 가 비어 있음");
        }
        lock.lock();
        try {
            ensureUsable();
            Optional<TradeLookup.TradeRecord> found = findTradeRecord(extRef);
            if (found.isPresent()) {
                return TradeLookup.found(found.get());
            }
            if (hasTransactionExtRef(extRef)) {
                return TradeLookup.error("다른 종류의 거래에 사용된 extRef: " + extRef);
            }
            return TradeLookup.notFound();
        } catch (SQLException | RuntimeException e) {
            return TradeLookup.error(brokenReason != null ? brokenReason : e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    private Optional<TradeLookup.TradeRecord> findTradeRecord(String extRef) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT ext_ref, a_uuid, a_pays, b_uuid, b_pays, tx_a_id, tx_b_id, source, boot_id, time_ms, context"
                        + " FROM trade_settlements WHERE ext_ref = ?")) {
            ps.setString(1, extRef);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                long txAId = rs.getLong(6);
                if (rs.wasNull()) {
                    txAId = 0L;
                }
                long txBId = rs.getLong(7);
                if (rs.wasNull()) {
                    txBId = 0L;
                }
                return Optional.of(new TradeLookup.TradeRecord(
                        rs.getString(1),
                        UUID.fromString(rs.getString(2)),
                        rs.getLong(3),
                        UUID.fromString(rs.getString(4)),
                        rs.getLong(5),
                        txAId,
                        txBId,
                        rs.getString(8),
                        rs.getString(9),
                        rs.getLong(10),
                        rs.getString(11)));
            }
        }
    }

    private boolean hasTransactionExtRef(String extRef) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM transactions WHERE ext_ref = ?")) {
            ps.setString(1, extRef);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean hasTradeExtRef(String extRef) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM trade_settlements WHERE ext_ref = ?")) {
            ps.setString(1, extRef);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Market 관리자 수동 환불(/market resolve &lt;tx_id&gt; refund, ECONOMY.md 13.6). Market 출금의 유일한 환불 경로입니다
     * ({@link #refund}는 Market 출금을 거부). {@code transactions.ref} 유일 제약으로 거래당 한 번만 환불됩니다.
     */
    public Outcome refundByExtRef(String extRef, String source, String context) {
        if (extRef == null || extRef.isBlank()) {
            return Outcome.fail(Failure.TX_NOT_FOUND, 0);
        }
        return change(() -> {
            long origId;
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM transactions WHERE ext_ref = ?")) {
                ps.setString(1, extRef);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new Rejected(Failure.TX_NOT_FOUND, 0);
                    }
                    origId = rs.getLong(1);
                }
            }
            TxRecord orig = findTx(origId).orElseThrow(() -> new Rejected(Failure.TX_NOT_FOUND, 0));
            if (orig.type() != TxType.WITHDRAW || orig.from() == null) {
                throw new Rejected(Failure.NOT_WITHDRAW, 0, orig.type().label());
            }
            if (!Sources.MARKET.equals(orig.source())) {
                throw new Rejected(Failure.NOT_SHOP, 0, "source=" + orig.source());
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM transactions WHERE ref = ?")) {
                ps.setLong(1, origId);
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
            long id = insertTx(TxType.REFUND, null, who, orig.amount(), null, next, source, context, origId);
            return new Applied(id, next, Map.of(who, next));
        });
    }

    private Outcome change(SqlWork<Applied> work) {
        Applied applied;
        try {
            applied = write(() -> {
                requireBoot();
                return work.run();
            }, // 커밋이 끝난 뒤, 같은 락 안에서 조회용 사본 갱신 → 커밋 순서와 조회 값이 항상 일치
                    a -> balances.putAll(a.newBalances()));
        } catch (Rejected r) {
            return Outcome.fail(r.failure, r.balance, r.detail);
        } catch (ClosedException e) {
            return Outcome.fail(Failure.CLOSED, 0);
        } catch (SQLException | RuntimeException e) {
            return Outcome.fail(Failure.STORAGE_ERROR, 0, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return Outcome.success(applied.balance(), applied.txId());
    }

    private static final class ClosedException extends SQLException {
        private static final long serialVersionUID = 1L;

        ClosedException() {
            super("closed");
        }
    }

    private <T> T write(SqlWork<T> work) throws SQLException {
        return write(work, null);
    }

    /**
     * 쓰기 트랜잭션. 시작(BEGIN IMMEDIATE)·커밋·롤백을 여기서만 직접 실행합니다.
     * JDBC 의 setAutoCommit 은 쓰지 않습니다: sqlite-jdbc 는 BEGIN 이 실패해도 autoCommit=false 로 남아
     * 다음 요청이 트랜잭션 없이 실행될 수 있기 때문입니다 (PR #8 리뷰 P1).
     * <ul>
     *   <li>BEGIN 실패(예: 다른 연결이 쓰기 잠금): 트랜잭션이 남아 있지 않게 ROLLBACK 을 시도하고 실패 반환. 연결은 계속 씀</li>
     *   <li>작업 중 오류: ROLLBACK. ROLLBACK 도 실패하면 상태를 알 수 없으므로 연결을 닫음</li>
     *   <li>COMMIT 실패: 반영 여부를 확신할 수 없으므로 연결을 닫음. 이후 모든 변경은 실패하고,
     *       재시작하면 DB 의 실제 값을 다시 읽음</li>
     * </ul>
     *
     * @param afterCommit 커밋이 끝난 뒤 같은 락 안에서 실행 (조회용 사본 갱신)
     */
    private <T> T write(SqlWork<T> work, Consumer<T> afterCommit) throws SQLException {
        lock.lock();
        try {
            ensureUsable();
            if (inTx) {
                throw new SQLException("이미 트랜잭션 안에서 다시 쓰기를 시작함 (버그)");
            }
            try {
                exec("BEGIN IMMEDIATE");
            } catch (SQLException e) {
                clearStrayTransaction(e);
                throw e;
            }
            inTx = true;
            T result;
            try {
                result = work.run();
            } catch (SQLException | RuntimeException e) {
                try {
                    exec("ROLLBACK");
                    inTx = false;
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                    breakConnection("ROLLBACK 실패: " + rollbackError.getMessage());
                }
                throw e;
            }
            try {
                exec("COMMIT");
            } catch (SQLException e) {
                breakConnection("COMMIT 실패: " + e.getMessage());
                throw e;
            }
            inTx = false;
            if (afterCommit != null) {
                afterCommit.accept(result);
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    /** BEGIN 이 실패했을 때 혹시 열려 있는 트랜잭션을 닫음. "열린 트랜잭션 없음" 오류는 정상. */
    private void clearStrayTransaction(SQLException cause) {
        try {
            exec("ROLLBACK");
        } catch (SQLException e) {
            String msg = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (!msg.contains("no transaction is active")) {
                cause.addSuppressed(e);
                breakConnection("BEGIN 실패 후 상태 확인 실패: " + e.getMessage());
            }
        }
    }

    private void exec(String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    /** 연결 상태를 확신할 수 없음 → 연결을 닫고 이후 모든 읽기·쓰기를 막음. */
    private void breakConnection(String reason) {
        brokenReason = reason;
        closed = true;
        inTx = false;
        closeQuietly(conn);
    }

    private void ensureUsable() throws SQLException {
        if (brokenReason != null) {
            throw new SQLException("저장 오류로 DB 연결을 닫았음, 서버 재시작 필요 (" + brokenReason + ")");
        }
        if (closed) {
            throw new ClosedException();
        }
    }

    /** 저장 오류로 연결을 닫았으면 그 이유, 아니면 null. */
    public String brokenReason() {
        return brokenReason;
    }

    private <T> T read(SqlWork<T> work) throws SQLException {
        lock.lock();
        try {
            ensureUsable();
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

    /** 테스트용: 잠금 대기 시간 변경. */
    void busyTimeoutForTest(int ms) throws SQLException {
        read(() -> {
            exec("PRAGMA busy_timeout = " + ms);
            return null;
        });
    }

    /** 테스트용: 커밋·롤백 불확실 상태({@link #brokenReason}) 흉내. */
    void simulateBrokenForTest(String reason) {
        lock.lock();
        try {
            breakConnection(reason);
        } finally {
            lock.unlock();
        }
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
