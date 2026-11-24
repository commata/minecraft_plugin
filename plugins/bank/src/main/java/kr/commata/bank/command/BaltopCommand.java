package kr.commata.bank.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.sql.SQLException;
import java.util.List;
import kr.commata.bank.BankService;
import kr.commata.bank.Msg;
import kr.commata.bank.core.Account;
import kr.commata.bank.core.Amounts;
import kr.commata.bank.core.Failure;
import org.bukkit.command.CommandSender;

/** /baltop [쪽]: 잔액 순위 10명씩. 순위는 config 의 주기(기본 1분)마다 다시 계산합니다. */
public final class BaltopCommand implements BasicCommand {

    static final int PAGE_SIZE = 10;
    private static final int MAX_ROWS = 1000;

    private final BankService bank;
    private List<Account> cached = List.of();
    private long cachedAt = Long.MIN_VALUE;

    public BaltopCommand(BankService bank) {
        this.bank = bank;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (bank.locked()) {
            Msg.error(sender, Failure.LOCKED.message());
            return;
        }
        List<Account> top;
        try {
            top = ranking();
        } catch (SQLException e) {
            Msg.error(sender, "순위를 불러오지 못했습니다.");
            bank.logger().warning("/baltop 조회 실패: " + e.getMessage());
            return;
        }
        if (top.isEmpty()) {
            Msg.info(sender, "아직 돈을 가진 사람이 없습니다.");
            return;
        }
        int pages = (top.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int page = 1;
        if (args.length > 0) {
            long p = Amounts.parse(args[0]);
            if (p < 1) {
                Msg.error(sender, "사용법: /baltop [쪽]");
                return;
            }
            page = (int) Math.min(p, pages);
        }
        Msg.info(sender, "부자 순위 (" + page + "/" + pages + "쪽)");
        int from = (page - 1) * PAGE_SIZE;
        for (int i = from; i < Math.min(top.size(), from + PAGE_SIZE); i++) {
            Account a = top.get(i);
            Msg.plain(sender, (i + 1) + ". " + (a.name() == null ? "(알 수 없음)" : a.name()) + " - "
                    + Amounts.format(a.balance()));
        }
    }

    private synchronized List<Account> ranking() throws SQLException {
        long now = System.currentTimeMillis();
        long ttl = bank.settings().baltopCacheSeconds() * 1000L;
        if (cachedAt == Long.MIN_VALUE || now - cachedAt >= ttl) {
            cached = bank.store().top(MAX_ROWS);
            cachedAt = now;
        }
        return cached;
    }

    /** /bank reload 후 바로 반영되도록. */
    public synchronized void invalidate() {
        cachedAt = Long.MIN_VALUE;
    }
}
