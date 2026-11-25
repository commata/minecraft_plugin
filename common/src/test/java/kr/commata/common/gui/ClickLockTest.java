package kr.commata.common.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClickLockTest {

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @Test
    void blocksRepeatClicksInsideWindow() {
        ClickLock lock = new ClickLock(250);
        assertTrue(lock.tryAcquire(a, 1_000));
        assertFalse(lock.tryAcquire(a, 1_100));
        assertFalse(lock.tryAcquire(a, 1_249));
        assertTrue(lock.tryAcquire(a, 1_250));
    }

    @Test
    void ignoredClicksDoNotExtendLock() {
        ClickLock lock = new ClickLock(250);
        assertTrue(lock.tryAcquire(a, 0));
        assertFalse(lock.tryAcquire(a, 200));
        assertTrue(lock.tryAcquire(a, 260));
    }

    @Test
    void playersAreIndependent() {
        ClickLock lock = new ClickLock(250);
        assertTrue(lock.tryAcquire(a, 0));
        assertTrue(lock.tryAcquire(b, 10));
    }

    @Test
    void clockGoingBackwardsDoesNotLockForever() {
        ClickLock lock = new ClickLock(250);
        assertTrue(lock.tryAcquire(a, 10_000));
        assertTrue(lock.tryAcquire(a, 5_000));
    }

    @Test
    void forgetResets() {
        ClickLock lock = new ClickLock(250);
        assertTrue(lock.tryAcquire(a, 0));
        lock.forget(a);
        assertTrue(lock.tryAcquire(a, 1));
    }
}
