package kr.commata.market.core;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteOpenMode;

/**
 * {@code plugins/Market/market.db} (파일형 SQLite, WAL, {@code synchronous=FULL}, ECONOMY.md 13.6, 14.7).
 *
 * <p>Bank 와 동일한 트랜잭션 규율({@code BEGIN IMMEDIATE}·{@code COMMIT}·{@code ROLLBACK}, 단일 연결 + {@link ReentrantLock},
 * 커밋/롤백 결과 불확실 시 연결을 닫고 {@code brokenReason}으로 이후 쓰기 차단)을 따릅니다.
 */
public final class MarketStore implements AutoCloseable {

    public static final String DB_FILE = "market.db";
    static final int SCHEMA_VERSION = 1;
    static final int BUSY_TIMEOUT_MS = 2000;

    public static final String KIND_RARE_BUY = "RARE_BUY";
    public static final String KIND_GACHA_PULL = "GACHA_PULL";

    public static final String ORDER_PENDING = "PENDING";
    public static final String ORDER_CHARGED = "CHARGED";
    public static final String ORDER_DELIVERED = "DELIVERED";
    public static final String ORDER_ABORTED = "ABORTED";
    public static final String ORDER_REFUNDED = "REFUNDED";
    /**
     * 관리자 환불 진행 중 (/market resolve refund). Bank 환불 전에 먼저 이 상태로 바꿔 지급 경로가 더는 건드리지 않게 하고,
     * Bank 결과를 모르면 이 상태로 남겨 관리자가 같은 명령으로 다시 확인합니다 (ECONOMY.md 13.6).
     */
    public static final String ORDER_REFUNDING = "REFUNDING";

    public static final String SALE_PENDING = "PENDING";
    public static final String SALE_ITEMS_TAKEN = "ITEMS_TAKEN";
    public static final String SALE_DONE = "DONE";
    public static final String SALE_ABORTED = "ABORTED";

    private static final String SCHEMA = """
            CREATE TABLE meta (
              key   TEXT PRIMARY KEY,
              value TEXT NOT NULL
            );
            CREATE TABLE orders (
              tx_id            TEXT PRIMARY KEY,
              time_ms          INTEGER NOT NULL,
              uuid             TEXT NOT NULL,
              kind             TEXT NOT NULL,
              status           TEXT NOT NULL,
              payload          TEXT NOT NULL,
              limit_key        TEXT NOT NULL,
              limit_delta      INTEGER NOT NULL,
              price            INTEGER NOT NULL CHECK (price > 0),
              period_key       TEXT NOT NULL,
              attempt          INTEGER NOT NULL DEFAULT 1,
              unreadable_count INTEGER NOT NULL DEFAULT 0,
              bank_ref         INTEGER,
              pity_before      INTEGER,
              pity_after       INTEGER,
              reason           TEXT
            );
            CREATE INDEX orders_uuid_status ON orders (uuid, status);
            CREATE INDEX orders_status ON orders (status);
            CREATE TABLE purchases (
              id       INTEGER PRIMARY KEY AUTOINCREMENT,
              tx_id    TEXT UNIQUE,
              time_ms  INTEGER NOT NULL,
              uuid     TEXT NOT NULL,
              item     TEXT NOT NULL,
              amount   INTEGER NOT NULL,
              price    INTEGER NOT NULL,
              week_key TEXT NOT NULL,
              bank_ref INTEGER
            );
            CREATE INDEX purchases_uuid_time ON purchases (uuid, time_ms DESC);
            CREATE TABLE pulls (
              id          INTEGER PRIMARY KEY AUTOINCREMENT,
              tx_id       TEXT NOT NULL,
              seq_in_tx   INTEGER NOT NULL DEFAULT 0,
              time_ms     INTEGER NOT NULL,
              uuid        TEXT NOT NULL,
              tier        TEXT NOT NULL,
              enchant     TEXT NOT NULL,
              level       INTEGER NOT NULL,
              price       INTEGER NOT NULL,
              pity_before INTEGER NOT NULL,
              bank_ref    INTEGER,
              UNIQUE (tx_id, seq_in_tx)
            );
            CREATE INDEX pulls_uuid_time ON pulls (uuid, time_ms DESC);
            CREATE TABLE pity (
              uuid  TEXT PRIMARY KEY,
              count INTEGER NOT NULL DEFAULT 0 CHECK (count >= 0)
            );
            CREATE TABLE weekly_limits (
              uuid      TEXT NOT NULL,
              week_key  TEXT NOT NULL,
              limit_key TEXT NOT NULL,
              used      INTEGER NOT NULL DEFAULT 0 CHECK (used >= 0),
              PRIMARY KEY (uuid, week_key, limit_key)
            );
            CREATE TABLE daily_pulls (
              uuid    TEXT NOT NULL,
              day_key TEXT NOT NULL,
              used    INTEGER NOT NULL DEFAULT 0 CHECK (used >= 0),
              PRIMARY KEY (uuid, day_key)
            );
            CREATE TABLE sell_quota (
              uuid        TEXT NOT NULL,
              item        TEXT NOT NULL,
              job_amount  INTEGER NOT NULL DEFAULT 0 CHECK (job_amount >= 0),
              base_amount INTEGER NOT NULL DEFAULT 0 CHECK (base_amount >= 0),
              PRIMARY KEY (uuid, item)
            );
            CREATE TABLE sell_orders (
              tx_id            TEXT PRIMARY KEY,
              time_ms          INTEGER NOT NULL,
              uuid             TEXT NOT NULL,
              item             TEXT NOT NULL,
              unit             INTEGER NOT NULL,
              bonus_amount     INTEGER NOT NULL,
              base_amount      INTEGER NOT NULL,
              bonus_unit_price INTEGER NOT NULL,
              base_unit_price  INTEGER NOT NULL,
              bonus_total      INTEGER NOT NULL,
              base_total       INTEGER NOT NULL,
              total            INTEGER NOT NULL CHECK (total > 0),
              day_key          TEXT NOT NULL,
              status           TEXT NOT NULL,
              attempt          INTEGER NOT NULL DEFAULT 1,
              unreadable_count INTEGER NOT NULL DEFAULT 0,
              bank_ref         INTEGER,
              reason           TEXT
            );
            CREATE INDEX sell_orders_uuid_status ON sell_orders (uuid, status);
            CREATE INDEX sell_orders_status ON sell_orders (status);
            CREATE TABLE sales (
              id         INTEGER PRIMARY KEY AUTOINCREMENT,
              tx_id      TEXT NOT NULL,
              time_ms    INTEGER NOT NULL,
              uuid       TEXT NOT NULL,
              item       TEXT NOT NULL,
              amount     INTEGER NOT NULL,
              price_type TEXT NOT NULL,
              unit_price INTEGER NOT NULL,
              total      INTEGER NOT NULL,
              bank_ref   INTEGER
            );
            CREATE INDEX sales_uuid_time ON sales (uuid, time_ms DESC);
            CREATE TABLE daily_sales (
              uuid    TEXT NOT NULL,
              day_key TEXT NOT NULL,
              total   INTEGER NOT NULL DEFAULT 0 CHECK (total >= 0),
              PRIMARY KEY (uuid, day_key)
            );
            CREATE TABLE player_seq (
              uuid TEXT PRIMARY KEY,
              seq  INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE quarantined_players (
              uuid        TEXT PRIMARY KEY,
              time_ms     INTEGER NOT NULL,
              reason      TEXT NOT NULL,
              backup_file TEXT
            );
            """;

