package kr.commata.travel.model;

import java.time.Duration;
import java.util.UUID;

/**
 * {@code requester} 가 {@code target} 에게 가겠다고 보낸 이동 요청. 시각은 epoch 밀리초.
 * 이름은 보낸 시점의 닉네임 (메시지 표시용).
 */
public record TeleportRequest(
        UUID requester,
        String requesterName,
        UUID target,
        String targetName,
        long createdAt,
        long expiresAt) {

    public boolean involves(UUID player) {
        return requester.equals(player) || target.equals(player);
    }

    public boolean isExpired(long nowMillis) {
        return nowMillis >= expiresAt;
    }

    public Duration remaining(long nowMillis) {
        return nowMillis >= expiresAt ? Duration.ZERO : Duration.ofMillis(expiresAt - nowMillis);
    }

    /** 남은 비율 1.0 → 0.0 (보스바 표시용). */
    public float progress(long nowMillis) {
        long total = expiresAt - createdAt;
        if (total <= 0) {
            return 0f;
        }
        return (float) Math.clamp((double) (expiresAt - nowMillis) / total, 0.0, 1.0);
    }
}
