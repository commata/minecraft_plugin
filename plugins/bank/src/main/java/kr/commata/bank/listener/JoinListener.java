package kr.commata.bank.listener;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import kr.commata.bank.BankService;
import kr.commata.bank.Msg;
import kr.commata.bank.core.BootReport;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** 접속 시 계좌 생성·닉네임 갱신. 관리자에게는 잠금 모드·직전 비정상 종료를 알립니다. */
public final class JoinListener implements Listener {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.ROOT);

    private final BankService bank;

    public JoinListener(BankService bank) {
        this.bank = bank;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        boolean admin = player.hasPermission("bank.admin");
        if (bank.locked()) {
            if (admin) {
                Msg.error(player, "잠금 모드입니다. 모든 입출금이 막혀 있습니다: " + bank.lockReason());
                Msg.error(player, "같은 시점의 전체 백업(월드 + plugins)으로 복원한 뒤 서버를 재시작하세요 (ECONOMY.md 2.3).");
            }
            return;
        }
        if (!bank.store().ensureAccount(player.getUniqueId(), player.getName())) {
            bank.logger().warning(player.getName() + " 계좌 생성·갱신 실패");
        }
        BootReport boot = bank.boot();
        if (admin && boot != null && boot.previousUnclean()) {
            Msg.error(player, "직전 서버 종료가 비정상이었습니다 (장애 추정 "
                    + TIME.format(Instant.ofEpochMilli(boot.crashEstimateMs()).atZone(bank.settings().zone()))
                    + "). 구매 유실 신고는 /bank log 로 확인 후 ECONOMY.md 2.4 절차로 처리하세요.");
        }
    }
}
