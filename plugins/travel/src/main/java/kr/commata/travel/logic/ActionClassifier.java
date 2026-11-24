package kr.commata.travel.logic;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 손에 든 아이템과 행동으로 귀환을 취소할지 판단합니다. 아이템은 마인크래프트 ID
 * (예: {@code "cooked_beef"}, 네임스페이스 없이) 로 받으므로 Bukkit 없이 테스트할 수 있습니다.
 *
 * <p>음식·활 이벤트({@code PlayerItemConsumeEvent}, {@code EntityShootBowEvent})는 다 먹은 뒤나
 * 쏜 뒤에야 발생하므로, 먹기 시작하거나 활을 당기는 순간(우클릭)을 여기서 잡습니다.
 */
public final class ActionClassifier {

    private static final Set<String> DRINKS = Set.of("potion", "milk_bucket", "honey_bottle", "ominous_bottle");
    private static final Set<String> RANGED = Set.of("bow", "crossbow", "trident");
    private static final Set<String> USABLE = Set.of(
            "ender_pearl", "ender_eye", "snowball", "egg", "blue_egg", "brown_egg",
            "splash_potion", "lingering_potion", "experience_bottle", "firework_rocket",
            "fishing_rod", "wind_charge", "fire_charge", "shield", "goat_horn", "spyglass",
            "brush", "bone_meal", "lead");
    private static final Set<String> BLOCK_TOOLS = Set.of("shears", "flint_and_steel");
    private static final Set<String> WEAPONS = Set.of("mace", "trident");

    private ActionClassifier() {
    }

    /**
     * 우클릭(허공 또는 블록) 했을 때.
     *
     * @param itemId       손에 든 아이템 ID, 빈 손이면 {@code "air"} 또는 빈 문자열
     * @param edible       먹을 수 있는 아이템인지 (서버가 판단)
     * @param clickedBlock 블록을 우클릭했는지
     */
    public static Optional<CancelReason> rightClick(String itemId, boolean edible, boolean clickedBlock) {
        String id = normalize(itemId);
        if (id.isEmpty() || id.equals("air")) {
            return Optional.empty();
        }
        if (DRINKS.contains(id)) {
            return Optional.of(CancelReason.DRINK);
        }
        if (edible) {
            return Optional.of(CancelReason.EAT);
        }
        if (RANGED.contains(id)) {
            return Optional.of(CancelReason.RANGED);
        }
        if (USABLE.contains(id)) {
            return Optional.of(CancelReason.ITEM);
        }
        if (id.endsWith("_bucket") || id.equals("bucket")) {
            return Optional.of(CancelReason.ITEM);
        }
        if (clickedBlock && (isTool(id) || BLOCK_TOOLS.contains(id))) {
            // 괭이로 밭 갈기, 도끼로 껍질 벗기기, 삽으로 길 만들기 등
            return Optional.of(CancelReason.TOOL);
        }
        return Optional.empty();
    }

    /** 팔을 휘둘렀을 때(좌클릭). 빈 손이나 일반 아이템이면 취소하지 않습니다. */
    public static Optional<CancelReason> swing(String itemId) {
        String id = normalize(itemId);
        if (isTool(id) || isWeapon(id)) {
            return Optional.of(CancelReason.SWING);
        }
        return Optional.empty();
    }

    static boolean isTool(String id) {
        return id.endsWith("_pickaxe") || id.endsWith("_axe") || id.endsWith("_shovel") || id.endsWith("_hoe");
    }

    static boolean isWeapon(String id) {
        return id.endsWith("_sword") || id.endsWith("_spear") || WEAPONS.contains(id);
    }

    private static String normalize(String itemId) {
        if (itemId == null) {
            return "";
        }
        String id = itemId.toLowerCase(Locale.ROOT);
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }
}
