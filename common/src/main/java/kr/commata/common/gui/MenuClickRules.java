package kr.commata.common.gui;

/**
 * Bukkit 없이 검증 가능한 메뉴 클릭 필터링 순수 규칙 (TRADE.md 7장, P2-6).
 *
 * <p>Paper 26.3-258 에서 꾸러미 내용 꺼내기·넣기는 {@code ClickType.RIGHT}/{@code LEFT} 와 함께
 * 별도의 {@code InventoryAction}(6종)으로 전달됩니다.
 */
public final class MenuClickRules {

    private MenuClickRules() {
    }

    /** {@code InventoryAction#name()} 이 Paper 26.3-258 꾸러미 내용 꺼내기·넣기 액션인지 판정합니다. */
    public static boolean isBundleActionName(String actionName) {
        if (actionName == null) {
            return false;
        }
        return switch (actionName) {
            case "PICKUP_FROM_BUNDLE",
                 "PICKUP_ALL_INTO_BUNDLE",
                 "PICKUP_SOME_INTO_BUNDLE",
                 "PLACE_FROM_BUNDLE",
                 "PLACE_ALL_INTO_BUNDLE",
                 "PLACE_SOME_INTO_BUNDLE" -> true;
            default -> false;
        };
    }
}
