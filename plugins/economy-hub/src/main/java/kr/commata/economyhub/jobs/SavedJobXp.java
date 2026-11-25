package kr.commata.economyhub.jobs;

import java.util.Locale;
import java.util.function.IntToDoubleFunction;

/**
 * 탈퇴할 때 저장해 두는 직업 레벨·XP (ECONOMY.md 5.1). VJobs 2.2.0 은 탈퇴하면 레벨만 보관하고 XP 를 0 으로 만들어서,
 * EconomyHub 가 따로 기억했다가 같은 레벨로 재가입하면 모자란 만큼 돌려줍니다. Bukkit 에 의존하지 않아 단위 테스트합니다.
 *
 * <p>{@code restored} 는 "VJobs 메모리에는 돌려줬지만 VJobs DB 에 저장됐는지는 아직 모름" 상태.
 * VJobs DB 에서 같은 레벨에 이 XP 이상이 보일 때만 기록을 지웁니다. 그 전에 서버가 꺼지면 재접속 때 모자란 만큼만 다시 줍니다.
 *
 * @param restored false = 탈퇴 후 복구 대기, true = 복구했고 VJobs 저장 확인 대기
 */
public record SavedJobXp(int level, double xp, boolean restored) {

    /** 저장 형식의 소수 4자리 반올림과 double 오차를 흡수. 0.001 XP 미만 차이는 같은 값으로 봄. */
    static final double EPSILON = 1e-3;

    public SavedJobXp(int level, double xp) {
        this(level, xp, false);
    }

    public SavedJobXp asRestored() {
        return new SavedJobXp(level, xp, true);
    }

    public String encode() {
        return level + ":" + String.format(Locale.ROOT, "%.4f", xp) + (restored ? ":r" : "");
    }

    /** 형식이 틀리거나 값이 이상하면 null. */
    public static SavedJobXp decode(String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.split(":", -1);
        if (parts.length < 2 || parts.length > 3 || (parts.length == 3 && !parts[2].equals("r"))) {
            return null;
        }
        try {
            int level = Integer.parseInt(parts[0]);
            double xp = Double.parseDouble(parts[1]);
            if (level < 0 || !Double.isFinite(xp) || xp < 0) {
                return null;
            }
            return new SavedJobXp(level, xp, parts.length == 3);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * (currentLevel, currentXp) 에서 이 기록의 레벨·XP 까지 가는 데 필요한 XP. 이미 그 이상이면 0.
     * 레벨이 더 낮으면(강제 종료로 VJobs DB 가 예전 상태로 돌아간 경우) 그 사이 레벨업에 필요한 XP 까지 더함.
     * VJobs 는 {@code xp >= xpForLevel(level + 1)} 이면 빼고 레벨을 올리므로(JobManager.applyLevelUps, JAR 확인)
     * 한 번에 giveXp 하면 정확히 이 기록의 레벨·XP 가 됩니다. xpForLevel 이 이상하면 NaN.
     *
     * <p>재가입·접속 순간의 값으로만 부릅니다. 그 뒤에 새로 얻은 XP 를 빼고 계산하면 복구량에 흡수되기 때문.
     */
    public double missingXp(int currentLevel, double currentXp, IntToDoubleFunction xpForLevel) {
        if (coveredBy(currentLevel, currentXp)) {
            return 0;
        }
        double total = 0;
        double have = currentXp;
        for (int lv = currentLevel; lv < level; lv++) {
            double need = xpForLevel.applyAsDouble(lv + 1); // lv → lv+1
            if (!Double.isFinite(need) || need <= 0) {
                return Double.NaN;
            }
            total += need - have;
            have = 0;
        }
        return total + xp - have;
    }

    /**
     * 탈퇴할 때 남길 기록. 기존 기록이 지금보다 앞서 있으면(더 높은 레벨, 또는 같은 레벨에 더 많은 XP) 그대로 둠:
     * 복구가 돌기 전(VJobs DB 에서 예전 값을 읽은 상태)에 다시 탈퇴해도 저장해 둔 진행도를 잃지 않게.
     * 남기는 기록은 항상 "재가입 대기"(restored=false).
     *
     * @return 남길 기록, 지울 거면 null
     */
    public static SavedJobXp onLeave(SavedJobXp existing, int level, double xp) {
        if (existing != null && !existing.coveredBy(level, xp)) {
            return new SavedJobXp(existing.level, existing.xp, false);
        }
        return xp > EPSILON ? new SavedJobXp(level, xp, false) : null;
    }

    /** VJobs DB 에 저장된 값이 이 기록 이상인지 (같은 레벨에 XP 이상, 또는 더 높은 레벨). */
    public boolean coveredBy(int storedLevel, double storedXp) {
        return storedLevel > level || (storedLevel == level && storedXp >= xp - EPSILON);
    }
}
