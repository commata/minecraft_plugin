package kr.commata.bank;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import kr.commata.bank.core.Account;
import kr.commata.bank.core.BankStore;
import kr.commata.bank.core.BootReport;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Player;

/** 명령·Vault 제공자·리스너가 함께 쓰는 상태. store 가 null 이면 잠금 모드. */
public final class BankService {

    private final Server server;
    private final Logger logger;
    private final BankStore store;
    private final String lockReason;
    private final BootReport boot;
    private volatile BankSettings settings = BankSettings.DEFAULTS;

    public BankService(Server server, Logger logger, BankStore store, String lockReason, BootReport boot) {
        this.server = server;
        this.logger = logger;
        this.store = store;
        this.lockReason = lockReason;
        this.boot = boot;
    }

    /** 잠금 모드면 null. */
    public BankStore store() {
        return store;
    }

    public boolean locked() {
        return store == null;
    }

    public String lockReason() {
        return lockReason;
    }

    /** 이번 기동을 시작할 때 이전 기동을 판정한 결과 (잠금 모드면 null). */
    public BootReport boot() {
        return boot;
    }

    public BankSettings settings() {
        return settings;
    }

    public void settings(BankSettings settings) {
        this.settings = settings;
    }

    public Logger logger() {
        return logger;
    }

    public Server server() {
        return server;
    }

    /** 닉네임으로 계좌 찾기: 접속 중이면 그 플레이어, 아니면 마지막으로 본 닉네임. */
    public Optional<Account> find(String name) {
        if (store == null) {
            return Optional.empty();
        }
        Player online = server.getPlayerExact(name);
        try {
            if (online != null) {
                Optional<Account> acc = store.account(online.getUniqueId());
                if (acc.isPresent()) {
                    return acc;
                }
            }
            return store.findByName(name);
        } catch (java.sql.SQLException e) {
            logger.warning("계좌 조회 실패: " + e.getMessage());
            return Optional.empty();
        }
    }

    public String nameOf(UUID uuid, String fallback) {
        if (store != null) {
            try {
                Optional<Account> acc = store.account(uuid);
                if (acc.isPresent() && acc.get().name() != null) {
                    return acc.get().name();
                }
            } catch (java.sql.SQLException ignored) {
                // 아래로
            }
        }
        return fallback;
    }

    /**
     * transactions.context: 플레이어 위치와 그 순간 열려 있던 인벤토리 창 제목 (ECONOMY.md 2.4).
     * 오프라인이거나 메인 스레드가 아니면 null.
     */
    public static String contextOf(Player player) {
        if (player == null || !Bukkit.isPrimaryThread() || !player.isOnline()) {
            return null;
        }
        try {
            Location l = player.getLocation();
            String title = PlainTextComponentSerializer.plainText().serialize(player.getOpenInventory().title());
            return l.getWorld().getName() + " " + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ()
                    + " | " + title;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