    public record OrderRecord(
            String txId,
            long timeMs,
            UUID uuid,
            String kind,
            String status,
            String payload,
            String limitKey,
            int limitDelta,
            long price,
            String periodKey,
            int attempt,
            int unreadableCount,
            Long bankRef,
            Integer pityBefore,
            Integer pityAfter,
            String reason
    ) {
    }

    public record SellOrderRecord(
            String txId,
            long timeMs,
            UUID uuid,
            String item,
            int unit,
            int bonusAmount,
            int baseAmount,
            long bonusUnitPrice,
            long baseUnitPrice,
            long bonusTotal,
            long baseTotal,
            long total,
            String dayKey,
            String status,
            int attempt,
            int unreadableCount,
            Long bankRef,
            String reason
    ) {
    }

    public record Quota(long jobAmount, long baseAmount) {
        public static final Quota ZERO = new Quota(0L, 0L);
    }

    public record QuarantineRecord(UUID uuid, long timeMs, String reason, String backupFile) {
    }

    public record LogEntry(long timeMs, String kind, String summary, long amount, Long bankRef) {
    }

    public enum ReserveFailure {
        NONE, PLAYER_LOCKED, LIMIT_EXCEEDED, INSUFFICIENT_QUOTA, STORAGE_ERROR
    }

    public record ReserveOrderResult(ReserveFailure failure, OrderRecord order) {
        public boolean ok() {
            return failure == ReserveFailure.NONE;
        }
    }

    public record ReserveSaleResult(ReserveFailure failure, SellOrderRecord order) {
        public boolean ok() {
            return failure == ReserveFailure.NONE;
        }
    }

    private final Connection conn;
    private final LongSupplier clock;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean closed;
    private volatile String brokenReason;
    private boolean inTx;

