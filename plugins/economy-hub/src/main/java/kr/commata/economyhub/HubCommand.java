package kr.commata.economyhub;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /economyhub status [닉] · /economyhub reload (권한 economyhub.admin) */
final class HubCommand implements BasicCommand {

    private final EconomyHubPlugin plugin;

    HubCommand(EconomyHubPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.reload();
            sender.sendMessage(Component.text("EconomyHub 설정을 다시 읽었습니다.", NamedTextColor.GREEN));
            return;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("status")) {
            sender.sendMessage(Component.text("탈퇴 후 쿨타임: " + (plugin.leaveHooked()
                            ? plugin.cooldownGuard().cooldownMs() / 60_000L + "분 (VJobs job-switch-cooldown.default-seconds)"
                            : "꺼짐")
                    + " · 보상 제외: " + (plugin.payoutHooked() ? "켜짐" : "꺼짐")
                    + " · 이번 기동에서 취소한 보상 " + plugin.cancelledPayouts() + "건"
                    + " · 탈퇴 후 XP 유지: " + plugin.xpState(), NamedTextColor.YELLOW));
            if (args.length >= 2) {
                Player target = plugin.getServer().getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(Component.text("접속 중인 플레이어가 아닙니다: " + args[1], NamedTextColor.RED));
                    return;
                }
                if (plugin.xpKeeper() != null) {
                    StringBuilder records = new StringBuilder();
                    plugin.xpKeeper().records(target.getUniqueId()).forEach((job, saved) -> records.append(' ').append(job)
                            .append(" Lv").append(saved.level()).append(" XP ").append(String.format(Locale.ROOT, "%.1f", saved.xp()))
                            .append(saved.restored() ? "(복구함, VJobs 저장 확인 대기)" : "(재가입 대기)"));
                    sender.sendMessage(Component.text(target.getName() + " XP 기록:" + (records.isEmpty() ? " 없음" : records),
                            NamedTextColor.YELLOW));
                }
                if (!plugin.leaveHooked()) {
                    return;
                }
                long remaining = plugin.cooldownGuard().remainingMs(target);
                boolean exempt = target.hasPermission("jobs.cooldown.instant");
                sender.sendMessage(Component.text(target.getName() + ": 남은 쿨타임 "
                        + (remaining <= 0 ? "없음" : (remaining + 59_999) / 60_000 + "분")
                        + ", VJobs 쿨타임 면제 권한 " + (exempt ? "있음" : "없음"), NamedTextColor.YELLOW));
            }
            return;
        }
        sender.sendMessage(Component.text("사용법: /economyhub status [닉] | reload", NamedTextColor.GRAY));
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            return List.of("status", "reload");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("status")) {
            return plugin.getServer().getOnlinePlayers().stream().map(Player::getName).toList();
        }
        return List.of();
    }

    @Override
    public String permission() {
        return "economyhub.admin";
    }
}
