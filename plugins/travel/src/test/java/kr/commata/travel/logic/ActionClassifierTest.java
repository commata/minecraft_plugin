package kr.commata.travel.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ActionClassifierTest {

    @Test
    void foodAndDrinksCancelOnRightClick() {
        assertEquals(Optional.of(CancelReason.EAT), ActionClassifier.rightClick("cooked_beef", true, false));
        assertEquals(Optional.of(CancelReason.DRINK), ActionClassifier.rightClick("minecraft:potion", false, false));
        assertEquals(Optional.of(CancelReason.DRINK), ActionClassifier.rightClick("MILK_BUCKET", false, true));
    }

    @Test
    void bowAndThrowablesCancel() {
        assertEquals(Optional.of(CancelReason.RANGED), ActionClassifier.rightClick("bow", false, false));
        assertEquals(Optional.of(CancelReason.RANGED), ActionClassifier.rightClick("crossbow", false, true));
        assertEquals(Optional.of(CancelReason.ITEM), ActionClassifier.rightClick("ender_pearl", false, false));
        assertEquals(Optional.of(CancelReason.ITEM), ActionClassifier.rightClick("water_bucket", false, true));
    }

    @Test
    void toolsCancelOnlyWhenUsedOnBlock() {
        assertEquals(Optional.of(CancelReason.TOOL), ActionClassifier.rightClick("iron_hoe", false, true));
        assertEquals(Optional.of(CancelReason.TOOL), ActionClassifier.rightClick("diamond_axe", false, true));
        assertEquals(Optional.empty(), ActionClassifier.rightClick("iron_hoe", false, false));
    }

    @Test
    void emptyHandAndPlainItemsDoNotCancel() {
        assertEquals(Optional.empty(), ActionClassifier.rightClick("air", false, true));
        assertEquals(Optional.empty(), ActionClassifier.rightClick("", false, false));
        assertEquals(Optional.empty(), ActionClassifier.rightClick(null, false, false));
        assertEquals(Optional.empty(), ActionClassifier.rightClick("stick", false, true));
    }

    @Test
    void swingingWeaponsOrToolsCancelButEmptyHandDoesNot() {
        assertEquals(Optional.of(CancelReason.SWING), ActionClassifier.swing("netherite_sword"));
        assertEquals(Optional.of(CancelReason.SWING), ActionClassifier.swing("stone_pickaxe"));
        assertEquals(Optional.of(CancelReason.SWING), ActionClassifier.swing("mace"));
        assertEquals(Optional.empty(), ActionClassifier.swing("air"));
        assertEquals(Optional.empty(), ActionClassifier.swing("dirt"));
    }
}
