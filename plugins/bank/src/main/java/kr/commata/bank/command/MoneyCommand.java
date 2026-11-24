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
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /money (/bal, /돈): 내 잔액. /money 닉네임: 다른 사람 잔액. */
public final class MoneyCommand implements BasicCommand {

    private final BankService bank;

    public MoneyCommand(BankService bank) {
        this.bank = bank;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (bank.locked()) {
            Msg.error(sender, Failure.LOCKED.message());
            return;
        }
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                Msg.error(sender, "사용법: /money <닉네임>");
                return;
            }
            long balance = bank.store().balance(player.getUniqueId()).orElse(0);
            Msg.info(sender, "내 잔액: " + Amounts.format(balance));
            return;
        }
        Optional<Account> acc = bank.find(args[0]);
        if (acc.isEmpty()) {
            Msg.error(sender, args[0] + "님은 서버에 접속한 적이 없습니다.");
            return;
        }
        Account a = acc.get();
        long balance = bank.store().balance(a.uuid()).orElse(a.balance());
        Msg.info(sender, (a.name() == null ? args[0] : a.name()) + "님의 잔액: " + Amounts.format(balance));
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length > 1) {
            return List.of();
        }
        return Names.online(bank.server(), source.getSender(), args.length == 0 ? "" : args[0]);
    }
}
