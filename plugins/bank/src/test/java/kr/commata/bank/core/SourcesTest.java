package kr.commata.bank.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class SourcesTest {

    @Test
    void skipsBankVaultAndNonPluginFrames() {
        assertEquals("EconomyShopGUI",
                Sources.firstCaller(Arrays.stream(new String[] {"Bank", null, "Vault", "EconomyShopGUI", "VJobs"})));
        assertEquals("VJobs", Sources.firstCaller(Stream.of(null, "VJobs")));
    }

    @Test
    void unknownWhenNoOtherPlugin() {
        assertEquals("unknown", Sources.firstCaller(Arrays.stream(new String[] {"Bank", null, null})));
    }
}
