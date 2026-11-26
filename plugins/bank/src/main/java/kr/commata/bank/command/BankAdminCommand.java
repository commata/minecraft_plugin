package kr.commata.bank.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import kr.commata.bank.BankService;
import kr.commata.bank.Msg;
import kr.commata.bank.core.Account;
import kr.commata.bank.core.Amounts;
import kr.commata.bank.core.BankStore;
import kr.commata.bank.core.BootReport;
import kr.commata.bank.core.BootStatus;
import kr.commata.bank.core.Failure;
import kr.commata.bank.core.Outcome;
import kr.commata.bank.core.Sources;
import kr.commata.bank.core.TxRecord;
import kr.commata.bank.core.TxType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /bank give·take·set 닉네임 금액, /bank log 닉네임 [쪽], /bank refund 거래번호, /bank reload.
 * 권한 bank.admin (기본 OP). 관리자 변경은 source=Bank, context=admin=&lt;실행자&gt; 로 기록합니다.
 */
public final class BankAdminCommand implements BasicCommand {

    private static final List<String> SUBS = List.of("give", "take", "set", "log", "refund", "reload");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private final BankService bank;
    private final Runnable reload;

    public BankAdminCommand(BankService bank, Runnable reload) {
        this.bank = bank;
        this.reload = reload;
    }

    @Override
    public String permission() {
        return "bank.admin";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (args.length == 0) {
            status(sender);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            reload.run();
            Msg.success(sender, "config.yml 을 다시 불러왔습니다."
                    + (bank.locked() ? " (잠금 모드는 bank.db 복원 후 서버를 재시작해야 풀립니다)" : ""));
            return;
        }
        if (bank.locked()) {
            Msg.error(sender, "잠금 모드: " + bank.lockReason());
            return;
        }
        switch (sub) {
            case "give", "take", "set" -> change(sender, sub, args);
            case "log" -> log(sender, args);
            case "refund" -> refund(sender, args);
            default -> usage(sender);
        }
    }

    private void usage(CommandSender sender) {
        Msg.info(sender, "관리자 명령");
        Msg.plain(sender, "/bank give|take|set <닉네임> <금액>");
        Msg.plain(sender, "/bank log <닉네임> [쪽]");
        Msg.plain(sender, "/bank refund <거래번호>  (비정상 종료된 기동의 EconomyShopGUI·Market 출금만, 한 번만)");
        Msg.plain(sender, "/bank reload");
    }

    private void status(CommandSender sender) {
        if (bank.locked()) {
            Msg.error(sender, "잠금 모드: " + bank.lockReason());
        } else {
            BankStore store = bank.store();
            Msg.info(sender, "정상 (기동 ID " + store.bootId() + ")");
            BootReport boot = bank.boot();
            if (boot != null && boot.previousUnclean()) {
                Msg.error(sender, "직전 기동 " + boot.previousBootId() + " 은 비정상 종료 (" + boot.describe()
                        + "). 장애 추정 시각 " + time(boot.crashEstimateMs())
                        + (BootReport.NO_CLEAN_SHUTDOWN.equals(boot.reason()) ? " (실제 종료는 그 뒤 30초 안)" : ""));
            }
        }
        usage(sender);
    }

    // ------------------------------------------------------------------ give / take / set

    private void change(CommandSender sender, String sub, String[] args) {
        if (args.length != 3) {
            Msg.error(sender, "사용법: /bank " + sub + " <닉네임> <금액>");
            return;
        }
        long amount = Amounts.parse(args[2]);
        if (amount < 0 || (amount == 0 && !sub.equals("set"))) {
            Msg.error(sender, "금액은 1 이상의 정수만 가능합니다 (set 은 0 가능).");
            return;
        }
        Optional<Account> target = bank.find(args[1]);
        if (target.isEmpty()) {
            Msg.error(sender, args[1] + "님은 서버에 접속한 적이 없습니다.");
            return;
        }
        Account a = target.get();
        String name = a.name() == null ? args[1] : a.name();
        String context = "admin=" + sender.getName();
        BankStore store = bank.store();
        Outcome o = switch (sub) {
            case "give" -> store.deposit(a.uuid(), a.name(), amount, TxType.ADMIN_GIVE, Sources.BANK, context);
            case "take" -> store.withdraw(a.uuid(), amount, TxType.ADMIN_TAKE, Sources.BANK, context);
            default -> store.set(a.uuid(), amount, Sources.BANK, context);
        };
        if (!o.ok()) {
            Msg.error(sender, o.message() + (o.failure() == Failure.INSUFFICIENT_FUNDS
                    ? " 현재 잔액: " + Amounts.format(o.balance()) : ""));
            return;
        }
        bank.logger().info("/bank " + sub + " " + name + " " + amount + " by " + sender.getName()
                + " → 잔액 " + o.balance() + " (거래 #" + o.txId() + ")");
        Msg.success(sender, name + "님 잔액: " + Amounts.format(o.balance()) + " (거래 #" + o.txId() + ")");
    }

    // ------------------------------------------------------------------ log

