package kr.commata.pets;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import kr.commata.pets.command.CallPetCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** /callpet: 마인크래프트 길들이기 = 등록. 별도 저장 데이터 없음. */
public final class PetsPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register("callpet", "내가 길들인 펫을 모두 내 옆으로 불러오기",
                        new CallPetCommand(getServer())));
        getLogger().info("Pets " + getPluginMeta().getVersion() + " 활성화");
    }
}
