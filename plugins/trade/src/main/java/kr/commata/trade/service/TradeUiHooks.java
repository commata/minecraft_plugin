package kr.commata.trade.service;

import java.util.UUID;
import kr.commata.trade.core.TradeSession;
import org.bukkit.entity.Player;

/**
 * {@link TradeService} 와 GUI·대화창 계층 사이의 느슨한 연결 인터페이스.
 * Commit 4 에서 실제 {@code TradeMenu}·{@code PlayerSelectMenu}·{@code MoneyDialog} 구현을 연결합니다.
 */
public interface TradeUiHooks {

    TradeUiHooks NOOP = new TradeUiHooks() {
    };

    /** 거래 창 열기 (커서 검사 후 호출). */
    default void openTradeMenu(Player player, TradeSession session) {
    }

    /** 플레이어가 현재 보고 있는 이 세션의 거래 창 고유 ID. 보고 있지 않으면 {@code null}. */
    default UUID activeTradeMenuId(Player player, TradeSession session) {
        return null;
    }

    /** 양쪽 중 거래 창을 보고 있는 플레이어의 화면을 제자리 갱신합니다. */
    default void refreshSessionMenus(TradeSession session) {
    }

    /** 이 플레이어가 거래 창을 보고 있으면 다음 틱에 닫습니다. */
    default void closeTradeMenuNextTick(Player player) {
    }

    /** 금액 입력 대화창(Paper Dialog)을 엽니다. */
    default void openMoneyDialog(Player player, TradeSession session, UUID dialogToken, long currentBalance) {
    }

    /** 대화창을 강제로 닫습니다(만료·거래 취소 시). */
    default void closeDialog(Player player) {
    }

    /** 접속자 선택 창을 엽니다. */
    default void openPlayerSelectMenu(Player player) {
    }
}
