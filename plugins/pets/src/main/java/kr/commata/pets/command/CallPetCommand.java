package kr.commata.pets.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import kr.commata.pets.logic.PetIndex;
import kr.commata.pets.logic.SpotSearch;
import kr.commata.pets.track.ChunkLoader;
import kr.commata.pets.track.PetTracker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * /callpet: 내가 길들인 살아 있는 펫을 모두 내 옆으로 순간이동합니다.
 * <ol>
 *   <li>로딩된 월드에서 바로 찾을 수 있는 펫을 모음</li>
 *   <li>나머지는 추적 목록에 기록된 청크를 불러와서(같은 청크는 한 번만) 엔티티를 찾음</li>
 *   <li>실제로 있고 주인이 나인지 다시 확인한 뒤 한꺼번에 이동</li>
 * </ol>
 * 엔티티를 새로 만들지 않고 기존 엔티티를 옮기므로 체력·장비·이름·앉은 상태가 그대로 유지됩니다.
 */
public final class CallPetCommand implements BasicCommand {

    private static final String PREFIX = "[펫] ";
    private static final List<SpotSearch.Offset> CANDIDATES = SpotSearch.candidates(3, 2);

    private final Server server;
    private final PetTracker tracker;
    private final ChunkLoader loader;

    public CallPetCommand(Server server, PetTracker tracker, ChunkLoader loader) {
        this.server = server;
        this.tracker = tracker;
        this.loader = loader;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendRichMessage("<red>플레이어만 사용할 수 있습니다.");
            return;
        }
        UUID me = player.getUniqueId();
        Map<UUID, Tameable> found = new LinkedHashMap<>();
        for (World world : server.getWorlds()) {
            for (Tameable pet : world.getEntitiesByClass(Tameable.class)) {
                if (PetTracker.isPet(pet)) {
                    tracker.track(pet);
                    if (isMine(pet, me)) {
                        found.put(pet.getUniqueId(), pet);
                    }
                }
            }
        }

        List<CompletableFuture<Void>> loads = new ArrayList<>();
        tracker.index().chunksOf(me, found.keySet()).forEach((chunk, petIds) -> {
            World world = server.getWorld(chunk.world());
            if (world == null) {
                return; // 월드가 없어졌거나 아직 안 불러옴 → 기록은 남겨 둠
            }
            loads.add(loader.load(world, chunk).thenAccept(entitiesLoaded ->
                    collect(chunk, petIds, entitiesLoaded, me, found)));
        });

        if (loads.isEmpty()) {
            finish(player, found);
            return;
        }
        // 청크 로딩 결과는 메인 스레드에서 완료되므로 여기서 바로 옮겨도 됨
        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new))
                .whenComplete((v, error) -> finish(player, found));
    }

    /** 불러온 청크에서 기록된 펫을 찾습니다. 엔티티가 다 로딩됐는데 없으면 추적 목록에서 뺍니다. */
    private void collect(PetIndex.ChunkKey chunk, List<UUID> petIds, boolean entitiesLoaded, UUID me,
                         Map<UUID, Tameable> found) {
        for (UUID id : petIds) {
            Entity e = server.getEntity(id);
            if (e instanceof Tameable pet && PetTracker.isPet(pet)) {
                tracker.track(pet);
                if (isMine(pet, me)) {
                    found.put(id, pet);
                }
            } else if (e == null && entitiesLoaded) {
                tracker.forget(id);
            } else if (e != null) {
                tracker.track(e); // 더 이상 펫이 아님 → track 이 목록에서 뺌
            }
        }
    }

    private static boolean isMine(Tameable pet, UUID me) {
        return me.equals(pet.getOwnerUniqueId()) && !pet.isDead() && pet.isValid();
    }

    private void finish(Player player, Map<UUID, Tameable> found) {
        if (!player.isOnline()) {
            return;
        }
        List<Tameable> pets = new ArrayList<>();
        for (Tameable pet : found.values()) {
            // 누가 타고 있는 말·라마는 옮기지 않음 (내가 타고 있으면 이미 옆에 있음, 남이 타고 있으면 끌고 오면 안 됨)
            if (pet.isValid() && !pet.isDead() && pet.getPassengers().isEmpty()) {
                pets.add(pet);
            }
        }
        if (pets.isEmpty()) {
            player.sendMessage(Component.text(PREFIX + "불러올 반려동물이 없습니다.", NamedTextColor.YELLOW));
            return;
        }
        List<Location> spots = safeSpots(player.getLocation());
        Set<UUID> moved = new HashSet<>();
        for (int i = 0; i < pets.size(); i++) {
            Tameable pet = pets.get(i);
            if (pet.isInsideVehicle()) {
                pet.leaveVehicle(); // 보트·수레에 타 있으면 순간이동이 실패함
            }
            Location to = spots.get(i % spots.size()).clone();
            to.setDirection(player.getLocation().toVector().subtract(to.toVector())); // 플레이어를 바라보게
            pet.setFallDistance(0);
            if (pet.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
                moved.add(pet.getUniqueId());
                tracker.track(pet);
            }
        }
        player.sendMessage(Component.text(PREFIX + "반려동물 " + moved.size() + "마리를 불러왔습니다.",
                NamedTextColor.GREEN));
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
