package kr.commata.trade.bridge;

import java.util.UUID;
import java.util.logging.Logger;
import kr.commata.bank.BankPlugin;
import kr.commata.bank.core.Sources;
import kr.commata.bank.core.TradeFailure;
import kr.commata.bank.core.TradeLookup;
import kr.commata.bank.core.TradeOutcome;
import kr.commata.trade.core.RecoveryPolicy;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Bank Java API({@code settleTrade}·{@code findTradeByExtRef})와 Vault 잔액 조회 어댑터 (TRADE.md 4·10장).
 *
 * <p>Bank 는 {@code softdepend} 이므로 없거나 잠금 모드이면 Trade 전체를 잠급니다(fail-closed, TRADE.md 1·10장 5).
 * {@link Direct} 내부 클래스로 Bank 클래스 로딩을 격리해 Bank 미설치 서버에서도 플러그인 로드는 안전하게 진행됩니다.
 */
public final class BankBridge {

    public enum SettleStatus {
        OK, REJECTED, MISMATCH, UNKNOWN
    }

    public record SettleResult(
            SettleStatus status,
            UUID affectedPlayer,
            long txAId,
            long txBId,
            boolean idempotent,
            String message
    ) {
        public boolean ok() {
            return status == SettleStatus.OK;
        }
    }

    public record LookupResult(
            RecoveryPolicy.BankLookupState state,
            long txAId,
            long txBId,
            String detail
    ) {
    }

    private final Server server;
    private final Logger logger;

