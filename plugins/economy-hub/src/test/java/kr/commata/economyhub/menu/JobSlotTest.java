package kr.commata.economyhub.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import kr.commata.economyhub.menu.JobSlot.State;
import org.junit.jupiter.api.Test;

class JobSlotTest {

    @Test
    void joinedWinsOverEverything() {
        assertEquals(State.JOINED, JobSlot.state(true, 2, 2, 60_000));
    }

    @Test
    void fullBeforeCooldown() {
        assertEquals(State.FULL, JobSlot.state(false, 2, 2, 60_000));
        assertEquals(State.FULL, JobSlot.state(false, 2, 2, 0));
    }

    @Test
    void cooldownOnlyWithFreeSlot() {
        assertEquals(State.COOLDOWN, JobSlot.state(false, 1, 2, 1));
        assertEquals(State.AVAILABLE, JobSlot.state(false, 1, 2, 0));
        assertEquals(State.AVAILABLE, JobSlot.state(false, 0, 2, 0));
    }

    @Test
    void clockFormat() {
        assertEquals("60분 00초", JobSlot.clock(3_600_000));
        assertEquals("01분 02초", JobSlot.clock(61_500));
        assertEquals("00분 01초", JobSlot.clock(1));
        assertEquals("00분 00초", JobSlot.clock(0));
        assertEquals("00분 00초", JobSlot.clock(-5));
    }

    @Test
    void wonFormat() {
        assertEquals("400원", JobSlot.won(400));
        assertEquals("1,200원", JobSlot.won(1200));
        assertEquals("2.5원", JobSlot.won(2.5));
    }
}
