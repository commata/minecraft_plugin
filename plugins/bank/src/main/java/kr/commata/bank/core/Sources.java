package kr.commata.bank.core;

import java.util.Set;
import java.util.stream.Stream;

/** transactions.source: 잔액 변경을 호출한 플러그인 이름. */
public final class Sources {

    public static final String BANK = "Bank";
    public static final String UNKNOWN = "unknown";
    /** /bank refund 가 환불을 허용하는 유일한 출처 (ECONOMY.md 2.4) */
    public static final String SHOP = "EconomyShopGUI";

    /** 호출 스택에서 건너뛰는 플러그인: Bank 자신, 그리고 중간에서 호출을 넘겨줄 수 있는 VaultUnlocked */
    public static final Set<String> SKIPPED = Set.of(BANK, "Vault");

    private Sources() {
    }

    /**
     * 호출 스택 안쪽(가까운 쪽)부터 나열한 플러그인 이름 중, 건너뛸 플러그인이 아닌 첫 이름.
     * 플러그인 클래스가 아닌 프레임은 null 또는 빈 문자열로 들어옵니다.
     */
    public static String firstCaller(Stream<String> pluginNamesInnermostFirst) {
        return pluginNamesInnermostFirst
                .filter(n -> n != null && !n.isEmpty() && !SKIPPED.contains(n))
                .findFirst()
                .orElse(UNKNOWN);
    }
}
