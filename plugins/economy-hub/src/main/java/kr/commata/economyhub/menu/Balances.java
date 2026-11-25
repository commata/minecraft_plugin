package kr.commata.economyhub.menu;

import java.util.Locale;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * 잔액 읽기 (Vault, ECONOMY.md 3장). Vault 는 softdepend 라서 없을 수 있고, 그때는 이 클래스의 Vault 타입을 건드리지 않도록
 * 먼저 플러그인이 켜져 있는지 봅니다. 직업 보호 기능은 Vault 없이도 계속 돌아야 하므로 depend 로 두지 않습니다.
 */
final class Balances {

    private final Server server;

    Balances(Server server) {
        this.server = server;
    }

    /** "12,340원", 읽지 못하면 null */
    String formatted(Player player) {
        if (!server.getPluginManager().isPluginEnabled("Vault")) {
            return null;
        }
        try {
            RegisteredServiceProvider<Economy> provider = server.getServicesManager().getRegistration(Economy.class);
            if (provider == null) {
                return null;
            }
            // 잔액은 정수만 (ECONOMY.md 2.2). 혹시 소수가 와도 내림해서 보여 줌
            long balance = (long) Math.floor(provider.getProvider().getBalance(player));
            return String.format(Locale.ROOT, "%,d원", balance);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }
}
