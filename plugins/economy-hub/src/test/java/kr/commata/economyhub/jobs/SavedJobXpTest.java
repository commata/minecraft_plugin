package kr.commata.economyhub.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.IntToDoubleFunction;
import org.junit.jupiter.api.Test;

class SavedJobXpTest {

    @Test
    void roundTrip() {
        SavedJobXp saved = new SavedJobXp(3, 123.5);
        assertEquals(saved, SavedJobXp.decode(saved.encode()));
    }

    /** VJobs 처럼 레벨 n-1 에서 n 으로 가는 데 xpForLevel(n) = 100·(n-1) XP (테스트용 곡선). */
    private static final IntToDoubleFunction CURVE = n -> 100.0 * (n - 1);

    @Test
    void restoresMissingXpAtSameLevel() {
        SavedJobXp saved = new SavedJobXp(3, 123.5);
        assertEquals(123.5, saved.missingXp(3, 0.0, CURVE), 1e-9);
        assertEquals(23.5, saved.missingXp(3, 100.0, CURVE), 1e-9);
    }

    @Test
    void nothingWhenAlreadyAtOrAboveTheRecord() {
        SavedJobXp saved = new SavedJobXp(3, 123.5);
        assertEquals(0.0, saved.missingXp(4, 0.0, CURVE));
        assertEquals(0.0, saved.missingXp(3, 200.0, CURVE));
    }

    @Test
    void lowerLevelAfterCrashRestoresTheLevelsToo() {
        // 기록 Lv3·XP 34, 강제 종료로 VJobs DB 는 Lv2·XP 150 (Lv2→3 에 200 필요)
        SavedJobXp saved = new SavedJobXp(3, 34.0);
        assertEquals(50.0 + 34.0, saved.missingXp(2, 150.0, CURVE), 1e-9);
        // Lv1·XP 0 → Lv2 100 + Lv3 200 + 34
        assertEquals(334.0, saved.missingXp(1, 0.0, CURVE), 1e-9);
        assertTrue(Double.isNaN(saved.missingXp(1, 0.0, lv -> Double.NaN)));
    }

    @Test
    void xpEarnedAfterTheSnapshotIsKept() {
        // 재가입 순간 Lv3·XP 0 에서 계산한 복구량 123.5 를 준 뒤 새로 40 을 얻으면 163.5
        SavedJobXp saved = new SavedJobXp(3, 123.5);
        double give = saved.missingXp(3, 0.0, CURVE);
        assertEquals(163.5, 0.0 + give + 40.0, 1e-9);
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
        current += saved.missingXp(3, current, CURVE);
        assertEquals(123.5, current, 1e-9);
        assertEquals(0.0, saved.missingXp(3, current, CURVE)); // 다시 접속해 DB 에 반영된 값을 읽으면 주지 않음
        // 4자리 반올림 오차는 무시
        SavedJobXp rounded = SavedJobXp.decode(new SavedJobXp(3, 10.123456).encode());
        assertEquals(0.0, rounded.missingXp(3, 10.123456, CURVE));
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
        // 강제 종료로 VJobs 가 낮은 레벨로 돌아간 상태에서 탈퇴해도 기록 유지
        assertEquals(new SavedJobXp(3, 34.0), SavedJobXp.onLeave(new SavedJobXp(3, 34.0), 2, 150.0));
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
