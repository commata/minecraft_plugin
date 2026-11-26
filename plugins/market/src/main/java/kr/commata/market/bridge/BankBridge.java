package kr.commata.market.bridge;

import java.util.UUID;
import java.util.logging.Logger;
import kr.commata.bank.BankPlugin;
import kr.commata.bank.core.BankStore;
import kr.commata.bank.core.Failure;
import kr.commata.bank.core.Outcome;
import kr.commata.market.core.MarketPeriods;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Bank Java API(멱등 입출금·조회·1회 환불)와 Vault 잔액 조회 어댑터 (ECONOMY.md 13.6).
 *
 * <p>Bank 는 {@code softdepend}이므로 없거나 잠금 모드이면 모든 거래를 막고(fail-closed) 상태 문자열을 남깁니다.
 */
public final class BankBridge {

    public static final String SOURCE = "Market";

    public enum TxStatus { OK, REJECTED, UNKNOWN }

    public enum LookupStatus { FOUND, NOT_FOUND, ERROR }

    public record TxResult(TxStatus status, long bankTxId, String error) {
    }

    public record LookupResult(LookupStatus status, long bankTxId) {
    }

    /**
     * 환불 결과. {@code REJECTED}는 Bank 가 반영하지 않았음이 확실한 거부, {@code UNKNOWN}은 저장 오류·예외로
     * 반영 여부를 모르는 상태(자동으로 되돌리지 않음, AGENTS.md 5.3).
     */
    public enum RefundStatus { OK, ALREADY_REFUNDED, REJECTED, UNKNOWN }

    public record RefundResult(RefundStatus status, long refundTxId, long balance, String message) {
        public boolean ok() {
            return status == RefundStatus.OK;
        }
    }

    private final Server server;
    private final Logger logger;

