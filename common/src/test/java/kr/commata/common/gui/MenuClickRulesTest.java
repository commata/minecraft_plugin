package kr.commata.common.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class MenuClickRulesTest {

    @Test
    void bundleActionNamesAreBlockedAndRegularActionsAreNot() {
        assertTrue(MenuClickRules.isBundleActionName("PICKUP_FROM_BUNDLE"));
        assertTrue(MenuClickRules.isBundleActionName("PICKUP_ALL_INTO_BUNDLE"));
        assertTrue(MenuClickRules.isBundleActionName("PICKUP_SOME_INTO_BUNDLE"));
        assertTrue(MenuClickRules.isBundleActionName("PLACE_FROM_BUNDLE"));
        assertTrue(MenuClickRules.isBundleActionName("PLACE_ALL_INTO_BUNDLE"));
        assertTrue(MenuClickRules.isBundleActionName("PLACE_SOME_INTO_BUNDLE"));

        assertFalse(MenuClickRules.isBundleActionName(null));
        assertFalse(MenuClickRules.isBundleActionName(""));
        assertFalse(MenuClickRules.isBundleActionName("PICKUP_ALL"));
        assertFalse(MenuClickRules.isBundleActionName("PICKUP_HALF"));
        assertFalse(MenuClickRules.isBundleActionName("PLACE_ALL"));
        assertFalse(MenuClickRules.isBundleActionName("MOVE_TO_OTHER_INVENTORY"));
        assertFalse(MenuClickRules.isBundleActionName("NOTHING"));
    }
}
