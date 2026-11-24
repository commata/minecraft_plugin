package kr.commata.bank.vault;

import kr.commata.bank.core.Sources;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 구 Vault 의 withdrawPlayer(player, amount) 에는 호출한 플러그인 정보가 없으므로,
 * 호출 스택에서 Bank·Vault 가 아닌 첫 플러그인 클래스를 찾아 그 이름을 씁니다 (ECONOMY.md 2.4).
 */
public final class CallerResolver {

    private static final int MAX_FRAMES = 128;
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    /** 클래스 → 그 클래스를 불러온 플러그인 이름 (플러그인 클래스가 아니면 ""). 클래스마다 한 번만 계산. */
    private static final ClassValue<String> PLUGIN_OF = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> type) {
            try {
                return JavaPlugin.getProvidingPlugin(type).getName();
            } catch (RuntimeException | LinkageError e) {
                return "";
            }
        }
    };

    private CallerResolver() {
    }

    public static String resolve() {
        try {
            return WALKER.walk(frames -> Sources.firstCaller(
                    frames.limit(MAX_FRAMES).map(f -> PLUGIN_OF.get(f.getDeclaringClass()))));
        } catch (RuntimeException e) {
            return Sources.UNKNOWN;
        }
    }
}
