package kr.commata.economyhub.skills;

/** 동굴 시야 판정 (ECONOMY.md 15.3, 검토 반영). Bukkit 없이 단위 테스트합니다. */
public final class CaveVisionRule {

    /** 스킬이 주는 야간 투시 길이(틱). 10초 아래로 내려가면 화면이 깜빡이므로 2초마다 15초로 갱신 */
    public static final int EFFECT_TICKS = 15 * 20;

    private CaveVisionRule() {
    }

    /**
     * 눈높이 Y 가 기준 이하이고 하늘빛(시간과 무관한 값)이 0 인 곳 (15.3 확정: {@code Y ≤ 50 && sky == 0}).
     * 하늘빛을 보므로 밤의 지상이나 하늘이 트인 협곡에서는 켜지지 않음.
     */
    public static boolean dark(int eyeY, int maxY, int skyLight) {
        return eyeY <= maxY && skyLight == 0;
    }

    /**
     * 지금 걸린 야간 투시를 스킬이 덮어써도 되는지 (15.5 확정). 남은 시간이 300틱을 넘거나 무한이면 물약 등으로 보고
     * 건드리지 않음. 300틱 이하는 스킬 효과(15초)보다 짧아 덮어써도 손해가 없음.
     *
     * @param durationTicks 남은 시간, 효과가 없으면 0, 무한이면 -1
     */
    public static boolean mayOverwrite(int durationTicks) {
        return durationTicks >= 0 && durationTicks <= EFFECT_TICKS;
    }
}
