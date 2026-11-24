package kr.commata.travel.cast;

import java.util.Optional;
import java.util.UUID;
import kr.commata.travel.Chat;
import kr.commata.travel.logic.CancelReason;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * 시전이 끝나면 어디로 보낼지, 끝난 뒤 무엇을 할지. /home 과 /tpa 가 각각 구현합니다.
 * 시전 시간, 취소 규칙(블록 설치·전투 등), 플레이어 안전 검사, 순간이동은 {@link CastManager} 가 공통으로 처리합니다.
 */
public interface CastJob {

    Chat chat();

    /** "귀환" / "이동". "귀환이 취소되었습니다", "귀환할 수 없습니다" 에 쓰입니다. */
    String noun();

    int seconds();

    /** 액션바 문구. 예: "귀환까지 3초" */
    String actionBar(int secondsLeft);

    /**
     * 카운트다운 끝 (이동하는 사람의 안전 검사는 이미 통과). 목적지를 돌려줍니다.
     * 갈 수 없으면 {@link Destination#fail} 로 이유를 돌려줍니다.
     */
    Destination destination(Player player);

    /** 순간이동 성공. 쿨타임 설정, 메시지 등. */
    void onSuccess(Player player);

    /** 취소되거나 실패했을 때 (상대에게 알리기 등). 이동하는 사람에게 보내는 메시지는 CastManager 가 보냅니다. */
    default void onAborted(Player player, String why) {
    }

    /** 이 작업이 이 플레이어(로그아웃 등)와 관련 있는지. /tpa 는 상대가 나가면 취소됩니다. */
    default boolean involves(UUID player) {
        return false;
    }

    /**
     * @param blockedMessage 청크를 불러온 뒤 발·머리·바닥 블록이 위험할 때 보낼 메시지
     */
    record Destination(Location location, Optional<String> error, String blockedMessage) {
        public static Destination to(Location location, String blockedMessage) {
            return new Destination(location, Optional.empty(), blockedMessage);
        }

        public static Destination fail(String error) {
            return new Destination(null, Optional.of(error), null);
        }
    }

    /** 취소 이유 문구 + 명사. 예: "블록을 설치하여 귀환이 취소되었습니다." */
    static String cancelled(CancelReason reason, String noun) {
        return reason.phrase() + " " + noun + "이 취소되었습니다.";
    }
}
