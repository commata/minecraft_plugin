package kr.commata.economyhub.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import kr.commata.economyhub.menu.JobSlot.State;
import kr.commata.economyhub.menu.JobSlot.StatusBar;
import kr.commata.economyhub.menu.JobSlot.StatusKind;
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
    void clockColonFormat() {
        assertEquals("60:00", JobSlot.clockColon(3_600_000));
        assertEquals("01:02", JobSlot.clockColon(61_500));
        assertEquals("00:01", JobSlot.clockColon(1));
        assertEquals("00:00", JobSlot.clockColon(0));
        assertEquals("00:00", JobSlot.clockColon(-5));
    }

    @Test
    void wonFormat() {
        assertEquals("400원", JobSlot.won(400));
        assertEquals("1,200원", JobSlot.won(1200));
        assertEquals("2.5원", JobSlot.won(2.5));
    }

    @Test
    void defaultLayoutStatusSlots() {
        List<Integer> jobSlots = List.of(11, 13, 15, 21, 23);
        Set<Integer> occupied = new HashSet<>(jobSlots);
        List<Integer> actual = jobSlots.stream().map(slot -> {
            OptionalInt status = JobSlot.statusSlot(slot, occupied);
            assertTrue(status.isPresent(), "기본 배치 직업 칸 " + slot + " 에 상태 바가 있어야 함");
            occupied.add(status.getAsInt());
            return status.getAsInt();
        }).toList();
        assertEquals(List.of(20, 22, 24, 30, 32), actual);
    }

    @Test
    void statusSlotEmptyWhenOutOfBoundsOrOccupied() {
        // 범위 밖: 30 + 9 = 39 (0–35 밖)
        assertEquals(OptionalInt.empty(), JobSlot.statusSlot(30, Set.of(30)));
        assertEquals(OptionalInt.empty(), JobSlot.statusSlot(27, Set.of(27)));
        // 경계: 26 + 9 = 35 (0–35 안)
        assertEquals(OptionalInt.of(35), JobSlot.statusSlot(26, Set.of(26)));
        // 겹침: 직업 11과 20이 함께 있음 → 11의 상태 바(20) 없음
        assertEquals(OptionalInt.empty(), JobSlot.statusSlot(11, Set.of(11, 20)));
        // 다른 직업의 상태 바와 겹침
        assertEquals(OptionalInt.empty(), JobSlot.statusSlot(11, Set.of(11, 20)));
    }

    @Test
    void statusBarMappingForAllSixStates() {
        assertEquals(new StatusBar("LIGHT_GRAY_STAINED_GLASS_PANE", "<#AAAAAA>불러오는 중"),
                JobSlot.statusBar(StatusKind.LOADING, 0, 2, 0));
        assertEquals(new StatusBar("LIME_STAINED_GLASS_PANE", "<#55FF55>가입 완료 ✔"),
                JobSlot.statusBar(StatusKind.JOINED, 1, 2, 0));
        assertEquals(new StatusBar("RED_STAINED_GLASS_PANE", "<#FF5555>한 번 더 우클릭하면 탈퇴"),
                JobSlot.statusBar(StatusKind.CONFIRM_LEAVE, 1, 2, 0));
        assertEquals(new StatusBar("RED_STAINED_GLASS_PANE", "<#FF5555>가입 불가 (2/2)"),
                JobSlot.statusBar(StatusKind.FULL, 2, 2, 0));
        assertEquals(new StatusBar("ORANGE_STAINED_GLASS_PANE", "<#FFDF6B>쿨타임 59:01"),
                JobSlot.statusBar(StatusKind.COOLDOWN, 1, 2, 3_540_500));
        assertEquals(new StatusBar("GREEN_STAINED_GLASS_PANE", "<#55FF55>▶ 좌클릭하여 가입"),
                JobSlot.statusBar(StatusKind.AVAILABLE, 0, 2, 0));
    }
}
