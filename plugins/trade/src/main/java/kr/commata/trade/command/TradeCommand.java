package kr.commata.trade.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;
import kr.commata.trade.Msg;
import kr.commata.trade.TradeConfig;
import kr.commata.trade.core.Journal;
import kr.commata.trade.core.OfferValidation;
import kr.commata.trade.gui.TradeUiManager;
import kr.commata.trade.service.TradeService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /trade} ({@code /거래}) 명령어 처리기 (TRADE.md 1·6·8장, ECONOMY.md 7.3·7.4.6a).
 *
 * <p>일반 명령: {@code /trade}, {@code /trade <닉네임>}, {@code accept|수락}, {@code deny|거절},
 * {@code cancel|취소}, {@code claim|수령}.
 * 관리자 명령({@code trade.admin}, 콘솔 가능): {@code status}, {@code reload}, {@code resolve unlock|<tradeId> [dismiss]}.
 */
public final class TradeCommand implements BasicCommand {

    private final Server server;
    private final Logger logger;
    private final TradeService service;
    private final TradeUiManager uiManager;
    private final Supplier<String> reloadCallback;

    public TradeCommand(Server server, Logger logger, TradeService service,
                        TradeUiManager uiManager, Supplier<String> reloadCallback) {
        this.server = server;
        this.logger = logger;
        this.service = service;
        this.uiManager = uiManager;
        this.reloadCallback = reloadCallback;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();

        if (args.length > 0) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "status" -> {
                    if (!requireAdmin(sender)) {
                        return;
                    }
                    handleStatus(sender);
                    return;
                }
                case "reload" -> {
                    if (!requireAdmin(sender)) {
                        return;
                    }
                    handleReload(sender);
                    return;
                }
                case "resolve" -> {
                    if (!requireAdmin(sender)) {
                        return;
                    }
                    handleResolve(sender, args);
                    return;
                }
                default -> { }
            }
        }

        if (!(source.getExecutor() instanceof Player player)) {
            Msg.error(sender, "게임 안에서만 쓸 수 있습니다. (관리자 명령: /trade status | reload | resolve)");
            return;
        }
        if (!player.hasPermission("trade.use")) {
            Msg.error(player, "거래 명령을 사용할 권한이 없습니다.");
            return;
        }

        if (args.length == 0) {
            if (service.isGloballyLocked()) {
                Msg.error(player, "현재 거래 기능을 사용할 수 없습니다 (<reason>).",
                        Placeholder.unparsed("reason", service.globalLockReason()));
                return;
            }
            uiManager.openPlayerSelectMenu(player);
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "accept", "수락" -> service.acceptRequest(player, args.length >= 2 ? args[1] : null);
            case "deny", "거절" -> service.denyRequest(player, args.length >= 2 ? args[1] : null);
            case "cancel", "취소" -> service.cancelOutgoingOrActive(player);
            case "claim", "수령" -> service.claimPending(player);
            default -> {
                Player target = findOnlineTarget(player, args[0]);
                if (target == null) {
                    Msg.error(player, "'<arg>' 플레이어를 찾을 수 없습니다.", Placeholder.unparsed("arg", args[0]));
                    return;
                }
                service.sendRequest(player, target);
            }
        }
    }

    private void handleStatus(CommandSender sender) {
        TradeConfig cfg = service.config();
        boolean locked = service.isGloballyLocked();
        Msg.info(sender, "<#F5C542>=== Trade 상태 ===");
        if (locked) {
            Msg.raw(sender, "<#AAAAAA>  상태: <#FF5555>잠김 (<reason>)",
                    Placeholder.unparsed("reason", service.globalLockReason()));
        } else {
            Msg.raw(sender, "<#AAAAAA>  상태: <#55FF55>정상 운영");
        }
        Msg.raw(sender, "<#AAAAAA>  연동: Bank=<bank>, Travel=<travel>",
                Placeholder.unparsed("bank", service.bank().statusText()),
                Placeholder.unparsed("travel", service.travel().statusText()));
        Msg.raw(sender, "<#AAAAAA>  설정: 요청=" + cfg.requestTimeoutSeconds() + "초, 카운트다운="
                + cfg.countdownSeconds() + "초, 재요청 금지=" + cfg.reRequestCooldownSeconds()
                + "초, 대화창 만료=" + cfg.dialogTimeoutSeconds() + "초, 타임존=" + cfg.zone());
        Msg.raw(sender, "<#AAAAAA>  진행 중 세션: <#FFDF6B>" + service.sessions().activeCount() + "건");

        try {
            List<String> corrupt = service.journalStore().unresolvedCorruptFiles();
            if (!corrupt.isEmpty()) {
                Msg.raw(sender, "<#FF5555>  미해결 손상 저널: <files>",
                        Placeholder.unparsed("files", String.join(", ", corrupt)));
            }
        } catch (IOException e) {
            Msg.raw(sender, "<#FF5555>  손상 저널 폴더 조회 실패: <err>",
                    Placeholder.unparsed("err", String.valueOf(e.getMessage())));
        }

        if (!service.saveFailedLockedPlayers().isEmpty()) {
            Msg.raw(sender, "<#FF5555>  저장 확인 실패 잠금 플레이어: " + service.saveFailedLockedPlayers().size() + "명 "
                    + service.saveFailedLockedPlayers());
        }

        Collection<Journal.Entry> open = service.openJournals();
        Msg.raw(sender, "<#AAAAAA>  미완료 저널: <#FFDF6B>" + open.size() + "건");
        for (Journal.Entry e : open) {
            if (e.isSealed()) {
                Msg.raw(sender, "<#AAAAAA>   - <#FFDF6B>" + e.tradeId()
                        + " <#AAAAAA><name_a>(" + OfferValidation.formatWon(e.aPays()) + ") ↔ "
                        + "<name_b>(" + OfferValidation.formatWon(e.bPays()) + ")"
                        + " | stage=" + e.stage()
                        + " take=" + e.takeA() + "/" + e.takeB()
                        + " give=" + e.giveA() + "/" + e.giveB()
                        + " ret=" + e.returnA() + "/" + e.returnB()
                        + " <#FF5555>[봉인: <sealed>]",
                        Placeholder.unparsed("name_a", e.nameA()),
                        Placeholder.unparsed("name_b", e.nameB()),
                        Placeholder.unparsed("sealed", e.sealedReason()));
            } else {
                Msg.raw(sender, "<#AAAAAA>   - <#FFDF6B>" + e.tradeId()
                        + " <#AAAAAA><name_a>(" + OfferValidation.formatWon(e.aPays()) + ") ↔ "
                        + "<name_b>(" + OfferValidation.formatWon(e.bPays()) + ")"
                        + " | stage=" + e.stage()
                        + " take=" + e.takeA() + "/" + e.takeB()
                        + " give=" + e.giveA() + "/" + e.giveB()
                        + " ret=" + e.returnA() + "/" + e.returnB(),
                        Placeholder.unparsed("name_a", e.nameA()),
                        Placeholder.unparsed("name_b", e.nameB()));
            }
        }
    }

    private void handleReload(CommandSender sender) {
        String summary = reloadCallback.get();
        Msg.infoPlain(sender, summary);
    }

    private void handleResolve(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Msg.error(sender, "사용법: /trade resolve unlock | /trade resolve <tradeId> [dismiss]");
            return;
        }
        String target = args[1];
        if (target.equalsIgnoreCase("unlock")) {
            Journal.UnlockResult result = service.unlockByAdmin(sender.getName());
            if (result.unlocked()) {
                logger.info("관리자(" + sender.getName() + ") /trade resolve unlock 실행: " + result.message());
                Msg.successPlain(sender, result.message());
            } else {
                logger.warning("관리자(" + sender.getName() + ") /trade resolve unlock 실패: " + result.message());
                Msg.errorPlain(sender, result.message());
            }
            return;
        }

        UUID tradeId;
        try {
            tradeId = UUID.fromString(target);
        } catch (IllegalArgumentException e) {
            Msg.error(sender, "올바른 거래 UUID 가 아닙니다: <arg>", Placeholder.unparsed("arg", target));
            return;
        }

        if (args.length >= 3 && args[2].equalsIgnoreCase("dismiss")) {
            String result = service.dismissSealedJournal(tradeId, sender.getName());
            logger.info("관리자(" + sender.getName() + ") /trade resolve " + tradeId + " dismiss: " + result);
            Msg.infoPlain(sender, result);
            return;
        }

        String result = service.reconcileSingleByAdmin(tradeId);
        logger.info("관리자(" + sender.getName() + ") /trade resolve " + tradeId + ": " + result);
        Msg.infoPlain(sender, result);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        boolean admin = sender.hasPermission("trade.admin");
        Player self = source.getExecutor() instanceof Player p ? p : null;

        if (args.length == 0 || args.length == 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            if (self != null && self.hasPermission("trade.use")) {
                addIfMatch(out, "accept", prefix);
                addIfMatch(out, "deny", prefix);
                addIfMatch(out, "cancel", prefix);
                addIfMatch(out, "claim", prefix);
                for (Player online : server.getOnlinePlayers()) {
                    if (!online.getUniqueId().equals(self.getUniqueId()) && self.canSee(online)) {
                        addIfMatch(out, online.getName(), prefix);
                    }
                }
            }
            if (admin) {
                addIfMatch(out, "status", prefix);
                addIfMatch(out, "reload", prefix);
                addIfMatch(out, "resolve", prefix);
            }
            return out;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            if ((sub.equals("accept") || sub.equals("수락") || sub.equals("deny") || sub.equals("거절")) && self != null) {
                for (Player online : server.getOnlinePlayers()) {
                    if (!online.getUniqueId().equals(self.getUniqueId()) && self.canSee(online)) {
                        addIfMatch(out, online.getName(), prefix);
                    }
                }
            } else if (sub.equals("resolve") && admin) {
                addIfMatch(out, "unlock", prefix);
                for (Journal.Entry e : service.openJournals()) {
                    addIfMatch(out, e.tradeId().toString(), prefix);
                }
            }
            return out;
        }

        if (args.length == 3 && sub.equals("resolve") && admin && !args[1].equalsIgnoreCase("unlock")) {
            List<String> out = new ArrayList<>();
            addIfMatch(out, "dismiss", args[2].toLowerCase(Locale.ROOT));
            return out;
        }
        return List.of();
    }

    private boolean requireAdmin(CommandSender sender) {
        if (!sender.hasPermission("trade.admin")) {
            Msg.error(sender, "관리자 권한(trade.admin)이 필요합니다.");
            return false;
        }
        return true;
    }

    private Player findOnlineTarget(Player viewer, String name) {
        Player exact = server.getPlayerExact(name);
        if (exact != null && exact.isOnline() && viewer.canSee(exact)) {
            return exact;
        }
        for (Player p : server.getOnlinePlayers()) {
            if (p.getName().equalsIgnoreCase(name) && viewer.canSee(p)) {
                return p;
            }
        }
        return null;
    }

    private static void addIfMatch(List<String> list, String candidate, String prefix) {
        if (candidate.toLowerCase(Locale.ROOT).startsWith(prefix)) {
            list.add(candidate);
        }
    }
}
