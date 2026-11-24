package kr.commata.travel.logic;

/** 1초마다 한 번씩 {@link #tick()} 을 부르는 귀환 카운트다운. */
public final class Countdown {

    private int secondsLeft;

    public Countdown(int seconds) {
        this.secondsLeft = Math.max(0, seconds);
    }

    public int secondsLeft() {
        return secondsLeft;
    }

    /** 1초 줄이고, 0 이 되면 true (이동할 차례). */
    public boolean tick() {
        if (secondsLeft > 0) {
            secondsLeft--;
        }
        return secondsLeft == 0;
    }
}
