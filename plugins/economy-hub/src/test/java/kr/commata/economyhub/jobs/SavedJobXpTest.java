package kr.commata.economyhub.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void restoredFlagRoundTrips() {
        SavedJobXp restored = new SavedJobXp(3, 123.5).asRestored();
        assertTrue(restored.restored());
        assertEquals("3:123.5000:r", restored.encode());
        assertEquals(restored, SavedJobXp.decode(restored.encode()));
        assertFalse(SavedJobXp.decode("3:123.5").restored());
    }

    @Test
    void givesOnlyWhatIsMissingSoRepeatedChecksDoNotDuplicate() {
        SavedJobXp saved = new SavedJobXp(3, 123.5).asRestored();
        double current = 0.0; // 복구 직후 VJobs 저장 전에 꺼져서 DB 에는 0
        current += saved.toRestore(3, current);
        assertEquals(123.5, current, 1e-9);
        assertEquals(0.0, saved.toRestore(3, current)); // 다음 확인에서는 주지 않음
        // 4자리 반올림 오차는 무시
        SavedJobXp rounded = SavedJobXp.decode(new SavedJobXp(3, 10.123456).encode());
        assertEquals(0.0, rounded.toRestore(3, 10.123456));
    }

    @Test
    void coveredOnlyWhenVJobsStoredAtLeastTheSavedXp() {
        SavedJobXp saved = new SavedJobXp(3, 123.5).asRestored();
        assertFalse(saved.coveredBy(3, 0.0));
        assertFalse(saved.coveredBy(3, 100.0));
        assertFalse(saved.coveredBy(2, 999.0));
        assertTrue(saved.coveredBy(3, 123.5));
        assertTrue(saved.coveredBy(3, 130.0));
        assertTrue(saved.coveredBy(4, 0.0));
    }

    @Test
    void leavingBeforeRestoreKeepsTheLargerSavedXp() {
        // 재접속 직후 VJobs DB 에서 XP 0 을 읽은 상태로 복구 전에 다시 탈퇴
        SavedJobXp unconfirmed = new SavedJobXp(3, 123.5).asRestored();
        assertEquals(new SavedJobXp(3, 123.5), SavedJobXp.onLeave(unconfirmed, 3, 0.0));
        assertEquals(new SavedJobXp(3, 123.5), SavedJobXp.onLeave(unconfirmed, 3, 50.0));
        // 더 쌓았으면 지금 값, 레벨이 올랐으면 지금 값
        assertEquals(new SavedJobXp(3, 140.0), SavedJobXp.onLeave(unconfirmed, 3, 140.0));
        assertEquals(new SavedJobXp(4, 10.0), SavedJobXp.onLeave(unconfirmed, 4, 10.0));
        assertNull(SavedJobXp.onLeave(unconfirmed, 4, 0.0));
        // 기록 없음
        assertEquals(new SavedJobXp(3, 51.25), SavedJobXp.onLeave(null, 3, 51.25));
        assertNull(SavedJobXp.onLeave(null, 3, 0.0));
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
        assertNull(SavedJobXp.decode("3:5:x"));
        assertNull(SavedJobXp.decode("3:5:r:r"));
    }
}
