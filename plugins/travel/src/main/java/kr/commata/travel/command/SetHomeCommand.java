package kr.commata.travel.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import kr.commata.travel.Chat;
import kr.commata.travel.TravelPlugin;
import kr.commata.travel.cast.BukkitSafety;
import kr.commata.travel.logic.HomeLocation;
import kr.commata.travel.storage.TravelStore;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** /sethome: 지금 서 있는 곳을 집으로 저장 (1인 1개, 오버월드만, 기존 집은 덮어씀). */
public final class SetHomeCommand implements BasicCommand {

    private final TravelStore store;

    public SetHomeCommand(TravelStore store) {
        this.store = store;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendRichMessage("<red>플레이어만 사용할 수 있습니다.");
            return;
        }
        Location loc = player.getLocation();
        if (!TravelPlugin.isOverworld(loc.getWorld())) {
            Chat.HOME.error(player, "집은 오버월드에서만 정할 수 있습니다.");
            return;
        }
        if (!BukkitSafety.isSafeDestination(loc)) {
            Chat.HOME.error(player, "여기는 집으로 정할 수 없습니다. 단단한 바닥 위, 머리 위가 뚫린 곳에서 다시 시도해 주세요.");
            return;
        }
        boolean replaced = store.home(player).isPresent();
        store.setHome(player, new HomeLocation(
                loc.getWorld().getUID(), loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch()));
        Chat.HOME.success(player, String.format("집을 %s했습니다. (%d, %d, %d)",
                replaced ? "이곳으로 옮겼" : "저장", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
    }
}
