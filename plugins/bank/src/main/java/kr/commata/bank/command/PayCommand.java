package kr.commata.bank.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import kr.commata.bank.BankService;
import kr.commata.bank.Msg;
import kr.commata.bank.core.Account;
import kr.commata.bank.core.Amounts;
import kr.commata.bank.core.Failure;
import kr.commata.bank.core.Outcome;
import kr.commata.bank.core.Sources;
import org.bukkit.entity.Player;

/**
 * /pay 닉네임 금액. 정수만, 최소 금액(config), 잔액 초과·자기 자신·음수·0 거부.
 * 받는 사람은 오프라인이어도 되지만 서버에 한 번은 접속했어야 합니다.
 * 출금·입금·기록이 한 트랜잭션이고, 커밋이 끝난 뒤에만 성공 메시지를 보냅니다.
 */
public final class PayCommand implements BasicCommand {

    private final BankService bank;

    public PayCommand(BankService bank) {
        this.bank = bank;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendRichMessage("<red>플레이어만 사용할 수 있습니다.");
            return;
        }
        if (bank.locked()) {
            Msg.error(player, Failure.LOCKED.message());
            return;
        }
        if (args.length != 2) {
            Msg.error(player, "사용법: /pay <닉네임> <금액>");
            return;
        }
        long amount = Amounts.parse(args[1]);
        if (amount < 0) {
            Msg.error(player, "금액은 숫자만 입력해 주세요 (소수점·쉼표 없이). 예: /pay " + args[0] + " 1000");
            return;
        }
        long min = bank.settings().minPay();
        if (amount < min) {
            Msg.error(player, "최소 " + Amounts.format(min) + "부터 보낼 수 있습니다.");
            return;
        }
        Optional<Account> target = bank.find(args[0]);
        if (target.isEmpty()) {
            Msg.error(player, args[0] + "님은 서버에 접속한 적이 없습니다.");
            return;
        }
        Account to = target.get();
        String toName = to.name() == null ? args[0] : to.name();
        if (to.uuid().equals(player.getUniqueId())) {
            Msg.error(player, Failure.SELF_TRANSFER.message());
            return;
        }
        Outcome o = bank.store().transfer(player.getUniqueId(), to.uuid(), amount, Sources.BANK,
                BankService.contextOf(player));
        if (!o.ok()) {
            if (o.failure() == Failure.STORAGE_ERROR) {
                bank.logger().severe("/pay 저장 실패 (" + player.getName() + " → " + toName + ", " + amount + "원): "
                        + o.detail());
                Msg.error(player, Failure.STORAGE_ERROR.message());
            } else if (o.failure() == Failure.INSUFFICIENT_FUNDS) {
                Msg.error(player, "잔액이 부족합니다. 내 잔액: " + Amounts.format(o.balance()));
            } else {
                Msg.error(player, o.message());
            }
            return;
        }
        Msg.success(player, toName + "님에게 " + Amounts.format(amount) + "을 보냈습니다. 내 잔액: "
                + Amounts.format(o.balance()) + " (거래 #" + o.txId() + ")");
        Player online = bank.server().getPlayer(to.uuid());
        if (online != null) {
            long toBalance = bank.store().balance(to.uuid()).orElse(0);
            Msg.success(online, player.getName() + "님이 " + Amounts.format(amount) + "을 보냈습니다. 내 잔액: "
                    + Amounts.format(toBalance));
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length > 1) {
            return List.of();
        }
        return Names.online(bank.server(), source.getSender(), args.length == 0 ? "" : args[0]).stream()
                .filter(n -> !(source.getSender() instanceof Player p) || !p.getName().equals(n))
                .toList();
    }
}