    public BankBridge(Server server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    /** Bank 플러그인이 켜져 있고 잠금 모드가 아닐 때만 true. */
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

    /** 잔액 조회 (Vault API, ECONOMY.md 13.6). 읽지 못하면 -1. */
    public long balance(Player player) {
        if (!server.getPluginManager().isPluginEnabled("Vault")) {
            return -1L;
        }
        try {
            RegisteredServiceProvider<Economy> provider = server.getServicesManager().getRegistration(Economy.class);
            if (provider == null) {
                return -1L;
            }
            return (long) Math.floor(provider.getProvider().getBalance(player));
        } catch (LinkageError | RuntimeException e) {
            return -1L;
        }
    }

    public String formattedBalance(Player player) {
        long bal = balance(player);
        return bal < 0 ? "확인 불가" : MarketPeriods.won(bal);
    }

    public TxResult withdraw(UUID who, long amount, String extRef, String context) {
        Plugin raw = server.getPluginManager().getPlugin("Bank");
        if (raw == null || !raw.isEnabled()) {
            return new TxResult(TxStatus.REJECTED, 0L, "Bank 플러그인이 꺼져 있습니다.");
        }
        try {
            return Direct.withdraw(raw, who, amount, extRef, context);
        } catch (Throwable e) {
            logger.warning("Bank withdraw 예외 (extRef=" + extRef + "): " + e);
            return new TxResult(TxStatus.UNKNOWN, 0L, e.getMessage());
        }
    }

    public TxResult deposit(UUID who, long amount, String extRef, String context) {
        Plugin raw = server.getPluginManager().getPlugin("Bank");
        if (raw == null || !raw.isEnabled()) {
            return new TxResult(TxStatus.REJECTED, 0L, "Bank 플러그인이 꺼져 있습니다.");
        }
        try {
            return Direct.deposit(raw, who, amount, extRef, context);
        } catch (Throwable e) {
            logger.warning("Bank deposit 예외 (extRef=" + extRef + "): " + e);
            return new TxResult(TxStatus.UNKNOWN, 0L, e.getMessage());
        }
    }

    public LookupResult findByExtRef(String extRef) {
        Plugin raw = server.getPluginManager().getPlugin("Bank");
        if (raw == null || !raw.isEnabled()) {
            return new LookupResult(LookupStatus.ERROR, 0L);
        }
        try {
            return Direct.findByExtRef(raw, extRef);
        } catch (Throwable e) {
            logger.warning("Bank findByExtRef 예외 (extRef=" + extRef + "): " + e);
            return new LookupResult(LookupStatus.ERROR, 0L);
        }
    }

    public RefundResult refundByExtRef(String extRef, String context) {
        Plugin raw = server.getPluginManager().getPlugin("Bank");
        if (raw == null || !raw.isEnabled()) {
            return new RefundResult(RefundStatus.UNKNOWN, 0L, 0L, "Bank 플러그인이 꺼져 있습니다.");
        }
        try {
            return Direct.refundByExtRef(raw, extRef, context);
        } catch (Throwable e) {
            logger.warning("Bank refundByExtRef 예외 (extRef=" + extRef + "): " + e);
            return new RefundResult(RefundStatus.UNKNOWN, 0L, 0L, "환불 처리 중 오류: " + e.getMessage());
        }
    }

    /** Bank 클래스 로딩을 격리해 Bank 미설치 환경에서도 BankBridge 로드 자체는 실패하지 않게 함. */
    private static final class Direct {
        static boolean isUsable(Plugin raw) {
            return raw instanceof BankPlugin bank && !bank.isLocked();
        }

        static TxResult withdraw(Plugin raw, UUID who, long amount, String extRef, String context) {
            if (!(raw instanceof BankPlugin bank)) {
                return new TxResult(TxStatus.REJECTED, 0L, "Bank 플러그인 타입 불일치");
            }
            BankStore.Receipt r = bank.withdraw(who, amount, SOURCE, extRef, context);
            return mapReceipt(r);
        }

        static TxResult deposit(Plugin raw, UUID who, long amount, String extRef, String context) {
            if (!(raw instanceof BankPlugin bank)) {
                return new TxResult(TxStatus.REJECTED, 0L, "Bank 플러그인 타입 불일치");
            }
            BankStore.Receipt r = bank.deposit(who, amount, SOURCE, extRef, context);
            return mapReceipt(r);
        }

        static LookupResult findByExtRef(Plugin raw, String extRef) {
            if (!(raw instanceof BankPlugin bank)) {
                return new LookupResult(LookupStatus.ERROR, 0L);
            }
            BankStore.Lookup l = bank.findByExtRef(extRef);
            LookupStatus status = switch (l.status()) {
                case FOUND -> LookupStatus.FOUND;
                case NOT_FOUND -> LookupStatus.NOT_FOUND;
                case ERROR -> LookupStatus.ERROR;
            };
            return new LookupResult(status, l.txId());
        }

        static RefundResult refundByExtRef(Plugin raw, String extRef, String context) {
            if (!(raw instanceof BankPlugin bank)) {
                return new RefundResult(RefundStatus.UNKNOWN, 0L, 0L, "Bank 플러그인 타입 불일치");
            }
            if (bank.isLocked()) {
                return new RefundResult(RefundStatus.UNKNOWN, 0L, 0L, "Bank 플러그인이 잠금 모드입니다.");
            }
            Outcome o = bank.refundByExtRef(extRef, SOURCE, context);
            RefundStatus status;
            if (o.ok()) {
                status = RefundStatus.OK;
            } else if (o.failure() == Failure.ALREADY_REFUNDED) {
                status = RefundStatus.ALREADY_REFUNDED;
            } else if (o.failure() == Failure.STORAGE_ERROR || o.failure() == Failure.LOCKED || o.failure() == Failure.CLOSED) {
                status = RefundStatus.UNKNOWN;
            } else {
                status = RefundStatus.REJECTED;
            }
            return new RefundResult(status, o.txId(), o.balance(), o.message());
        }

        private static TxResult mapReceipt(BankStore.Receipt r) {
            TxStatus status = switch (r.status()) {
                case OK -> TxStatus.OK;
                case REJECTED -> TxStatus.REJECTED;
                case UNKNOWN -> TxStatus.UNKNOWN;
            };
            return new TxResult(status, r.txId(), null);
        }
    }
}
