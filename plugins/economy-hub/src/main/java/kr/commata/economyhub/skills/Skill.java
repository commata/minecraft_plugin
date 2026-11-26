package kr.commata.economyhub.skills;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;

/**
 * 직업 레벨 편의 스킬 (ECONOMY.md 15장, 1차 = Lv.10·20). 보상 단가·전투 능력치는 바꾸지 않고 반복 작업만 줄입니다.
 * 스킬마다 플레이어가 켜고 끌 수 있고(PDC), 해금 레벨은 config.yml skills.unlock-levels 로 바꿀 수 있습니다.
 */
public enum Skill {

    MINER_CAVE_VISION("miner_cave_vision", "miner", 10, true, Material.ENDER_EYE,
            "동굴 시야", "Y 50 이하의 지붕 덮인 곳에서 야간 투시"),
    MINER_MAGNET("miner_magnet", "miner", 20, true, Material.RAW_IRON,
            "광물 자석", "직접 캔 광석·돌 드롭이 인벤토리로 바로"),

    LUMBERJACK_REPLANT("lumberjack_replant", "lumberjack", 10, true, Material.OAK_SAPLING,
            "묘목 자동 재식재", "흙 위 원목 밑동을 베면 인벤토리 묘목 1개로 바로 심기"),
    LUMBERJACK_LEAVES("lumberjack_leaves", "lumberjack", 20, true, Material.OAK_LEAVES,
            "나뭇잎 급속 소멸", "원목을 벤 뒤 떠 있게 된 자연 나뭇잎을 1–2초 안에 정리"),
    LUMBERJACK_MAGNET("lumberjack_magnet", "lumberjack", 20, true, Material.APPLE,
            "벌목 자석", "원목·묘목·사과가 인벤토리로 바로"),

    FARMER_TRAMPLE("farmer_trample", "farmer", 10, true, Material.FARMLAND,
            "경작지 보호", "밭을 밟아도 흙으로 돌아가지 않음"),
    FARMER_REPLANT("farmer_replant", "farmer", 10, true, Material.WHEAT_SEEDS,
            "자동 재파종", "다 자란 작물을 수확하면 인벤토리 씨앗 1개로 다시 심기"),
    FARMER_MAGNET("farmer_magnet", "farmer", 20, true, Material.WHEAT,
            "수확 자석", "수확물이 인벤토리로 바로"),
    FARMER_HOE_HARVEST("farmer_hoe_harvest", "farmer", 20, true, Material.IRON_HOE,
            "괭이 우클릭 수확", "괭이로 다 자란 작물을 우클릭하면 수확"),

    HUNTER_MAGNET("hunter_magnet", "hunter", 10, true, Material.EXPERIENCE_BOTTLE,
            "전리품·경험치 자석", "직접 잡은 자연 적대 몹의 드롭·경험치를 바로 흡수"),
    HUNTER_DETECT("hunter_detect", "hunter", 20, true, Material.SPYGLASS,
            "사냥감 탐지", "검·활을 들고 웅크린 채 허공 좌클릭: 주변 적대 몹 발광"),

    FISHERMAN_ALERT("fisherman_alert", "fisherman", 10, true, Material.BELL,
            "입질 알림", "입질 순간 액션바·효과음"),
    FISHERMAN_MAGNET("fisherman_magnet", "fisherman", 10, true, Material.COD,
            "낚시 자석", "낚은 아이템이 인벤토리로 바로"),
    FISHERMAN_TRASH_FILTER("fisherman_trash_filter", "fisherman", 20, false, Material.LEATHER_BOOTS,
            "쓰레기 자동 필터", "설정된 쓰레기(가죽 장화·그릇 등)를 바로 버림 (기본 꺼짐)");

    private final String id;
    private final String job;
    private final int defaultUnlock;
    private final boolean defaultOn;
    private final Material icon;
    private final String displayName;
    private final String description;

    Skill(String id, String job, int defaultUnlock, boolean defaultOn, Material icon, String displayName, String description) {
        this.id = id;
        this.job = job;
        this.defaultUnlock = defaultUnlock;
        this.defaultOn = defaultOn;
        this.icon = icon;
        this.displayName = displayName;
        this.description = description;
    }

    public String id() {
        return id;
    }

    /** VJobs 직업 id */
    public String job() {
        return job;
    }

    public int defaultUnlock() {
        return defaultUnlock;
    }

    /** 플레이어가 아직 켜고 끈 적이 없을 때 */
    public boolean defaultOn() {
        return defaultOn;
    }

    public Material icon() {
        return icon;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public static List<Skill> ofJob(String job) {
        List<Skill> result = new ArrayList<>();
        for (Skill skill : values()) {
            if (skill.job.equals(job)) {
                result.add(skill);
            }
        }
        return result;
    }
}
