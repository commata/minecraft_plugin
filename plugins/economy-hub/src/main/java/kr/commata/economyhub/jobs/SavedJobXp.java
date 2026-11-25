package kr.commata.economyhub.jobs;

import java.util.Locale;

/**
 * 탈퇴할 때 저장해 두는 "레벨:XP" (ECONOMY.md 5.1). VJobs 2.2.0 은 탈퇴하면 레벨만 보관하고 XP 를 0 으로 만들어서,
 * EconomyHub 가 따로 기억했다가 같은 레벨로 재가입하면 모자란 만큼 돌려줍니다. Bukkit 에 의존하지 않아 단위 테스트합니다.
 */
public record SavedJobXp(int level, double xp) {

    public String encode() {
        return level + ":" + String.format(Locale.ROOT, "%.4f", xp);
    }

    /** 형식이 틀리거나 값이 이상하면 null. */
    public static SavedJobXp decode(String raw) {
        if (raw == null) {
            return null;
        }
        int sep = raw.indexOf(':');
        if (sep <= 0) {
            return null;
        }
        try {
            int level = Integer.parseInt(raw.substring(0, sep));
            double xp = Double.parseDouble(raw.substring(sep + 1));
            if (level < 0 || !Double.isFinite(xp) || xp < 0) {
                return null;
            }
            return new SavedJobXp(level, xp);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 재가입 뒤 돌려줄 XP. 레벨이 다르면(관리자가 바꿨거나 탈퇴 벌칙이 생긴 경우) 0, 이미 그 이상이면 0.
     */
    public double toRestore(int currentLevel, double currentXp) {
        if (currentLevel != level || currentXp >= xp) {
            return 0;
        }
        return xp - currentXp;
    }
}
