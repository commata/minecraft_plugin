package kr.commata.market.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import kr.commata.market.MarketConfig;
import kr.commata.market.Msg;
import kr.commata.market.core.MarketPeriods;
import kr.commata.market.core.MarketStore;
import kr.commata.market.core.OrderPayload;
import kr.commata.market.gui.MarketMenus;
import kr.commata.market.service.MarketService;
import kr.commata.market.service.PlayerDataGuard;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /market} 명령 (ECONOMY.md 13.6):
 * <ul>
 *   <li>인자 없음: 희귀 상점 GUI 열기</li>
 *   <li>{@code /market claim}: 미수령 구매품 받기 (일반 플레이어 허용)</li>
 *   <li>{@code /market status|reload|log|reset|pending|resolve}: 관리자 ({@code market.admin})</li>
 * </ul>
 */
public final class MarketCommand implements BasicCommand {

    private static final String ADMIN_PERM = "market.admin";
    private static final List<String> ADMIN_SUBS = List.of("status", "reload", "log", "reset", "pending", "resolve");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private final Server server;
    private final MarketService market;
    private final MarketMenus menus;
    private final PlayerDataGuard guard;
    private final Runnable reloadAction;

    public MarketCommand(Server server, MarketService market, MarketMenus menus,
                         PlayerDataGuard guard, Runnable reloadAction) {
        this.server = server;
        this.market = market;
        this.menus = menus;
        this.guard = guard;
        this.reloadAction = reloadAction;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (args.length == 0) {
            if (source.getExecutor() instanceof Player player) {
                menus.openRareShop(player);
            } else {
                status(sender);
            }
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if ("claim".equals(sub)) {
            if (source.getExecutor() instanceof Player player) {
                market.claimPendingOrders(player);
            } else {
                Msg.error(sender, "게임 안에서만 쓸 수 있습니다.");
            }
            return;
        }
        if (!sender.hasPermission(ADMIN_PERM)) {
            Msg.error(sender, "권한이 없습니다.");
            return;
        }
        switch (sub) {
            case "status" -> status(sender);
            case "reload" -> {
                reloadAction.run();
                Msg.success(sender, "Market config.yml 을 다시 불러왔습니다.");
                status(sender);
            }
            case "log" -> log(sender, args);
            case "reset" -> reset(sender, args);
            case "pending" -> pending(sender);
            case "resolve" -> resolve(sender, args);
            default -> usage(sender);
        }
    }

    private void usage(CommandSender sender) {
        Msg.info(sender, "Market 관리자 명령:");
        Msg.raw(sender, "<#AAAAAA>  /market status · reload · pending");
        Msg.raw(sender, "<#AAAAAA>  /market log <닉네임> [개수]");
        Msg.raw(sender, "<#AAAAAA>  /market reset <닉네임>  (이번 주·오늘 한도 초기화)");
        Msg.raw(sender, "<#AAAAAA>  /market resolve <tx_id> refund");
        Msg.raw(sender, "<#AAAAAA>  /market resolve <닉네임|UUID> unquarantine");
    }

    private void status(CommandSender sender) {
        MarketConfig cfg = market.config();
        Msg.info(sender, "Market 상태");
        Msg.raw(sender, "<#AAAAAA>  DB: " + (market.dbLocked()
                ? "<#FF5555>잠금 (" + market.dbLockReason() + ")" : "<#55FF55>정상"));
        Msg.raw(sender, "<#AAAAAA>  Bank: " + (market.bank().available()
                ? "<#55FF55>" + market.bank().statusText() : "<#FF5555>" + market.bank().statusText()));
        Msg.raw(sender, "<#AAAAAA>  VJobs: " + (market.vjobs().available() ? "<#55FF55>연결됨" : "<#FFDF6B>미연결"));
        Msg.raw(sender, "<#AAAAAA>  희귀 상점: " + featureState(cfg.rare().available(), cfg.rare().lockReason()));
        Msg.raw(sender, "<#AAAAAA>  인첸트 뽑기: " + featureState(cfg.gacha().available(), cfg.gacha().lockReason()));
        Msg.raw(sender, "<#AAAAAA>  서버 매입: " + featureState(cfg.sell().available(), cfg.sell().lockReason()));
        boolean hubEnabled = server.getPluginManager().isPluginEnabled("EconomyHub");
        Msg.raw(sender, "<#AAAAAA>  블록 판매 가능량 집계: " + (hubEnabled
                ? "<#55FF55>켜짐" : "<#FF5555>꺼짐 (EconomyHub 없음/꺼짐)"));
    }

    private static String featureState(boolean available, String reason) {
        return available ? "<#55FF55>켜짐" : "<#FF5555>잠김 (" + reason + ")";
    }

    private void log(CommandSender sender, String[] args) {
        if (market.dbLocked()) {
            Msg.error(sender, "Market DB 가 잠금 모드입니다.");
            return;
        }
        if (args.length < 2 || args.length > 3) {
            Msg.error(sender, "사용법: /market log <닉네임> [개수]");
            return;
        }
        OfflinePlayer target = resolvePlayer(args[1]);
        if (target == null) {
            Msg.error(sender, "플레이어를 찾을 수 없습니다: " + args[1]);
            return;
        }
        int limit = 15;
        if (args.length == 3) {
            try {
                limit = Math.clamp(Integer.parseInt(args[2]), 1, 50);
            } catch (NumberFormatException e) {
                Msg.error(sender, "개수는 1~50 사이의 정수여야 합니다.");
                return;
            }
        }
        try {
            List<MarketStore.LogEntry> entries = market.store().recentLogs(target.getUniqueId(), limit);
            int pity = market.store().pity(target.getUniqueId());
            long todaySell = market.store().dailySalesTotal(target.getUniqueId(), market.currentDayKey());
            String name = target.getName() != null ? target.getName() : target.getUniqueId().toString();
            Msg.info(sender, name + "님 최근 마켓 기록 " + entries.size() + "건 (천장 " + pity
                    + ", 오늘 판매 " + MarketPeriods.won(todaySell) + ")");
            if (entries.isEmpty()) {
                Msg.raw(sender, "<#AAAAAA>  기록 없음");
                return;
            }
            for (MarketStore.LogEntry e : entries) {
                String amountStr = (e.amount() >= 0 ? "<#55FF55>+" : "<#FF5555>-")
                        + MarketPeriods.won(Math.abs(e.amount()));
                String refStr = e.bankRef() == null ? "" : " <#AAAAAA>(Bank #" + e.bankRef() + ")";
                Msg.raw(sender, "<#AAAAAA>" + formatTime(e.timeMs()) + " <#F5C542>[" + e.kind() + "] <white>"
                        + e.summary() + " " + amountStr + refStr);
            }
        } catch (SQLException e) {
            Msg.error(sender, "기록 조회 실패: " + e.getMessage());
        }
    }

    private void reset(CommandSender sender, String[] args) {
        if (market.dbLocked()) {
            Msg.error(sender, "Market DB 가 잠금 모드입니다.");
            return;
        }
        if (args.length != 2) {
            Msg.error(sender, "사용법: /market reset <닉네임>");
            return;
        }
        OfflinePlayer target = resolvePlayer(args[1]);
        if (target == null) {
            Msg.error(sender, "플레이어를 찾을 수 없습니다: " + args[1]);
            return;
        }
        try {
            market.store().resetLimits(target.getUniqueId(), market.currentWeekKey(), market.currentDayKey());
            String name = target.getName() != null ? target.getName() : target.getUniqueId().toString();
            Msg.success(sender, name + "님의 이번 주 희귀 상점·오늘 뽑기·오늘 판매 한도를 초기화했습니다 (천장은 유지).");
        } catch (SQLException e) {
            Msg.error(sender, "한도 초기화 실패: " + e.getMessage());
        }
    }

    private void pending(CommandSender sender) {
        if (market.dbLocked()) {
            Msg.error(sender, "Market DB 가 잠금 모드입니다.");
            return;
        }
        try {
            List<MarketStore.OrderRecord> orders = market.store().unresolvedOrders();
            List<MarketStore.SellOrderRecord> sales = market.store().openSales();
            List<MarketStore.QuarantineRecord> quarantined = market.store().allQuarantined();
            Map<UUID, String> preUnreadable = guard.unreadablePlayers();

            Msg.info(sender, "보류·확인 대기 목록 (주문 " + orders.size() + "건, 판매 " + sales.size()
                    + "건, 격리 " + quarantined.size() + "명, 접속불가 " + preUnreadable.size() + "명)");

            for (MarketStore.OrderRecord o : orders) {
                Msg.raw(sender, "<#F5C542>[주문:" + o.status() + "] <white>" + o.txId()
                        + " <#AAAAAA>| " + playerName(o.uuid())
                        + " | " + OrderPayload.summary(o.kind(), o.payload())
                        + " (" + MarketPeriods.won(o.price()) + ")"
                        + " | 시도 " + o.attempt() + ", 실패 " + o.unreadableCount()
                        + (o.reason() != null ? " | 사유: " + o.reason() : ""));
            }
            for (MarketStore.SellOrderRecord s : sales) {
                Msg.raw(sender, "<#55FFFF>[판매:" + s.status() + "] <white>" + s.txId()
                        + " <#AAAAAA>| " + playerName(s.uuid())
                        + " | " + s.item() + " x" + (s.bonusAmount() + s.baseAmount())
                        + " (" + MarketPeriods.won(s.total()) + ")"
                        + " | 실패 " + s.unreadableCount()
                        + (s.reason() != null ? " | 사유: " + s.reason() : ""));
            }
            for (MarketStore.QuarantineRecord q : quarantined) {
                Msg.raw(sender, "<#FF5555>[격리] <white>" + playerName(q.uuid()) + " (" + q.uuid() + ")"
                        + " <#AAAAAA>| " + q.reason()
                        + (q.backupFile() != null ? " | 백업: " + q.backupFile() : ""));
            }
            for (Map.Entry<UUID, String> u : preUnreadable.entrySet()) {
                Msg.raw(sender, "<#FF5555>[접속차단] <white>" + playerName(u.getKey()) + " (" + u.getKey() + ")"
                        + " <#AAAAAA>| " + u.getValue());
            }
            if (orders.isEmpty() && sales.isEmpty() && quarantined.isEmpty() && preUnreadable.isEmpty()) {
                Msg.raw(sender, "<#55FF55>  보류 중인 항목이 없습니다.");
            }
        } catch (SQLException e) {
            Msg.error(sender, "보류 목록 조회 실패: " + e.getMessage());
        }
    }

    private void resolve(CommandSender sender, String[] args) {
        if (args.length != 3) {
            Msg.error(sender, "사용법: /market resolve <tx_id> refund 또는 /market resolve <닉네임|UUID> unquarantine");
            return;
        }
        String action = args[2].toLowerCase(Locale.ROOT);
        if ("refund".equals(action)) {
            String result = market.resolveRefund(args[1], sender.getName());
            Msg.info(sender, result);
            return;
        }
        if ("unquarantine".equals(action)) {
            if (market.dbLocked()) {
                Msg.error(sender, "Market DB 가 잠금 모드입니다.");
                return;
            }
            UUID uuid = parseUuidOrPlayer(args[1]);
            if (uuid == null) {
                Msg.error(sender, "플레이어 또는 UUID를 찾을 수 없습니다: " + args[1]);
                return;
            }
            try {
                boolean removed = market.store().unquarantinePlayer(uuid);
                guard.clearUnreadable(uuid);
                if (removed) {
                    Msg.success(sender, playerName(uuid) + "의 접속 격리를 해제했습니다.");
                } else {
                    Msg.warn(sender, playerName(uuid) + "은(는) 격리 목록에 없었습니다 (접속 불가 캐시만 정리됨).");
                }
            } catch (SQLException e) {
                Msg.error(sender, "격리 해제 실패: " + e.getMessage());
            }
            return;
        }
        Msg.error(sender, "지원하는 해결 동작: refund, unquarantine");
    }

    private OfflinePlayer resolvePlayer(String nameOrUuid) {
        Player online = server.getPlayerExact(nameOrUuid);
        if (online != null) {
            return online;
        }
        try {
            return server.getOfflinePlayer(UUID.fromString(nameOrUuid));
        } catch (IllegalArgumentException ignored) {
            // 닉네임 조회
        }
        OfflinePlayer cached = server.getOfflinePlayerIfCached(nameOrUuid);
        return cached;
    }

    private UUID parseUuidOrPlayer(String nameOrUuid) {
        try {
            return UUID.fromString(nameOrUuid);
        } catch (IllegalArgumentException ignored) {
            OfflinePlayer p = resolvePlayer(nameOrUuid);
            return p == null ? null : p.getUniqueId();
        }
    }

    private String playerName(UUID uuid) {
        Player online = server.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        OfflinePlayer off = server.getOfflinePlayer(uuid);
        return off.getName() != null ? off.getName() : uuid.toString().substring(0, 8);
    }

    private String formatTime(long epochMs) {
        return TIME.format(Instant.ofEpochMilli(epochMs).atZone(market.config().zone()));
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            if ("claim".startsWith(prefix)) {
                out.add("claim");
            }
            if (sender.hasPermission(ADMIN_PERM)) {
                for (String sub : ADMIN_SUBS) {
                    if (sub.startsWith(prefix)) {
                        out.add(sub);
                    }
                }
            }
            return out;
        }
        if (!sender.hasPermission(ADMIN_PERM)) {
            return List.of();
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && ("log".equals(sub) || "reset".equals(sub))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<>();
            for (Player p : server.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    names.add(p.getName());
                }
            }
            return names;
        }
        if (args.length == 3 && "resolve".equals(sub)) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return List.of("refund", "unquarantine").stream()
                    .filter(s -> s.startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}
