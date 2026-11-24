package kr.commata.travel.logic;

/**
 * 귀환이 취소된 이유. 취소 자체에는 쿨타임이 없고, 전투로 취소된 경우에만
 * 별도의 전투 제한(combat tag)이 걸려 있습니다.
 */
public enum CancelReason {
    BLOCK_PLACE("블록을 설치하여"),
    BLOCK_BREAK("블록을 캐서"),
    TOOL("도구를 사용하여"),
    SWING("무기나 도구를 휘둘러"),
    ATTACK("공격하여"),
    EAT("음식을 먹어서"),
    DRINK("물약을 마셔서"),
    RANGED("원거리 무기를 사용하여"),
    ITEM("아이템을 사용하여"),
    MOVED_AWAY("다른 곳으로 이동하여"),
    DAMAGED("피해를 받아"),
    /** 로그아웃·사망: 메시지 없이 조용히 취소 */
    SILENT("");

    private final String phrase;

    CancelReason(String phrase) {
        this.phrase = phrase;
    }

    /** "블록을 설치하여" 처럼 "귀환이 취소되었습니다" 앞에 붙는 말. */
    public String phrase() {
        return phrase;
    }
}
