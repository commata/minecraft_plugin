package kr.commata.economyhub.skills;

/** 동굴 시야 판정 (ECONOMY.md 15.3, 검토 반영). Bukkit 없이 단위 테스트합니다. */
public final class CaveVisionRule {

    /** 스킬이 주는 야간 투시 길이(틱). 10초 아래로 내려가면 화면이 깜빡이므로 2초마다 15초로 갱신 */
    public static final int EFFECT_TICKS = 15 * 20;

    private CaveVisionRule() {
    }

    /**
     * 하늘빛(시간과 무관한 값)이 7 이하인 덮인 곳이면서, Y 가 기준 이하이거나 블록 빛도 7 이하인 곳.
     * "밝기 ≤ 7"만 보면 밤의 지상에서도 켜지므로 하늘빛으로 지붕 여부를 먼저 봄.
     */
    public static boolean dark(int eyeY, int maxY, int skyLight, int blockLight) {
        return skyLight <= 7 && (eyeY <= maxY || blockLight <= 7);
    }

    /**
     * 지금 걸린 야간 투시를 스킬이 덮어써도 되는지. 물약 등으로 더 길게(또는 무한히) 걸려 있으면 건드리지 않음.
     *
     * @param durationTicks 남은 시간, 효과가 없으면 0, 무한이면 -1
     */
    public static boolean mayOverwrite(int durationTicks, boolean ours) {
        if (durationTicks == 0) {
            return true;
        }
        if (durationTicks < 0) {
            return false;
        }
        return ours && durationTicks <= EFFECT_TICKS;
    }
}
