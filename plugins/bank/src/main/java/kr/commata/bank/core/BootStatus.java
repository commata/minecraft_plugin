package kr.commata.bank.core;

/** boots.status 값. */
public final class BootStatus {
    public static final String RUNNING = "running";
    public static final String CLEAN = "clean";
    /** Bank 는 정상 종료했지만 서버의 플레이어 저장 완료는 아직 확인 전 (다음 기동에서 clean/unclean 으로 바뀜) */
    public static final String CLOSED = "closed";
    public static final String UNCLEAN = "unclean";

    private BootStatus() {
    }
}
