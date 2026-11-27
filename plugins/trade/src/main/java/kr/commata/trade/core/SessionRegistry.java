package kr.commata.trade.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 플레이어당 세션 하나 규칙과 같은 상대 10초 재요청 금지 관리 (TRADE.md 2·10장).
 */
public final class SessionRegistry {

    public record PairKey(UUID requester, UUID target) {
    }

    private final Map<UUID, TradeSession> byPlayer = new HashMap<>();
    private final Map<UUID, TradeSession> byTradeId = new HashMap<>();
    /** 같은 상대에게 거절·만료 뒤 재요청 금지 만료 시각 (epoch ms) */
    private final Map<PairKey, Long> reRequestUntil = new HashMap<>();

    public Optional<TradeSession> sessionOf(UUID playerId) {
        return Optional.ofNullable(byPlayer.get(playerId));
    }

    public Optional<TradeSession> byId(UUID tradeId) {
        return Optional.ofNullable(byTradeId.get(tradeId));
    }

    public boolean hasSession(UUID playerId) {
        return byPlayer.containsKey(playerId);
    }

    public Collection<TradeSession> allSessions() {
        return List.copyOf(byTradeId.values());
    }

    public int activeCount() {
        return byTradeId.size();
    }

    /**
     * 새 거래 요청 세션을 등록합니다. 둘 중 하나라도 이미 세션이 있으면 실패({@code false})합니다.
     */
    public boolean register(TradeSession session) {
        UUID a = session.sideA().uuid();
        UUID b = session.sideB().uuid();
        if (byPlayer.containsKey(a) || byPlayer.containsKey(b) || byTradeId.containsKey(session.tradeId())) {
            return false;
        }
        byPlayer.put(a, session);
        byPlayer.put(b, session);
        byTradeId.put(session.tradeId(), session);
        return true;
    }

    /** 종료된 세션을 레지스트리에서 제거합니다. */
    public void remove(TradeSession session) {
        if (session == null) {
            return;
        }
        byTradeId.remove(session.tradeId());
        byPlayer.remove(session.sideA().uuid(), session);
        byPlayer.remove(session.sideB().uuid(), session);
    }

    /** 같은 상대에게 재요청 금지 쿨다운을 설정합니다 (거절·만료 시 10초, TRADE.md 2·10장 4). */
    public void setReRequestCooldown(UUID requester, UUID target, long nowMs, long cooldownMs) {
        if (cooldownMs <= 0) {
            return;
        }
        reRequestUntil.put(new PairKey(requester, target), nowMs + cooldownMs);
    }

    /** 남은 재요청 금지 시간(ms). 없으면 0. */
    public long reRequestRemainingMs(UUID requester, UUID target, long nowMs) {
        PairKey key = new PairKey(requester, target);
        Long until = reRequestUntil.get(key);
        if (until == null) {
            return 0L;
        }
        long remain = until - nowMs;
        if (remain <= 0L) {
            reRequestUntil.remove(key);
            return 0L;
        }
        return remain;
    }

    /** 만료된 요청 세션 목록을 찾아 반환합니다(상태 변경·제거는 호출자가 쿨다운·알림과 함께 처리). */
    public List<TradeSession> findExpiredRequests(long nowMs) {
        List<TradeSession> expired = new ArrayList<>();
        for (TradeSession s : byTradeId.values()) {
            if (s.isRequestExpired(nowMs)) {
                expired.add(s);
            }
        }
        return expired;
    }

    /** 만료된 쿨다운 항목 정리 */
    public void pruneCooldowns(long nowMs) {
        reRequestUntil.entrySet().removeIf(e -> e.getValue() <= nowMs);
    }

    /** 모든 활성 세션 스냅샷을 반환하고 레지스트리를 비웁니다(onDisable 정리용). */
    public Set<TradeSession> clearAll() {
        Set<TradeSession> snapshot = new HashSet<>(byTradeId.values());
        byTradeId.clear();
        byPlayer.clear();
        return snapshot;
    }
}
