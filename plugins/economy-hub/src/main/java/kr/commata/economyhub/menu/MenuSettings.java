package kr.commata.economyhub.menu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/** config.yml 의 menus 부분 (ECONOMY.md 7.2, 7.4). 슬롯 배치는 확정 레이아웃이라 코드에, 명령·문구·직업은 설정에 둡니다. */
public record MenuSettings(Map<String, Button> buttons, List<String> payGuide, List<String> guide, List<Job> jobs) {

    /** /economy 버튼이 실행할 명령(앞의 / 없이)과 그 명령을 제공하는 플러그인 이름. 플러그인이 꺼져 있으면 [준비 중] */
    public record Button(String command, String plugin) {
    }

    /** 보상 요약 한 줄: VJobs config 의 jobs.<id>.actions.<action>.<key>.money 를 읽어 "<label> 400원" */
    public record Reward(String action, String key, String label) {
    }

    public record Job(String id, String name, int slot, Material icon, Material joinedIcon, boolean glowWhenJoined,
                      List<Reward> rewards) {
    }

    /** /직업 에서 직업을 놓을 수 있는 칸 (9×5 의 위 4줄) */
    static final int JOB_SLOT_LIMIT = 36;

    public static MenuSettings read(FileConfiguration config, Logger log) {
        Map<String, Button> buttons = new LinkedHashMap<>();
        ConfigurationSection buttonSection = section(config, "menus.economy.buttons");
        if (buttonSection != null) {
            for (String key : buttonSection.getKeys(false)) {
                String command = buttonSection.getString(key + ".command", "").trim();
                if (command.startsWith("/")) {
                    command = command.substring(1);
                }
                if (command.isEmpty()) {
                    log.warning("menus.economy.buttons." + key + ".command 가 비어 있음, [준비 중]으로 표시");
                }
                buttons.put(key, new Button(command, buttonSection.getString(key + ".plugin", "")));
            }
        }

        List<Job> jobs = new ArrayList<>();
        ConfigurationSection jobSection = section(config, "menus.jobs");
        if (jobSection != null) {
            for (String id : jobSection.getKeys(false)) {
                ConfigurationSection job = jobSection.getConfigurationSection(id);
                if (job == null) {
                    continue;
                }
                int slot = job.getInt("slot", -1);
                if (slot < 0 || slot >= JOB_SLOT_LIMIT) {
                    log.warning("menus.jobs." + id + ".slot 은 0–35 여야 함 (지금 " + slot + "), 이 직업은 표시하지 않음");
                    continue;
                }
                if (jobs.stream().anyMatch(other -> other.slot() == slot)) {
                    log.warning("menus.jobs." + id + ".slot " + slot + " 이 다른 직업과 겹침, 이 직업은 표시하지 않음");
                    continue;
                }
                List<Reward> rewards = new ArrayList<>();
                for (Map<?, ?> raw : job.getMapList("rewards")) {
                    Object action = raw.get("action");
                    Object key = raw.get("key");
                    Object label = raw.get("label");
                    if (action != null && key != null && label != null) {
                        rewards.add(new Reward(action.toString(), key.toString(), label.toString()));
                    }
                }
                jobs.add(new Job(id, job.getString("name", id), slot,
                        material(job.getString("icon"), Material.IRON_PICKAXE, "menus.jobs." + id + ".icon", log),
                        material(job.getString("joined-icon"), Material.DIAMOND_PICKAXE, "menus.jobs." + id + ".joined-icon", log),
                        job.getBoolean("glow-when-joined", false), List.copyOf(rewards)));
            }
        }
        return new MenuSettings(Map.copyOf(buttons), List.copyOf(config.getStringList("menus.economy.pay-guide")),
                List.copyOf(config.getStringList("menus.economy.guide")), List.copyOf(jobs));
    }

    /**
     * 서버의 config.yml 에 있으면 그 섹션, 없으면 JAR 기본값의 섹션. 1b 때 만든 config.yml 에는 menus 가 없다.
     * Bukkit getConfigurationSection 은 파일에 없고 기본값에만 있으면 빈 섹션을 새로 만들어 돌려주고(getKeys 가 비어 있음)
     * isConfigurationSection 도 기본값을 보고 true 라서 둘 다 쓰지 않는다.
     */
    static ConfigurationSection section(FileConfiguration config, String path) {
        Object own = config.get(path, null);
        if (own instanceof ConfigurationSection section) {
            return section;
        }
        Configuration defaults = config.getDefaults();
        if (defaults != null && defaults.get(path, null) instanceof ConfigurationSection section) {
            return section;
        }
        return null;
    }

    private static Material material(String name, Material fallback, String path, Logger log) {
        if (name == null) {
            return fallback;
        }
        Material material = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
        if (material == null || !material.isItem()) {
            log.warning(path + ": 없는 아이템 " + name + ", " + fallback + " 로 표시");
            return fallback;
        }
        return material;
    }

    public Button button(String key) {
        return buttons.get(key);
    }
}