    private MarketStore(Connection conn, LongSupplier clock) throws SQLException {
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
                if (!rs.next() || rs.getInt(1) != 2) {
                    throw new SQLException("synchronous 를 FULL 로 바꾸지 못함");
                }
            }
        }
        conn.setAutoCommit(true);
    }

    public static MarketStore create(Path dbFile, LongSupplier clock) throws SQLException {
        if (Files.exists(dbFile)) {
            throw new SQLException("이미 DB 파일이 있음: " + dbFile);
        }
        Connection conn = connect(dbFile, true);
        try {
            MarketStore store = new MarketStore(conn, clock);
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

    public static MarketStore open(Path dbFile, LongSupplier clock) throws SQLException {
        if (!Files.isRegularFile(dbFile)) {
            throw new SQLException("DB 파일이 없음: " + dbFile);
        }
        Connection conn = connect(dbFile, false);
        try {
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
                    throw new SQLException("meta.installed 표시가 없음 (Market DB 가 아님)");
                }
            }
            MarketStore store = new MarketStore(conn, clock);
            store.migrate();
            return store;
        } catch (SQLException | RuntimeException e) {
            closeQuietly(conn);
            throw e;
        }
    }

    private static Connection connect(Path dbFile, boolean create) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setOpenMode(SQLiteOpenMode.READWRITE);
        if (create) {
            config.setOpenMode(SQLiteOpenMode.CREATE);
        } else {
            config.resetOpenMode(SQLiteOpenMode.CREATE);
        }
        return config.createConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
    }

    private void migrate() throws SQLException {
        write(() -> {
            String schema = meta("schema");
            if (schema == null || Integer.parseInt(schema) < SCHEMA_VERSION) {
                putMeta("schema", String.valueOf(SCHEMA_VERSION));
            }
            return null;
        });
    }

    public void closeClean() {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            } catch (SQLException ignored) {
                // 닫기 진행
            } finally {
                closed = true;
                closeQuietly(conn);
            }
        } finally {
            lock.unlock();
        }
    }

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

    public String brokenReason() {
        return brokenReason;
    }

    // ------------------------------------------------------------------ 플레이어 잠금·시퀀스·격리

    /** 미완료 거래(PENDING/CHARGED/REFUNDING 주문, PENDING/ITEMS_TAKEN 판매)나 격리 상태면 true. */
    public boolean isPlayerLocked(UUID uuid) throws SQLException {
        return read(() -> isPlayerLockedInTx(uuid));
    }

    private boolean isPlayerLockedInTx(UUID uuid) throws SQLException {
        String id = uuid.toString();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM quarantined_players WHERE uuid = ? LIMIT 1")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return true;
                }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM orders WHERE uuid = ? AND status IN (?, ?, ?) LIMIT 1")) {
            ps.setString(1, id);
            ps.setString(2, ORDER_PENDING);
            ps.setString(3, ORDER_CHARGED);
            ps.setString(4, ORDER_REFUNDING);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return true;
                }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM sell_orders WHERE uuid = ? AND status IN (?, ?) LIMIT 1")) {
            ps.setString(1, id);
            ps.setString(2, SALE_PENDING);
            ps.setString(3, SALE_ITEMS_TAKEN);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** 이 플레이어가 Market 거래·시퀀스 이력을 가지고 있는지 (접속 전 .dat 검증 대상 여부, ECONOMY.md 13.6). */
    public boolean hasMarketHistory(UUID uuid) throws SQLException {
        return read(() -> {
            String id = uuid.toString();
            for (String sql : List.of(
                    "SELECT 1 FROM player_seq WHERE uuid = ? LIMIT 1",
                    "SELECT 1 FROM orders WHERE uuid = ? LIMIT 1",
                    "SELECT 1 FROM sell_orders WHERE uuid = ? LIMIT 1",
                    "SELECT 1 FROM quarantined_players WHERE uuid = ? LIMIT 1")) {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return true;
                        }
                    }
                }
            }
            return false;
        });
    }

    public long playerSeq(UUID uuid) throws SQLException {
        return read(() -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT seq FROM player_seq WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    /** 디스크에서 확인한 {@code market:seq}가 DB 값보다 크면 올립니다(복구 경로에서 DB 값이 뒤처지지 않게). */
    public void raisePlayerSeq(UUID uuid, long seq) throws SQLException {
        if (seq <= 0) {
            return;
        }
        write(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO player_seq (uuid, seq) VALUES (?, ?)"
                            + " ON CONFLICT (uuid) DO UPDATE SET seq = MAX(seq, excluded.seq)")) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, seq);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void savePlayerSeq(UUID uuid, long seq) throws SQLException {
        write(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO player_seq (uuid, seq) VALUES (?, ?)"
                            + " ON CONFLICT (uuid) DO UPDATE SET seq = excluded.seq")) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, seq);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public Optional<QuarantineRecord> quarantine(UUID uuid) throws SQLException {
        return read(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT uuid, time_ms, reason, backup_file FROM quarantined_players WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new QuarantineRecord(
                            UUID.fromString(rs.getString(1)), rs.getLong(2), rs.getString(3), rs.getString(4)));
                }
            }
        });
    }

    public void quarantinePlayer(UUID uuid, String reason, String backupFile) throws SQLException {
        write(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO quarantined_players (uuid, time_ms, reason, backup_file) VALUES (?, ?, ?, ?)"
                            + " ON CONFLICT (uuid) DO UPDATE SET time_ms = excluded.time_ms,"
                            + " reason = excluded.reason, backup_file = excluded.backup_file")) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, clock.getAsLong());
                ps.setString(3, reason);
                setNullable(ps, 4, backupFile);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public boolean unquarantinePlayer(UUID uuid) throws SQLException {
        return write(() -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM quarantined_players WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                return ps.executeUpdate() > 0;
            }
        });
    }

    public boolean isQuarantined(UUID uuid) throws SQLException {
        return quarantine(uuid).isPresent();
    }

    public Set<UUID> quarantinedUuids() throws SQLException {
        return read(() -> {
            Set<UUID> set = new HashSet<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT uuid FROM quarantined_players")) {
                while (rs.next()) {
                    set.add(UUID.fromString(rs.getString(1)));
                }
            }
            return set;
        });
    }

    public List<QuarantineRecord> allQuarantined() throws SQLException {
        return read(() -> {
            List<QuarantineRecord> out = new ArrayList<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT uuid, time_ms, reason, backup_file FROM quarantined_players ORDER BY time_ms DESC")) {
                while (rs.next()) {
                    out.add(new QuarantineRecord(
                            UUID.fromString(rs.getString(1)), rs.getLong(2), rs.getString(3), rs.getString(4)));
                }
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ 한도·천장 조회

    public int weeklyUsed(UUID uuid, String weekKey, String limitKey) throws SQLException {
        return read(() -> weeklyUsedInTx(uuid, weekKey, limitKey));
    }

    private int weeklyUsedInTx(UUID uuid, String weekKey, String limitKey) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT used FROM weekly_limits WHERE uuid = ? AND week_key = ? AND limit_key = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, weekKey);
            ps.setString(3, limitKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public Map<String, Integer> allWeeklyUsed(UUID uuid, String weekKey) throws SQLException {
        return read(() -> {
            Map<String, Integer> map = new HashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT limit_key, used FROM weekly_limits WHERE uuid = ? AND week_key = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, weekKey);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        map.put(rs.getString(1), rs.getInt(2));
                    }
                }
            }
            return map;
        });
    }

    public int dailyPullsUsed(UUID uuid, String dayKey) throws SQLException {
        return read(() -> dailyPullsUsedInTx(uuid, dayKey));
    }

    private int dailyPullsUsedInTx(UUID uuid, String dayKey) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT used FROM daily_pulls WHERE uuid = ? AND day_key = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, dayKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public int pity(UUID uuid) throws SQLException {
        return read(() -> pityInTx(uuid));
    }

    private int pityInTx(UUID uuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT count FROM pity WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public long dailySalesTotal(UUID uuid, String dayKey) throws SQLException {
        return read(() -> dailySalesTotalInTx(uuid, dayKey));
    }

    private long dailySalesTotalInTx(UUID uuid, String dayKey) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT total FROM daily_sales WHERE uuid = ? AND day_key = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, dayKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    // ------------------------------------------------------------------ 희귀 상점·뽑기 주문 상태 전이

    /**
     * 희귀 상점 1개 구매 예약: 한 트랜잭션에서 플레이어 잠금·주간 한도 검사 → 한도 예약 → {@code orders(PENDING)} 기록.
     */
    public ReserveOrderResult reserveRarePurchase(
            String txId, UUID uuid, RareCatalog.Entry entry, String payload, String weekKey) {
        try {
            return write(() -> {
                if (isPlayerLockedInTx(uuid)) {
                    return new ReserveOrderResult(ReserveFailure.PLAYER_LOCKED, null);
                }
                String limitKey = entry.limitKey();
                int used = weeklyUsedInTx(uuid, weekKey, limitKey);
                if (used + 1 > entry.weeklyLimit()) {
                    return new ReserveOrderResult(ReserveFailure.LIMIT_EXCEEDED, null);
                }
                upsertWeeklyLimit(uuid, weekKey, limitKey, used + 1);
                long now = clock.getAsLong();
                insertOrder(txId, now, uuid, KIND_RARE_BUY, ORDER_PENDING, payload, limitKey, 1,
                        entry.price(), weekKey, null, null);
                return new ReserveOrderResult(ReserveFailure.NONE,
                        new OrderRecord(txId, now, uuid, KIND_RARE_BUY, ORDER_PENDING, payload, limitKey, 1,
                                entry.price(), weekKey, 1, 0, null, null, null, null));
            });
        } catch (SQLException e) {
            return new ReserveOrderResult(ReserveFailure.STORAGE_ERROR, null);
        }
    }

    /**
     * 인첸트 책 뽑기 예약: 한 트랜잭션에서 플레이어 잠금·하루 한도 검사 → 하루 한도 증가 + 천장 갱신 → {@code orders(PENDING)} 기록.
     */
    public ReserveOrderResult reserveGachaPull(
            String txId, UUID uuid, int count, int dailyLimit, long totalPrice,
            int expectedPityBefore, int pityAfter, String payload, String dayKey) {
        try {
            return write(() -> {
                if (isPlayerLockedInTx(uuid)) {
                    return new ReserveOrderResult(ReserveFailure.PLAYER_LOCKED, null);
                }
                int used = dailyPullsUsedInTx(uuid, dayKey);
                if (used + count > dailyLimit) {
                    return new ReserveOrderResult(ReserveFailure.LIMIT_EXCEEDED, null);
                }
                int curPity = pityInTx(uuid);
                if (curPity != expectedPityBefore) {
                    return new ReserveOrderResult(ReserveFailure.PLAYER_LOCKED, null);
                }
                upsertDailyPulls(uuid, dayKey, used + count);
                upsertPity(uuid, pityAfter);
                long now = clock.getAsLong();
                insertOrder(txId, now, uuid, KIND_GACHA_PULL, ORDER_PENDING, payload, "GACHA", count,
                        totalPrice, dayKey, expectedPityBefore, pityAfter);
                return new ReserveOrderResult(ReserveFailure.NONE,
                        new OrderRecord(txId, now, uuid, KIND_GACHA_PULL, ORDER_PENDING, payload, "GACHA", count,
                                totalPrice, dayKey, 1, 0, null, expectedPityBefore, pityAfter, null));
            });
        } catch (SQLException e) {
            return new ReserveOrderResult(ReserveFailure.STORAGE_ERROR, null);
        }
    }

    /** {@code PENDING → CHARGED} (Bank 출금 확정 후). */
    public boolean markOrderCharged(String txId, long bankRef) throws SQLException {
        return write(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE orders SET status = ?, bank_ref = ?, unreadable_count = 0, reason = NULL"
                            + " WHERE tx_id = ? AND status = ?")) {
                ps.setString(1, ORDER_CHARGED);
                ps.setLong(2, bankRef);
                ps.setString(3, txId);
                ps.setString(4, ORDER_PENDING);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** {@code PENDING → ABORTED} + 예약한 한도·천장 반환. */
    public boolean abortOrder(String txId, String reason) throws SQLException {
        return write(() -> {
            OrderRecord order = findOrderInTx(txId);
            if (order == null || !ORDER_PENDING.equals(order.status())) {
                return false;
            }
            releaseReservationInTx(order);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE orders SET status = ?, reason = ? WHERE tx_id = ? AND status = ?")) {
                ps.setString(1, ORDER_ABORTED);
                setNullable(ps, 2, reason);
                ps.setString(3, txId);
                ps.setString(4, ORDER_PENDING);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** 저장 확인 실패로 같은 틱에 회수한 뒤, 다음 재지급 시도를 위해 attempt 를 1 올립니다. */
    public int bumpOrderAttempt(String txId, String reason) throws SQLException {
        return write(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE orders SET attempt = attempt + 1, reason = ? WHERE tx_id = ? AND status = ?")) {
                setNullable(ps, 1, reason);
                ps.setString(2, txId);
                ps.setString(3, ORDER_CHARGED);
                ps.executeUpdate();
            }
            OrderRecord updated = findOrderInTx(txId);
            return updated == null ? 0 : updated.attempt();
        });
    }

    public int incrementOrderUnreadable(String txId, String reason) throws SQLException {
        return write(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE orders SET unreadable_count = unreadable_count + 1, reason = ? WHERE tx_id = ?")) {
                setNullable(ps, 1, reason);
                ps.setString(2, txId);
                ps.executeUpdate();
            }
            OrderRecord updated = findOrderInTx(txId);
            return updated == null ? 0 : updated.unreadableCount();
        });
    }

    /** {@code CHARGED → DELIVERED} + {@code purchases} 또는 {@code pulls} 기록 삽입. */
    public boolean markOrderDelivered(String txId) throws SQLException {
        return write(() -> {
            OrderRecord order = findOrderInTx(txId);
            if (order == null || !ORDER_CHARGED.equals(order.status())) {
                return false;
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE orders SET status = ?, unreadable_count = 0, reason = NULL WHERE tx_id = ? AND status = ?")) {
                ps.setString(1, ORDER_DELIVERED);
                ps.setString(2, txId);
                ps.setString(3, ORDER_CHARGED);
                if (ps.executeUpdate() != 1) {
                    return false;
                }
            }
            if (KIND_RARE_BUY.equals(order.kind())) {
                String itemId;
                int amount;
                if (OrderPayload.isRareResource(order.payload())) {
                    OrderPayload.RareResource r = OrderPayload.decodeRareResource(order.payload());
                    itemId = r.itemId();
                    amount = r.amount();
                } else {
                    OrderPayload.RareBook b = OrderPayload.decodeRareBook(order.payload());
                    itemId = b.itemId();
                    amount = 1;
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT OR IGNORE INTO purchases (tx_id, time_ms, uuid, item, amount, price, week_key, bank_ref)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, order.txId());
                    ps.setLong(2, order.timeMs());
                    ps.setString(3, order.uuid().toString());
                    ps.setString(4, itemId);
                    ps.setInt(5, amount);
                    ps.setLong(6, order.price());
                    ps.setString(7, order.periodKey());
                    setNullable(ps, 8, order.bankRef());
                    ps.executeUpdate();
                }
            } else if (KIND_GACHA_PULL.equals(order.kind())) {
                List<OrderPayload.GachaEntry> draws = OrderPayload.decodeGacha(order.payload());
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT OR IGNORE INTO pulls (tx_id, seq_in_tx, time_ms, uuid, tier, enchant, level, price, pity_before, bank_ref)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    for (int i = 0; i < draws.size(); i++) {
                        OrderPayload.GachaEntry d = draws.get(i);
                        ps.setString(1, order.txId());
                        ps.setInt(2, i);
                        ps.setLong(3, order.timeMs());
                        ps.setString(4, order.uuid().toString());
                        ps.setString(5, d.tierId());
                        ps.setString(6, d.enchantKey());
                        ps.setInt(7, d.level());
                        ps.setLong(8, d.unitPrice());
                        ps.setInt(9, d.pityBefore());
                        setNullable(ps, 10, order.bankRef());
                        ps.executeUpdate();
                    }
                }
            }
            return true;
        });
    }

    /**
     * 예약한 주간 한도·하루 뽑기 횟수를 돌려주고 천장을 주문 전 값으로 되돌립니다 (취소·환불 공통).
     * 천장은 지금 값이 이 주문이 만든 값({@code pity_after})일 때만 되돌립니다. 주문이 끝나기 전에는 플레이어가 잠겨
     * 있어 보통 같지만, 다르면 그 뒤 기록을 덮지 않도록 그대로 둡니다.
     */
    private void releaseReservationInTx(OrderRecord order) throws SQLException {
        if (KIND_RARE_BUY.equals(order.kind())) {
            int used = weeklyUsedInTx(order.uuid(), order.periodKey(), order.limitKey());
            upsertWeeklyLimit(order.uuid(), order.periodKey(), order.limitKey(),
                    Math.max(0, used - order.limitDelta()));
        } else if (KIND_GACHA_PULL.equals(order.kind())) {
            int used = dailyPullsUsedInTx(order.uuid(), order.periodKey());
            upsertDailyPulls(order.uuid(), order.periodKey(), Math.max(0, used - order.limitDelta()));
            if (order.pityBefore() != null
                    && (order.pityAfter() == null || pityInTx(order.uuid()) == order.pityAfter())) {
                upsertPity(order.uuid(), order.pityBefore());
            }
        }
    }

    /** 관리자 환불 1단계: {@code CHARGED → REFUNDING}. 이후 지급·복구 경로는 이 주문을 건드리지 않습니다. */
    public boolean beginRefund(String txId, String reason) throws SQLException {
        return write(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE orders SET status = ?, reason = ? WHERE tx_id = ? AND status = ?")) {
                ps.setString(1, ORDER_REFUNDING);
                setNullable(ps, 2, reason);
                ps.setString(3, txId);
                ps.setString(4, ORDER_CHARGED);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** 관리자 환불 완료: {@code REFUNDING → REFUNDED} + 한도·천장 반환을 한 트랜잭션으로 (ECONOMY.md 13.6). */
    public boolean finishRefund(String txId, String reason) throws SQLException {
        return write(() -> {
            OrderRecord order = findOrderInTx(txId);
            if (order == null || !ORDER_REFUNDING.equals(order.status())) {
                return false;
            }
            releaseReservationInTx(order);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE orders SET status = ?, reason = ? WHERE tx_id = ? AND status = ?")) {
                ps.setString(1, ORDER_REFUNDED);
                setNullable(ps, 2, reason);
                ps.setString(3, txId);
                ps.setString(4, ORDER_REFUNDING);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** Bank 가 환불을 확실히 거부했을 때: {@code REFUNDING → CHARGED} (다시 지급 대기). */
    public boolean cancelRefund(String txId, String reason) throws SQLException {
        return write(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE orders SET status = ?, reason = ? WHERE tx_id = ? AND status = ?")) {
                ps.setString(1, ORDER_CHARGED);
                setNullable(ps, 2, reason);
                ps.setString(3, txId);
                ps.setString(4, ORDER_REFUNDING);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** {@code /market pending}용: 미완료 주문 + 환불 진행 중 주문. */
    public List<OrderRecord> unresolvedOrders() throws SQLException {
        return read(() -> {
            List<OrderRecord> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    ORDER_SELECT + " WHERE status IN (?, ?, ?) ORDER BY time_ms ASC")) {
                ps.setString(1, ORDER_PENDING);
                ps.setString(2, ORDER_CHARGED);
                ps.setString(3, ORDER_REFUNDING);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(orderRow(rs));
                    }
                }
            }
            return out;
        });
    }

    public Optional<OrderRecord> findOrder(String txId) throws SQLException {
        return read(() -> Optional.ofNullable(findOrderInTx(txId)));
    }

    public List<OrderRecord> openOrders() throws SQLException {
        return read(() -> {
            List<OrderRecord> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    ORDER_SELECT + " WHERE status IN (?, ?) ORDER BY time_ms ASC")) {
                ps.setString(1, ORDER_PENDING);
                ps.setString(2, ORDER_CHARGED);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(orderRow(rs));
                    }
                }
            }
            return out;
        });
    }

    public List<OrderRecord> openOrdersOf(UUID uuid) throws SQLException {
        return read(() -> {
            List<OrderRecord> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    ORDER_SELECT + " WHERE uuid = ? AND status IN (?, ?) ORDER BY time_ms ASC")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, ORDER_PENDING);
                ps.setString(3, ORDER_CHARGED);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(orderRow(rs));
                    }
                }
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ 판매 가능량·판매 주문 상태 전이

    /** 직접 채집 수량 적립 ({@code hasJob=true}면 {@code job_amount}, 아니면 {@code base_amount}). */
    public void addQuota(UUID uuid, String item, long amount, boolean hasJob) throws SQLException {
        if (amount <= 0) {
            return;
        }
        addQuotaBatch(Map.of(new QuotaBuffer.Key(uuid, item, hasJob), amount));
    }

    /** 메모리 버퍼에 모인 판매 가능량을 한 트랜잭션으로 일괄 반영합니다 (ECONOMY.md 14.2). */
    public void addQuotaBatch(Map<QuotaBuffer.Key, Long> batch) throws SQLException {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        write(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO sell_quota (uuid, item, job_amount, base_amount) VALUES (?, ?, ?, ?)"
                            + " ON CONFLICT (uuid, item) DO UPDATE SET"
                            + " job_amount = job_amount + excluded.job_amount,"
                            + " base_amount = base_amount + excluded.base_amount")) {
                for (Map.Entry<QuotaBuffer.Key, Long> entry : batch.entrySet()) {
                    long amount = entry.getValue();
                    if (amount <= 0) {
                        continue;
                    }
                    QuotaBuffer.Key key = entry.getKey();
                    ps.setString(1, key.uuid().toString());
                    ps.setString(2, key.item());
                    ps.setLong(3, key.hasJob() ? amount : 0L);
                    ps.setLong(4, key.hasJob() ? 0L : amount);
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    /** 탈퇴 이관 보정: 지정 품목들의 {@code job_amount}를 {@code base_amount}로 옮깁니다 (ECONOMY.md 14.2). */
    public int demoteJobQuota(UUID uuid, Collection<String> items) throws SQLException {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        return write(() -> {
            int moved = 0;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE sell_quota SET base_amount = base_amount + job_amount, job_amount = 0"
                            + " WHERE uuid = ? AND item = ? AND job_amount > 0")) {
                String id = uuid.toString();
                for (String item : items) {
                    ps.setString(1, id);
                    ps.setString(2, item);
                    moved += ps.executeUpdate();
                }
            }
            return moved;
        });
    }

    public Quota quota(UUID uuid, String item) throws SQLException {
        return read(() -> quotaInTx(uuid, item));
    }

    private Quota quotaInTx(UUID uuid, String item) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT job_amount, base_amount FROM sell_quota WHERE uuid = ? AND item = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, item);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Quota(rs.getLong(1), rs.getLong(2)) : Quota.ZERO;
            }
        }
    }

    public Map<String, Quota> allQuotas(UUID uuid) throws SQLException {
        return read(() -> {
            Map<String, Quota> out = new HashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT item, job_amount, base_amount FROM sell_quota WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString(1), new Quota(rs.getLong(2), rs.getLong(3)));
                    }
                }
            }
            return out;
        });
    }

    /**
     * 판매 예약: 한 트랜잭션에서 플레이어 잠금·판매 가능량·하루 한도 검사 → 차감 → {@code sell_orders(PENDING)} 기록.
     */
    public ReserveSaleResult reserveSale(
            String txId, UUID uuid, SellCatalog.Item item, SellMath.Plan plan,
            long dailyLimit, String dayKey) {
        if (plan.isEmpty() || plan.totalMoney() <= 0) {
            return new ReserveSaleResult(ReserveFailure.INSUFFICIENT_QUOTA, null);
        }
        try {
            return write(() -> {
                if (isPlayerLockedInTx(uuid)) {
                    return new ReserveSaleResult(ReserveFailure.PLAYER_LOCKED, null);
                }
                long today = dailySalesTotalInTx(uuid, dayKey);
                if (today + plan.totalMoney() > dailyLimit) {
                    return new ReserveSaleResult(ReserveFailure.LIMIT_EXCEEDED, null);
                }
                Quota q = quotaInTx(uuid, item.id());
                if (q.jobAmount() < plan.bonusAmount() || q.baseAmount() < plan.baseAmount()) {
                    return new ReserveSaleResult(ReserveFailure.INSUFFICIENT_QUOTA, null);
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE sell_quota SET job_amount = job_amount - ?, base_amount = base_amount - ?"
                                + " WHERE uuid = ? AND item = ?")) {
                    ps.setLong(1, plan.bonusAmount());
                    ps.setLong(2, plan.baseAmount());
                    ps.setString(3, uuid.toString());
                    ps.setString(4, item.id());
                    if (ps.executeUpdate() != 1) {
                        return new ReserveSaleResult(ReserveFailure.INSUFFICIENT_QUOTA, null);
                    }
                }
                upsertDailySales(uuid, dayKey, today + plan.totalMoney());
                long now = clock.getAsLong();
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO sell_orders (tx_id, time_ms, uuid, item, unit, bonus_amount, base_amount,"
                                + " bonus_unit_price, base_unit_price, bonus_total, base_total, total, day_key, status)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, txId);
                    ps.setLong(2, now);
                    ps.setString(3, uuid.toString());
                    ps.setString(4, item.id());
                    ps.setInt(5, item.unit());
                    ps.setInt(6, plan.bonusAmount());
                    ps.setInt(7, plan.baseAmount());
                    ps.setLong(8, item.bonusPrice());
                    ps.setLong(9, item.basePrice());
                    ps.setLong(10, plan.bonusTotal());
                    ps.setLong(11, plan.baseTotal());
                    ps.setLong(12, plan.totalMoney());
                    ps.setString(13, dayKey);
                    ps.setString(14, SALE_PENDING);
                    ps.executeUpdate();
                }
                return new ReserveSaleResult(ReserveFailure.NONE,
                        new SellOrderRecord(txId, now, uuid, item.id(), item.unit(),
                                plan.bonusAmount(), plan.baseAmount(), item.bonusPrice(), item.basePrice(),
                                plan.bonusTotal(), plan.baseTotal(), plan.totalMoney(), dayKey,
                                SALE_PENDING, 1, 0, null, null));
            });
        } catch (SQLException e) {
            return new ReserveSaleResult(ReserveFailure.STORAGE_ERROR, null);
        }
    }

    /** {@code PENDING → ITEMS_TAKEN} (디스크에서 제거 표시 FOUND 확인 후). */
    public boolean markSaleItemsTaken(String txId) throws SQLException {
        return write(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE sell_orders SET status = ?, unreadable_count = 0, reason = NULL"
                            + " WHERE tx_id = ? AND status = ?")) {
                ps.setString(1, SALE_ITEMS_TAKEN);
                ps.setString(2, txId);
                ps.setString(3, SALE_PENDING);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** {@code ITEMS_TAKEN → DONE} + {@code sales} 로그 삽입(보너스/기본 각각 1줄, ECONOMY.md 14.3 #3). */
    public boolean markSaleDone(String txId, long bankRef) throws SQLException {
        return write(() -> {
            SellOrderRecord sale = findSaleInTx(txId);
            if (sale == null || !SALE_ITEMS_TAKEN.equals(sale.status())) {
                return false;
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE sell_orders SET status = ?, bank_ref = ?, unreadable_count = 0, reason = NULL"
                            + " WHERE tx_id = ? AND status = ?")) {
                ps.setString(1, SALE_DONE);
                ps.setLong(2, bankRef);
                ps.setString(3, txId);
                ps.setString(4, SALE_ITEMS_TAKEN);
                if (ps.executeUpdate() != 1) {
                    return false;
                }
            }
            if (sale.bonusAmount() > 0) {
                insertSaleLog(sale, sale.bonusAmount(), "bonus", sale.bonusUnitPrice(), sale.bonusTotal(), bankRef);
            }
            if (sale.baseAmount() > 0) {
                insertSaleLog(sale, sale.baseAmount(), "base", sale.baseUnitPrice(), sale.baseTotal(), bankRef);
            }
            return true;
        });
    }

    private void insertSaleLog(SellOrderRecord sale, int amount, String priceType,
                               long unitPrice, long total, long bankRef) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO sales (tx_id, time_ms, uuid, item, amount, price_type, unit_price, total, bank_ref)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, sale.txId());
            ps.setLong(2, sale.timeMs());
            ps.setString(3, sale.uuid().toString());
            ps.setString(4, sale.item());
            ps.setInt(5, amount);
            ps.setString(6, priceType);
            ps.setLong(7, unitPrice);
            ps.setLong(8, total);
            ps.setLong(9, bankRef);
            ps.executeUpdate();
        }
    }

    /** {@code PENDING → ABORTED} + 예약한 판매 가능량·하루 판매 한도 반환. */
    public boolean abortSale(String txId, String reason) throws SQLException {
        return write(() -> {
            SellOrderRecord sale = findSaleInTx(txId);
            if (sale == null || !SALE_PENDING.equals(sale.status())) {
                return false;
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO sell_quota (uuid, item, job_amount, base_amount) VALUES (?, ?, ?, ?)"
                            + " ON CONFLICT (uuid, item) DO UPDATE SET"
                            + " job_amount = job_amount + excluded.job_amount,"
                            + " base_amount = base_amount + excluded.base_amount")) {
                ps.setString(1, sale.uuid().toString());
                ps.setString(2, sale.item());
                ps.setLong(3, sale.bonusAmount());
                ps.setLong(4, sale.baseAmount());
                ps.executeUpdate();
            }
            long today = dailySalesTotalInTx(sale.uuid(), sale.dayKey());
            upsertDailySales(sale.uuid(), sale.dayKey(), Math.max(0L, today - sale.total()));
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE sell_orders SET status = ?, reason = ? WHERE tx_id = ? AND status = ?")) {
                ps.setString(1, SALE_ABORTED);
                setNullable(ps, 2, reason);
                ps.setString(3, txId);
                ps.setString(4, SALE_PENDING);
                return ps.executeUpdate() == 1;
            }
        });
    }

    public int incrementSaleUnreadable(String txId, String reason) throws SQLException {
        return write(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE sell_orders SET unreadable_count = unreadable_count + 1, reason = ? WHERE tx_id = ?")) {
                setNullable(ps, 1, reason);
                ps.setString(2, txId);
                ps.executeUpdate();
            }
            SellOrderRecord updated = findSaleInTx(txId);
            return updated == null ? 0 : updated.unreadableCount();
        });
    }

    public Optional<SellOrderRecord> findSale(String txId) throws SQLException {
        return read(() -> Optional.ofNullable(findSaleInTx(txId)));
    }

    public List<SellOrderRecord> openSales() throws SQLException {
        return read(() -> {
            List<SellOrderRecord> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    SALE_SELECT + " WHERE status IN (?, ?) ORDER BY time_ms ASC")) {
                ps.setString(1, SALE_PENDING);
                ps.setString(2, SALE_ITEMS_TAKEN);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(saleRow(rs));
                    }
                }
            }
            return out;
        });
    }

    public List<SellOrderRecord> openSalesOf(UUID uuid) throws SQLException {
        return read(() -> {
            List<SellOrderRecord> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    SALE_SELECT + " WHERE uuid = ? AND status IN (?, ?) ORDER BY time_ms ASC")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, SALE_PENDING);
                ps.setString(3, SALE_ITEMS_TAKEN);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(saleRow(rs));
                    }
                }
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ 관리자 조회·초기화

    /** /market reset &lt;닉&gt;: 이번 주 희귀 상점 한도, 오늘 뽑기 한도, 오늘 판매 한도를 초기화합니다(천장은 유지). */
    public void resetLimits(UUID uuid, String weekKey, String dayKey) throws SQLException {
        write(() -> {
            String id = uuid.toString();
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM weekly_limits WHERE uuid = ? AND week_key = ?")) {
                ps.setString(1, id);
                ps.setString(2, weekKey);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM daily_pulls WHERE uuid = ? AND day_key = ?")) {
                ps.setString(1, id);
                ps.setString(2, dayKey);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM daily_sales WHERE uuid = ? AND day_key = ?")) {
                ps.setString(1, id);
                ps.setString(2, dayKey);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** /market log &lt;닉&gt;: 구매·뽑기·판매 기록을 최신순 통합 조회. */
    public List<LogEntry> recentLogs(UUID uuid, int limit) throws SQLException {
        return read(() -> {
            List<LogEntry> out = new ArrayList<>();
            String id = uuid.toString();
            String sql = """
                    SELECT time_ms, kind, summary, amount, bank_ref FROM (
                      SELECT time_ms, '희귀구매' AS kind,
                             item || ' x' || amount AS summary, -price AS amount, bank_ref
                        FROM purchases WHERE uuid = ?
                      UNION ALL
                      SELECT time_ms, '뽑기' AS kind,
                             tier || ':' || enchant || ' ' || level || ' (천장전 ' || pity_before || ')' AS summary,
                             -price AS amount, bank_ref
                        FROM pulls WHERE uuid = ?
                      UNION ALL
                      SELECT time_ms, '매입(' || price_type || ')' AS kind,
                             item || ' x' || amount AS summary, total AS amount, bank_ref
                        FROM sales WHERE uuid = ?
                    ) ORDER BY time_ms DESC LIMIT ?
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, id);
                ps.setString(2, id);
                ps.setString(3, id);
                ps.setInt(4, Math.max(1, limit));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long ref = rs.getLong(5);
                        Long bankRef = rs.wasNull() ? null : ref;
                        out.add(new LogEntry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), bankRef));
                    }
                }
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ 내부 SQL 도우미

    private static final String ORDER_SELECT =
            "SELECT tx_id, time_ms, uuid, kind, status, payload, limit_key, limit_delta, price,"
                    + " period_key, attempt, unreadable_count, bank_ref, pity_before, pity_after, reason"
                    + " FROM orders";

    private static final String SALE_SELECT =
            "SELECT tx_id, time_ms, uuid, item, unit, bonus_amount, base_amount, bonus_unit_price,"
                    + " base_unit_price, bonus_total, base_total, total, day_key, status, attempt,"
                    + " unreadable_count, bank_ref, reason FROM sell_orders";

    private OrderRecord findOrderInTx(String txId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(ORDER_SELECT + " WHERE tx_id = ?")) {
            ps.setString(1, txId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? orderRow(rs) : null;
            }
        }
    }

    private SellOrderRecord findSaleInTx(String txId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SALE_SELECT + " WHERE tx_id = ?")) {
            ps.setString(1, txId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? saleRow(rs) : null;
            }
        }
    }

    private static OrderRecord orderRow(ResultSet rs) throws SQLException {
        long br = rs.getLong(13);
        Long bankRef = rs.wasNull() ? null : br;
        int pb = rs.getInt(14);
        Integer pityBefore = rs.wasNull() ? null : pb;
        int pa = rs.getInt(15);
        Integer pityAfter = rs.wasNull() ? null : pa;
        return new OrderRecord(
                rs.getString(1), rs.getLong(2), UUID.fromString(rs.getString(3)),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getInt(8), rs.getLong(9), rs.getString(10), rs.getInt(11),
                rs.getInt(12), bankRef, pityBefore, pityAfter, rs.getString(16));
    }

    private static SellOrderRecord saleRow(ResultSet rs) throws SQLException {
        long br = rs.getLong(17);
        Long bankRef = rs.wasNull() ? null : br;
        return new SellOrderRecord(
                rs.getString(1), rs.getLong(2), UUID.fromString(rs.getString(3)),
                rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getInt(7),
                rs.getLong(8), rs.getLong(9), rs.getLong(10), rs.getLong(11),
                rs.getLong(12), rs.getString(13), rs.getString(14), rs.getInt(15),
                rs.getInt(16), bankRef, rs.getString(18));
    }

    private void insertOrder(String txId, long timeMs, UUID uuid, String kind, String status, String payload,
                             String limitKey, int limitDelta, long price, String periodKey,
                             Integer pityBefore, Integer pityAfter) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO orders (tx_id, time_ms, uuid, kind, status, payload, limit_key, limit_delta,"
                        + " price, period_key, pity_before, pity_after) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, txId);
            ps.setLong(2, timeMs);
            ps.setString(3, uuid.toString());
            ps.setString(4, kind);
            ps.setString(5, status);
            ps.setString(6, payload);
            ps.setString(7, limitKey);
            ps.setInt(8, limitDelta);
            ps.setLong(9, price);
            ps.setString(10, periodKey);
            setNullable(ps, 11, pityBefore);
            setNullable(ps, 12, pityAfter);
            ps.executeUpdate();
        }
    }

    private void upsertWeeklyLimit(UUID uuid, String weekKey, String limitKey, int used) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO weekly_limits (uuid, week_key, limit_key, used) VALUES (?, ?, ?, ?)"
                        + " ON CONFLICT (uuid, week_key, limit_key) DO UPDATE SET used = excluded.used")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, weekKey);
            ps.setString(3, limitKey);
            ps.setInt(4, used);
            ps.executeUpdate();
        }
    }

    private void upsertDailyPulls(UUID uuid, String dayKey, int used) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO daily_pulls (uuid, day_key, used) VALUES (?, ?, ?)"
                        + " ON CONFLICT (uuid, day_key) DO UPDATE SET used = excluded.used")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, dayKey);
            ps.setInt(3, used);
            ps.executeUpdate();
        }
    }

    private void upsertPity(UUID uuid, int count) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO pity (uuid, count) VALUES (?, ?)"
                        + " ON CONFLICT (uuid) DO UPDATE SET count = excluded.count")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, count);
            ps.executeUpdate();
        }
    }

    private void upsertDailySales(UUID uuid, String dayKey, long total) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO daily_sales (uuid, day_key, total) VALUES (?, ?, ?)"
                        + " ON CONFLICT (uuid, day_key) DO UPDATE SET total = excluded.total")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, dayKey);
            ps.setLong(3, total);
            ps.executeUpdate();
        }
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

    private static void setNullable(PreparedStatement ps, int idx, Object value) throws SQLException {
        if (value == null) {
            ps.setNull(idx, Types.NULL);
        } else if (value instanceof Long l) {
            ps.setLong(idx, l);
        } else if (value instanceof Integer i) {
            ps.setInt(idx, i);
        } else {
            ps.setString(idx, value.toString());
        }
    }

    // ------------------------------------------------------------------ 트랜잭션 관리

    @FunctionalInterface
    private interface SqlWork<T> {
        T run() throws SQLException;
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

    private <T> T write(SqlWork<T> work) throws SQLException {
        lock.lock();
        try {
            ensureUsable();
            if (inTx) {
                throw new SQLException("이미 트랜잭션 안에서 다시 쓰기를 시작함");
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
            return result;
        } finally {
            lock.unlock();
        }
    }

    private void clearStrayTransaction(SQLException cause) {
        try {
            exec("ROLLBACK");
        } catch (SQLException e) {
            String msg = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
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
            throw new SQLException("MarketStore 가 닫혀 있습니다.");
        }
    }

    private static void closeQuietly(Connection c) {
        try {
            c.close();
        } catch (SQLException ignored) {
            // 이미 닫힘
        }
    }
}
