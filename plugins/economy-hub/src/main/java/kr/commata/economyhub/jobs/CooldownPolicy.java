package kr.commata.economyhub.jobs;

/** 직업 쿨타임 판단 (ECONOMY.md 5.1). 빈 슬롯을 처음 채울 때는 바로, 탈퇴 뒤에만 일정 시간 막음. */
public final class CooldownPolicy {

    private CooldownPolicy() {
    }

    /** 탈퇴 기록이 없거나 쿨타임이 지났으면 VJobs 쿨타임을 면제한다. */
    public static boolean exempt(long lastLeaveMs, long nowMs, long cooldownMs) {
        return lastLeaveMs <= 0 || nowMs - lastLeaveMs >= cooldownMs;
    }

    public static long remainingMs(long lastLeaveMs, long nowMs, long cooldownMs) {
        if (exempt(lastLeaveMs, nowMs, cooldownMs)) {
            return 0;
        }
        return cooldownMs - (nowMs - lastLeaveMs);
    }
}
