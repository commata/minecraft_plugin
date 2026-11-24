package kr.commata.pets.track;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import kr.commata.pets.logic.PetIndex;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * 청크와 그 안의 엔티티를 불러옵니다. 같은 청크를 이미 불러오는 중이면 그 작업을 같이 기다립니다
 * (/callpet 을 여러 번 쳐도 같은 청크 로딩이 겹치지 않음).
 * 불러오는 동안 플러그인 티켓으로 청크를 붙잡아 두고, 끝나면 다음 틱에 놓아줍니다.
 */
public final class ChunkLoader {

    /** 엔티티 로딩을 기다리는 최대 시간 (틱). */
    private static final int MAX_WAIT_TICKS = 200;

    private final JavaPlugin plugin;
    private final Map<PetIndex.ChunkKey, CompletableFuture<Boolean>> inFlight = new HashMap<>();

    public ChunkLoader(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 메인 스레드에서 호출합니다. 결과도 메인 스레드에서 완료됩니다.
     *
     * @return 엔티티까지 다 로딩됐으면 true, 시간 초과면 false
     */
    public CompletableFuture<Boolean> load(World world, PetIndex.ChunkKey key) {
        CompletableFuture<Boolean> running = inFlight.get(key);
        if (running != null) {
            return running;
        }
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        inFlight.put(key, result);
        world.getChunkAtAsync(key.x(), key.z()).whenComplete((chunk, error) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (error != null || chunk == null) {
                        finish(key, null, result, false);
                        return;
                    }
                    chunk.addPluginChunkTicket(plugin);
                    waitForEntities(key, chunk, result);
                }));
        return result;
    }

    private void waitForEntities(PetIndex.ChunkKey key, Chunk chunk, CompletableFuture<Boolean> result) {
        if (chunk.isEntitiesLoaded()) {
            finish(key, chunk, result, true);
            return;
        }
        int[] waited = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            boolean loaded = chunk.isEntitiesLoaded();
            if (loaded || ++waited[0] >= MAX_WAIT_TICKS) {
                task[0].cancel();
                finish(key, chunk, result, loaded);
            }
        }, 1L, 1L);
    }

    private void finish(PetIndex.ChunkKey key, Chunk chunk, CompletableFuture<Boolean> result, boolean ok) {
        inFlight.remove(key);
        result.complete(ok); // 기다리던 쪽이 여기서 바로 펫을 옮김
        if (chunk != null) {
            plugin.getServer().getScheduler().runTask(plugin, () -> chunk.removePluginChunkTicket(plugin));
        }
    }
}