    public BankBridge(Server server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    /** Bank 플러그인이 켜져 있고 잠금 모드가 아닐 때만 {@code true}. */
    public boolean available() {
        Plugin raw = server.getPluginManager().getPlugin("Bank");
        if (raw == null || !raw.isEnabled()) {
            return false;
        }
        try {
            return Direct.isUsable(raw);
        } catch (LinkageError | RuntimeException e) {
            return false;
        }
    }

    public String statusText() {
        Plugin raw = server.getPluginManager().getPlugin("Bank");
        if (raw == null || !raw.isEnabled()) {
            return "꺼짐 (Bank 플러그인 없음)";
        }
        try {
            return Direct.isUsable(raw) ? "연결됨" : "잠김 (Bank 잠금 모드)";
        } catch (LinkageError | RuntimeException e) {
            return "오류 (" + e.getClass().getSimpleName() + ")";
        }
    }

    /** Vault API 를 통한 잔액 조회. 조회 불가면 {@code -1L}. */
    public long balance(Player player) {
        if (player == null || !server.getPluginManager().isPluginEnabled("Vault")) {
            return -1L;
        }
        try {
            RegisteredServiceProvider<Economy> provider = server.getServicesManager().getRegistration(Economy.class);
            if (provider == null) {
                return -1L;
            }
            double raw = provider.getProvider().getBalance(player);
            if (Double.isNaN(raw) || Double.isInfinite(raw) || raw < 0.0) {
                return -1L;
            }
            return (long) Math.floor(raw);
        } catch (LinkageError | RuntimeException e) {
            return -1L;
        }
    }

    /** 거래번호 {@code "trade:<tradeId>"} 로 한 트랜잭션 양방향 정산을 실행합니다 (TRADE.md 4장). */
    public SettleResult settleTrade(UUID tradeId, UUID a, long aPays, UUID b, long bPays, String context) {
        String extRef = extRef(tradeId);
        Plugin raw = server.getPluginManager().getPlugin("Bank");
        if (raw == null || !raw.isEnabled()) {
            return new SettleResult(SettleStatus.UNKNOWN, null, 0L, 0L, false, "Bank 플러그인이 꺼져 있습니다.");
        }
        try {
            return Direct.settleTrade(raw, extRef, a, aPays, b, bPays, context);
        } catch (Throwable e) {
            logger.severe("Bank settleTrade 예외 (extRef=" + extRef + "): " + e);
            return new SettleResult(SettleStatus.UNKNOWN, null, 0L, 0L, false, "예외: " + e.getMessage());
        }
    }

    /** 거래번호 {@code "trade:<tradeId>"} 의 정산 원장 기록을 조회하고 당사자·금액 일치를 검증합니다 (TRADE.md 4장). */
    public LookupResult findTradeByExtRef(UUID tradeId, UUID a, long aPays, UUID b, long bPays) {
        String extRef = extRef(tradeId);
        Plugin raw = server.getPluginManager().getPlugin("Bank");
        if (raw == null || !raw.isEnabled()) {
            return new LookupResult(RecoveryPolicy.BankLookupState.ERROR, 0L, 0L, "Bank 플러그인이 꺼져 있습니다.");
        }
        try {
            return Direct.findTradeByExtRef(raw, extRef, a, aPays, b, bPays);
        } catch (Throwable e) {
            logger.warning("Bank findTradeByExtRef 예외 (extRef=" + extRef + "): " + e);
            return new LookupResult(RecoveryPolicy.BankLookupState.ERROR, 0L, 0L, "예외: " + e.getMessage());
        }
    }

    public static String extRef(UUID tradeId) {
        return "trade:" + tradeId;
    }

    private static final class Direct {

        static boolean isUsable(Plugin raw) {
            return raw instanceof BankPlugin bank && !bank.isLocked();
        }

        static SettleResult settleTrade(Plugin raw, String extRef, UUID a, long aPays, UUID b, long bPays, String context) {
            if (!(raw instanceof BankPlugin bank)) {
                return new SettleResult(SettleStatus.UNKNOWN, null, 0L, 0L, false, "Bank 플러그인 타입 불일치");
            }
            if (bank.isLocked()) {
                return new SettleResult(SettleStatus.UNKNOWN, null, 0L, 0L, false, TradeFailure.LOCKED.message());
            }
            TradeOutcome out = bank.settleTrade(extRef, a, aPays, b, bPays, Sources.TRADE, context);
            SettleStatus status = switch (out.status()) {
                case OK -> SettleStatus.OK;
                case REJECTED -> (out.failure() == TradeFailure.LOCKED
                        || out.failure() == TradeFailure.CLOSED
                        || out.failure() == TradeFailure.STORAGE_ERROR)
                        ? SettleStatus.UNKNOWN : SettleStatus.REJECTED;
                case MISMATCH -> SettleStatus.MISMATCH;
                case UNKNOWN -> SettleStatus.UNKNOWN;
            };
            return new SettleResult(status, out.affectedPlayer(), out.txAId(), out.txBId(), out.idempotent(), out.message());
        }

        static LookupResult findTradeByExtRef(Plugin raw, String extRef, UUID a, long aPays, UUID b, long bPays) {
            if (!(raw instanceof BankPlugin bank)) {
                return new LookupResult(RecoveryPolicy.BankLookupState.ERROR, 0L, 0L, "Bank 플러그인 타입 불일치");
            }
            if (bank.isLocked()) {
                return new LookupResult(RecoveryPolicy.BankLookupState.ERROR, 0L, 0L, "Bank 잠금 모드");
            }
            TradeLookup lookup = bank.findTradeByExtRef(extRef);
            return switch (lookup.status()) {
                case NOT_FOUND -> new LookupResult(RecoveryPolicy.BankLookupState.NOT_FOUND, 0L, 0L, "");
                case ERROR -> new LookupResult(RecoveryPolicy.BankLookupState.ERROR, 0L, 0L, lookup.detail());
                case FOUND -> {
                    TradeLookup.TradeRecord rec = lookup.record();
                    if (rec != null && rec.matches(a, aPays, b, bPays)) {
                        yield new LookupResult(RecoveryPolicy.BankLookupState.FOUND_MATCH, rec.txAId(), rec.txBId(), "");
                    }
                    yield new LookupResult(RecoveryPolicy.BankLookupState.FOUND_MISMATCH, 0L, 0L,
                            "원장 기록 불일치: " + rec);
                }
            };
        }
    }
}