    private void log(CommandSender sender, String[] args) {
        if (args.length < 2 || args.length > 3) {
            Msg.error(sender, "사용법: /bank log <닉네임> [쪽]");
            return;
        }
        Optional<Account> target = bank.find(args[1]);
        if (target.isEmpty()) {
            Msg.error(sender, args[1] + "님은 서버에 접속한 적이 없습니다.");
            return;
        }
        Account a = target.get();
        int page = 1;
        if (args.length == 3) {
            long p = Amounts.parse(args[2]);
            if (p < 1) {
                Msg.error(sender, "쪽 번호는 1 이상의 숫자입니다.");
                return;
            }
            page = (int) Math.min(p, Integer.MAX_VALUE / BaltopCommand.PAGE_SIZE);
        }
        try {
            BankStore store = bank.store();
            long total = store.historyCount(a.uuid());
            long pages = Math.max(1, (total + BaltopCommand.PAGE_SIZE - 1) / BaltopCommand.PAGE_SIZE);
            List<TxRecord> rows = store.history(a.uuid(), (page - 1) * BaltopCommand.PAGE_SIZE,
                    BaltopCommand.PAGE_SIZE);
            Msg.info(sender, (a.name() == null ? args[1] : a.name()) + "님 거래 기록 (" + page + "/" + pages
                    + "쪽, 총 " + total + "건, 최신순, 잔액 " + Amounts.format(store.balance(a.uuid()).orElse(0)) + ")");
            if (rows.isEmpty()) {
                Msg.plain(sender, "기록 없음");
            }
            for (TxRecord r : rows) {
                Msg.line(sender, row(r, a.uuid()));
            }
        } catch (SQLException e) {
            Msg.error(sender, "기록을 불러오지 못했습니다: " + e.getMessage());
        }
    }

    private Component row(TxRecord r, UUID who) {
        boolean out = who.equals(r.from());
        Long after = out ? r.fromBalanceAfter() : r.toBalanceAfter();
        String counterpart = "";
        if (r.type() == TxType.PAY) {
            UUID other = out ? r.to() : r.from();
            counterpart = (out ? " → " : " ← ") + bank.nameOf(other, other.toString().substring(0, 8));
        }
        if (r.type() == TxType.REFUND) {
            counterpart = " (#" + r.ref() + " 환불)";
        }
        Component line = Component.text("#" + r.id() + " ", NamedTextColor.WHITE)
                .append(Component.text(time(r.timeMs()) + " ", NamedTextColor.GRAY))
                .append(Component.text(r.type().label() + " ", NamedTextColor.YELLOW))
                .append(Component.text((out ? "-" : "+") + Amounts.format(r.amount()),
                        out ? NamedTextColor.RED : NamedTextColor.GREEN))
                .append(Component.text(counterpart, NamedTextColor.WHITE))
                .append(Component.text(after == null ? "" : " = " + Amounts.format(after), NamedTextColor.GRAY))
                .append(Component.text(" [" + r.source() + "]", NamedTextColor.AQUA));
        if (BootStatus.UNCLEAN.equals(r.bootStatus())) {
            line = line.append(Component.text(" (비정상 종료된 기동)", NamedTextColor.RED));
        }
        String hover = "기동 " + r.bootId() + " (" + r.bootStatus() + ")"
                + (r.context() == null ? "" : "\n" + r.context());
        return line.hoverEvent(HoverEvent.showText(Component.text(hover)));
    }

    // ------------------------------------------------------------------ refund

    private void refund(CommandSender sender, String[] args) {
        if (args.length != 2) {
            Msg.error(sender, "사용법: /bank refund <거래번호>");
            return;
        }
        long txId = Amounts.parse(args[1].startsWith("#") ? args[1].substring(1) : args[1]);
        if (txId < 1) {
            Msg.error(sender, "거래번호는 숫자입니다. /bank log <닉네임> 으로 확인하세요.");
            return;
        }
        BankStore store = bank.store();
        Outcome o = store.refund(txId, Sources.BANK, "admin=" + sender.getName());
        if (!o.ok()) {
            Msg.error(sender, "환불 거부: " + o.message());
            return;
        }
        try {
            TxRecord orig = store.transaction(txId).orElseThrow();
            String name = bank.nameOf(orig.from(), orig.from().toString());
            OptionalLong crash = store.crashEstimate(orig.bootId());
            bank.logger().info("/bank refund #" + txId + " by " + sender.getName() + " → " + name + " +"
                    + orig.amount() + " (환불 거래 #" + o.txId() + ")");
            Msg.success(sender, "거래 #" + txId + " 환불 완료: " + name + "님에게 " + Amounts.format(orig.amount())
                    + " 입금 (환불 거래 #" + o.txId() + ", 잔액 " + Amounts.format(o.balance()) + ")");
            Msg.plain(sender, "구매 시각 " + time(orig.timeMs()) + " / 장애 추정 시각 "
                    + (crash.isPresent() ? time(crash.getAsLong()) : "?") + ". 결정과 근거를 노션에 기록해 주세요.");
            Player online = bank.server().getPlayer(orig.from());
            if (online != null) {
                Msg.success(online, "서버 장애로 유실된 구매(거래 #" + txId + ")를 환불했습니다: +"
                        + Amounts.format(orig.amount()));
            }
        } catch (SQLException | RuntimeException e) {
            Msg.success(sender, "거래 #" + txId + " 환불 완료 (환불 거래 #" + o.txId() + ")");
        }
    }

    private String time(long ms) {
        return TIME.format(Instant.ofEpochMilli(ms).atZone(bank.settings().zone()));
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            return Names.startingWith(SUBS, args.length == 0 ? "" : args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && List.of("give", "take", "set", "log").contains(sub)) {
            return Names.online(bank.server(), source.getSender(), args[1]);
        }
        return List.of();
    }
}
