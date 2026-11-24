package kr.commata.pets;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import kr.commata.pets.command.CallPetCommand;
import kr.commata.pets.track.ChunkLoader;
import kr.commata.pets.track.PetTracker;
import org.bukkit.plugin.java.JavaPlugin;

/** /callpet: 마인크래프트 길들이기 = 등록. 펫의 마지막 위치는 plugins/Pets/pets.yml 에 저장됩니다. */
public final class PetsPlugin extends JavaPlugin {

    /** 바뀐 내용이 있으면 이 주기(틱)마다 pets.yml 저장. 1분. */
    private static final long SAVE_PERIOD_TICKS = 20L * 60;

    private PetTracker tracker;

    @Override
    public void onEnable() {
        tracker = new PetTracker(this);
        tracker.load();
        tracker.scanLoaded();
        getServer().getPluginManager().registerEvents(tracker, this);
        getServer().getScheduler().runTaskTimer(this, tracker::saveIfDirty, SAVE_PERIOD_TICKS, SAVE_PERIOD_TICKS);

        ChunkLoader loader = new ChunkLoader(this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register("callpet", "내가 길들인 펫을 모두 내 옆으로 불러오기",
                        new CallPetCommand(getServer(), tracker, loader)));
        getLogger().info("Pets " + getPluginMeta().getVersion() + " 활성화 (추적 중인 펫 " + tracker.index().size() + "마리)");
    }

    @Override
    public void onDisable() {
        if (tracker != null) {
            tracker.scanLoaded();
            tracker.save();
        }
    }
}
