package kr.commata.trade.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

final class ClickRulesTest {

    @Test
    void bottomInventoryLeftAndRightAddOffersAndOtherIgnored() {
        assertEquals(new ClickRules.AddOfferAll(0),
                ClickRules.interpret(ClickRules.Area.BOTTOM, 0, ClickRules.Button.LEFT));
        assertEquals(new ClickRules.AddOfferOne(35),
                ClickRules.interpret(ClickRules.Area.BOTTOM, 35, ClickRules.Button.RIGHT));
        assertEquals(ClickRules.Ignore.INSTANCE,
                ClickRules.interpret(ClickRules.Area.BOTTOM, 36, ClickRules.Button.LEFT));
        assertEquals(ClickRules.Ignore.INSTANCE,
                ClickRules.interpret(ClickRules.Area.BOTTOM, 0, ClickRules.Button.OTHER));
    }

    @Test
    void topOfferSlotsRemoveAndPartnerSlotsIgnored() {
        for (int i = 0; i < ClickRules.MY_OFFER_SLOTS.length; i++) {
            int slot = ClickRules.MY_OFFER_SLOTS[i];
            assertEquals(new ClickRules.RemoveOfferAll(i),
                    ClickRules.interpret(ClickRules.Area.TOP, slot, ClickRules.Button.LEFT));
            assertEquals(new ClickRules.RemoveOfferOne(i),
                    ClickRules.interpret(ClickRules.Area.TOP, slot, ClickRules.Button.RIGHT));
            assertEquals(ClickRules.Ignore.INSTANCE,
                    ClickRules.interpret(ClickRules.Area.TOP, slot, ClickRules.Button.OTHER));
        }
        for (int slot : ClickRules.PARTNER_OFFER_SLOTS) {
            assertEquals(ClickRules.Ignore.INSTANCE,
                    ClickRules.interpret(ClickRules.Area.TOP, slot, ClickRules.Button.LEFT));
            assertEquals(ClickRules.Ignore.INSTANCE,
                    ClickRules.interpret(ClickRules.Area.TOP, slot, ClickRules.Button.RIGHT));
        }
    }

    @Test
    void centerButtonsAndOutsideAndStatusSlotsBehavePerLayout746() {
        assertInstanceOf(ClickRules.OpenMoneyDialog.class,
                ClickRules.interpret(ClickRules.Area.TOP, ClickRules.SLOT_MY_MONEY, ClickRules.Button.LEFT));
        assertInstanceOf(ClickRules.ToggleReady.class,
                ClickRules.interpret(ClickRules.Area.TOP, ClickRules.SLOT_READY, ClickRules.Button.LEFT));
        assertInstanceOf(ClickRules.CancelTrade.class,
                ClickRules.interpret(ClickRules.Area.TOP, ClickRules.SLOT_CANCEL, ClickRules.Button.LEFT));

        assertEquals(ClickRules.Ignore.INSTANCE,
                ClickRules.interpret(ClickRules.Area.TOP, ClickRules.SLOT_PARTNER_MONEY, ClickRules.Button.LEFT));
        assertEquals(ClickRules.Ignore.INSTANCE,
                ClickRules.interpret(ClickRules.Area.TOP, ClickRules.SLOT_COUNTDOWN, ClickRules.Button.LEFT));
        assertEquals(ClickRules.Ignore.INSTANCE,
                ClickRules.interpret(ClickRules.Area.TOP, ClickRules.SLOT_MY_INFO, ClickRules.Button.LEFT));
        assertEquals(ClickRules.Ignore.INSTANCE,
                ClickRules.interpret(ClickRules.Area.TOP, ClickRules.SLOT_PARTNER_INFO, ClickRules.Button.LEFT));
        assertEquals(ClickRules.Ignore.INSTANCE,
                ClickRules.interpret(ClickRules.Area.OUTSIDE, -999, ClickRules.Button.LEFT));
    }
}
