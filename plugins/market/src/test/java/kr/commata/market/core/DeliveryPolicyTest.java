package kr.commata.market.core;

import kr.commata.market.core.DeliveryPolicy.Action;
import kr.commata.market.core.DeliveryPolicy.Decision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DeliveryPolicy 순수 단위 테스트.
 * 구매품 지급 전/후 상태 전이 표 및 중복 지급 방지 판정을 검증합니다.
 */
class DeliveryPolicyTest {

    @Test
    @DisplayName("지급 전 디스크에 이미 토큰이 존재하면 ALREADY_DELIVERED 판정 (중복 지급 방지 P1)")
    void preDeliveryAlreadyOnDisk() {
        Decision d = DeliveryPolicy.checkPreDelivery(true);
        assertNotNull(d);
        assertEquals(Action.ALREADY_DELIVERED, d.action());
        assertTrue(d.reason().contains("중복 지급 방지"));
    }

    @Test
    @DisplayName("지급 전 디스크에 토큰이 없으면 계속 진행 (null 반환)")
    void preDeliveryNotOnDisk() {
        Decision d = DeliveryPolicy.checkPreDelivery(false);
        assertNull(d);
    }

    @Test
    @DisplayName("디스크 확인 정상 FOUND 시 CONFIRMED 판정")
    void postDeliveryFound() {
        Decision d = DeliveryPolicy.decidePostDelivery(true, false, true, null);
        assertEquals(Action.CONFIRMED, d.action());
    }

    @Test
    @DisplayName("디스크 미검출이고 회수 성공 시 REVERT_AND_RETRY 판정")
    void postDeliveryAbsentRevertOk() {
        Decision d = DeliveryPolicy.decidePostDelivery(false, false, true, "ABSENT");
        assertEquals(Action.REVERT_AND_RETRY, d.action());
        assertEquals("ABSENT", d.reason());
    }

    @Test
    @DisplayName("저장 예외 발생했으나 회수 성공 시 REVERT_AND_RETRY 판정")
    void postDeliverySaveExceptionRevertOk() {
        Decision d = DeliveryPolicy.decidePostDelivery(false, true, true, "IO error");
        assertEquals(Action.REVERT_AND_RETRY, d.action());
    }

    @Test
    @DisplayName("회수 실패 시 인벤토리 오염으로 CORRUPTED 판정 (긴급 격리 P2)")
    void postDeliveryRevertFailedCorrupted() {
        Decision d1 = DeliveryPolicy.decidePostDelivery(false, false, false, "ABSENT");
        assertEquals(Action.CORRUPTED, d1.action());

        Decision d2 = DeliveryPolicy.decidePostDelivery(false, true, false, "IO error");
        assertEquals(Action.CORRUPTED, d2.action());
    }
}
