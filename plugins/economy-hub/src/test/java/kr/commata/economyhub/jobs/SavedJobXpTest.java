package kr.commata.economyhub.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class SavedJobXpTest {

    @Test
    void roundTrip() {
        SavedJobXp saved = new SavedJobXp(3, 123.5);
        assertEquals(saved, SavedJobXp.decode(saved.encode()));
    }

    @Test
    void restoresMissingXpAtSameLevel() {
        SavedJobXp saved = new SavedJobXp(3, 123.5);
        assertEquals(123.5, saved.toRestore(3, 0.0), 1e-9);
        assertEquals(23.5, saved.toRestore(3, 100.0), 1e-9);
    }

    @Test
    void nothingWhenLevelChangedOrAlreadyHigher() {
        SavedJobXp saved = new SavedJobXp(3, 123.5);
        assertEquals(0.0, saved.toRestore(2, 0.0));
        assertEquals(0.0, saved.toRestore(4, 0.0));
        assertEquals(0.0, saved.toRestore(3, 200.0));
    }

    @Test
    void rejectsBrokenValues() {
        assertNull(SavedJobXp.decode(null));
        assertNull(SavedJobXp.decode(""));
        assertNull(SavedJobXp.decode("3"));
        assertNull(SavedJobXp.decode("a:1"));
        assertNull(SavedJobXp.decode("3:NaN"));
        assertNull(SavedJobXp.decode("-1:5"));
        assertNull(SavedJobXp.decode("3:-5"));
    }
}
