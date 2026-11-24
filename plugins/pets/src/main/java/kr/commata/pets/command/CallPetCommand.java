package kr.commata.pets.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.commata.pets.logic.SpotSearch;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * /callpet: 지금 로딩된 모든 월드에서 내가 길들인 살아 있는 펫을 찾아 내 옆으로 순간이동합니다.
 * 엔티티를 새로 만들지 않고 기존 엔티티를 옮기므로 체력·장비·이름·앉은 상태가 그대로 유지됩니다.
 * 로딩되지 않은 청크에 있는 펫은 찾지 않습니다 (단순 버전).
 */
public final class CallPetCommand implements BasicCommand {

    private static final Set<EntityType> PET_TYPES = Set.of(
            EntityType.WOLF, EntityType.CAT, EntityType.PARROT,
            EntityType.HORSE, EntityType.DONKEY, EntityType.MULE, EntityType.LLAMA);

    private static final String PREFIX = "[펫] ";
    private static final List<SpotSearch.Offset> CANDIDATES = SpotSearch.candidates(3, 2);

    private final Server server;

    public CallPetCommand(Server server) {
        this.server = server;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendRichMessage("<red>플레이어만 사용할 수 있습니다.");
            return;
        }
        List<Tameable> pets = findPets(player);
        if (pets.isEmpty()) {
            player.sendMessage(Component.text(PREFIX + "불러올 반려동물이 없습니다.", NamedTextColor.YELLOW));
            return;
        }

        List<Location> spots = safeSpots(player.getLocation());
        int called = 0;
        for (int i = 0; i < pets.size(); i++) {
            Location to = spots.get(i % spots.size()).clone();
            // 펫이 플레이어를 바라보게
            to.setDirection(player.getLocation().toVector().subtract(to.toVector()));
            Tameable pet = pets.get(i);
            pet.setFallDistance(0);
            if (pet.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
                called++;
            }
        }
        player.sendMessage(Component.text(PREFIX + "반려동물 " + called + "마리를 불러왔습니다.", NamedTextColor.GREEN));
    }

    private List<Tameable> findPets(Player player) {
        UUID me = player.getUniqueId();
        List<Tameable> out = new ArrayList<>();
        for (World world : server.getWorlds()) {
            for (Tameable pet : world.getEntitiesByClass(Tameable.class)) {
                if (!PET_TYPES.contains(pet.getType()) || !pet.isTamed() || !me.equals(pet.getOwnerUniqueId())) {
                    continue;
                }
                if (pet.isDead() || !pet.isValid()) {
                    continue;
                }
                // 누가 타고 있는 말·라마는 옮기지 않음 (내가 타고 있으면 이미 옆에 있음, 남이 타고 있으면 끌고 오면 안 됨)
                if (!pet.getPassengers().isEmpty()) {
                    continue;
                }
                // 보트·수레 등에 타 있는 펫은 내려서 옮김 (타고 있으면 순간이동이 실패함)
                if (pet.isInsideVehicle()) {
                    pet.leaveVehicle();
                }
                out.add(pet);
            }
        }
        return out;
    }

    /** 플레이어 주변의 안전한 칸 목록. 하나도 없으면 플레이어 위치 하나. */
    private static List<Location> safeSpots(Location origin) {
        World world = origin.getWorld();
        Block base = origin.getBlock();
        List<Location> out = new ArrayList<>();
        for (SpotSearch.Offset o : CANDIDATES) {
            Block feet = base.getRelative(o.dx(), o.dy(), o.dz());
            if (isSafe(feet)) {
                out.add(new Location(world, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5));
            }
        }
        if (out.isEmpty()) {
            out.add(origin.clone());
        }
        return out;
    }

    private static boolean isSafe(Block feet) {
        Block head = feet.getRelative(BlockFace.UP);
        Block ground = feet.getRelative(BlockFace.DOWN);
        boolean hazard = isHazard(feet) || isHazard(head) || isHazard(ground);
        return SpotSearch.isSafe(new SpotSearch.Spot(
                feet.isPassable() && !feet.isLiquid(),
                head.isPassable() && !head.isLiquid(),
                ground.getType().isSolid(),
                hazard));
    }

    private static boolean isHazard(Block block) {
        return SpotSearch.isHazardBlock(block.getType().getKey().getKey());
    }
}
