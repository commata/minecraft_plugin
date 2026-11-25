package kr.commata.bank.vault;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import kr.commata.bank.BankService;
import kr.commata.bank.core.Account;
import kr.commata.bank.core.Amounts;
import kr.commata.bank.core.BankStore;
import kr.commata.bank.core.Failure;
import kr.commata.bank.core.Outcome;
import kr.commata.bank.core.TxType;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * VaultUnlocked 에 등록하는 구 Vault 경제 제공자. VJobs·EconomyShopGUI 가 이 잔액을 씁니다.
 * <ul>
 *   <li>입금·출금은 DB 커밋이 끝난 뒤에만 SUCCESS (커밋 실패면 FAILURE → 상점은 상품을 주지 않음)</li>
 *   <li>정수가 아닌 금액: 입금·출금은 FAILURE("정수 금액만 가능"), has 는 false. 반올림하지 않음 (ECONOMY.md 2.2)</li>
 *   <li>잠금 모드: 모든 입출금 FAILURE, has false, 잔액 0</li>
 *   <li>월드별 잔액 없음 (월드 인자는 무시), 은행 계좌 기능 없음</li>
 * </ul>
 */
public final class BankEconomy implements Economy {

    private static final String INTEGER_ONLY = "정수 금액만 가능";

    private final BankService bank;

    public BankEconomy(BankService bank) {
        this.bank = bank;
    }

    // ------------------------------------------------------------------ 기본 정보

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String getName() {
        return "Bank";
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return 0;
    }

    @Override
    public String format(double amount) {
        if (Amounts.check(Math.abs(amount)) == Amounts.Check.OK) {
            long v = (long) Math.abs(amount);
            return (amount < 0 ? "-" : "") + Amounts.format(v);
        }
        return String.format(Locale.ROOT, "%,.2f원", amount);
    }

    @Override
    public String currencyNamePlural() {
        return "원";
    }

    @Override
    public String currencyNameSingular() {
        return "원";
    }

    // ------------------------------------------------------------------ 계좌·잔액

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        BankStore store = bank.store();
        return store != null && player != null && store.hasAccount(player.getUniqueId());
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        BankStore store = bank.store();
        if (store == null || player == null) {
            return 0;
        }
        return store.balance(player.getUniqueId()).orElse(0);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        BankStore store = bank.store();
        if (store == null || player == null) {
            return false;
        }
        Amounts.Check check = Amounts.check(amount);
        if (check != Amounts.Check.OK) {
            warnRejected("has", player, amount, check);
            return false;
        }
        return store.balance(player.getUniqueId()).orElse(0) >= Amounts.exact(amount);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        BankStore store = bank.store();
        return store != null && player != null && store.ensureAccount(player.getUniqueId(), player.getName());
    }

    // ------------------------------------------------------------------ 입출금

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        return change(player, amount, false);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        return change(player, amount, true);
    }

    private EconomyResponse change(OfflinePlayer player, double amount, boolean deposit) {
        String op = deposit ? "depositPlayer" : "withdrawPlayer";
        BankStore store = bank.store();
        if (player == null) {
            return new EconomyResponse(amount, 0, ResponseType.FAILURE, "플레이어 없음");
        }
        if (store == null) {
            return new EconomyResponse(amount, 0, ResponseType.FAILURE, Failure.LOCKED.message());
        }
        UUID uuid = player.getUniqueId();
        long current = store.balance(uuid).orElse(0);
        Amounts.Check check = Amounts.check(amount);
        if (check != Amounts.Check.OK) {
            warnRejected(op, player, amount, check);
            String reason = check == Amounts.Check.NEGATIVE ? "음수 금액 불가" : INTEGER_ONLY;
            return new EconomyResponse(amount, current, ResponseType.FAILURE, reason);
        }
        long value = Amounts.exact(amount);
        if (value == 0) {
            // 바뀌는 것이 없으므로 기록하지 않음
            return new EconomyResponse(0, current, ResponseType.SUCCESS, null);
        }
        String source = CallerResolver.resolve();
        Player online = player.getPlayer();
        String context = BankService.contextOf(online);
        Outcome o = deposit
                ? store.deposit(uuid, player.getName(), value, TxType.DEPOSIT, source, context)
                : store.withdraw(uuid, value, TxType.WITHDRAW, source, context);
        if (o.ok()) {
            return new EconomyResponse(amount, o.balance(), ResponseType.SUCCESS, null);
        }
        if (o.failure() == Failure.STORAGE_ERROR) {
            bank.logger().severe(op + " 저장 실패 (" + source + ", " + player.getName() + ", " + value + "원): "
                    + o.detail());
        }
        return new EconomyResponse(amount, store.balance(uuid).orElse(0), ResponseType.FAILURE, o.message());
    }

    private void warnRejected(String op, OfflinePlayer player, double amount, Amounts.Check check) {
        bank.logger().warning("[금액 거부: " + check + "] " + CallerResolver.resolve() + " → " + op + "("
                + player.getName() + ", " + amount + "). 정수 금액만 받습니다 (ECONOMY.md 2.2)");
    }

    // ------------------------------------------------------------------ 닉네임·월드 인자 버전 (위로 위임)

    private OfflinePlayer byName(String name) {
        if (name == null) {
            return null;
        }
        Player online = bank.server().getPlayerExact(name);
        if (online != null) {
            return online;
        }
        Optional<Account> acc = bank.find(name);
        if (acc.isPresent()) {
            return bank.server().getOfflinePlayer(acc.get().uuid());
        }
        return bank.server().getOfflinePlayerIfCached(name);
    }

    @Override
    @Deprecated
    public boolean hasAccount(String playerName) {
        return hasAccount(byName(playerName));
    }

    @Override
    @Deprecated
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(byName(playerName));
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    @Override
    @Deprecated
    public double getBalance(String playerName) {
        return getBalance(byName(playerName));
    }

    @Override
    @Deprecated
    public double getBalance(String playerName, String world) {
        return getBalance(byName(playerName));
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    @Override
    @Deprecated
    public boolean has(String playerName, double amount) {
        return has(byName(playerName), amount);
    }

    @Override
    @Deprecated
    public boolean has(String playerName, String worldName, double amount) {
        return has(byName(playerName), amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    @Override
    @Deprecated
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        return withdrawPlayer(byName(playerName), amount);
    }

    @Override
    @Deprecated
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(byName(playerName), amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    @Deprecated
    public EconomyResponse depositPlayer(String playerName, double amount) {
        return depositPlayer(byName(playerName), amount);
    }

    @Override
    @Deprecated
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(byName(playerName), amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    @Override
    @Deprecated
    public boolean createPlayerAccount(String playerName) {
        return createPlayerAccount(byName(playerName));
    }

    @Override
    @Deprecated
    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(byName(playerName));
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }

    // ------------------------------------------------------------------ 은행 계좌 (지원 안 함)

    private static EconomyResponse noBanks() {
        return new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, "Bank 는 공동 계좌를 지원하지 않습니다");
    }

    @Override
    @Deprecated
    public EconomyResponse createBank(String name, String player) {
        return noBanks();
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return noBanks();
    }

    @Override
    @Deprecated
    public EconomyResponse isBankOwner(String name, String playerName) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    @Deprecated
    public EconomyResponse isBankMember(String name, String playerName) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    public List<String> getBanks() {
        return List.of();
    }
}
