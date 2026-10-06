package com.rotation.config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class RotationConfigTest {

    @Test
    void loadsNamespacedMomentumAndSharedPipelineSettings() throws IOException {
        RotationConfig config = load("strategy=momentum\n"
                + "momentum.lookback.days=45\n"
                + "momentum.top.n=4\n"
                + "momentum.rebalance.mode=weekly\n"
                + "data.path=bars\n"
                + "output.prefix=check\n");

        assertEquals(45, config.lookbackDays());
        assertEquals(4, config.topN());
        assertEquals("weekly", config.rebalanceMode());
        assertEquals("check", config.outputPrefix());
    }

    @Test
    void parsesDmaPeriodsWithDefaultBlankAndValidation() throws IOException {
        assertEquals(java.util.List.of(10, 20, 50, 100, 200), load("top.n=3\n").dmaPeriods());
        assertEquals(java.util.List.of(5, 30), load("market.dma.periods= 5 , 30\n").dmaPeriods());
        assertTrue(load("market.dma.periods=\n").dmaPeriods().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> load("market.dma.periods=10,abc\n"));
        assertThrows(IllegalArgumentException.class, () -> load("market.dma.periods=10,10\n"));
        assertThrows(IllegalArgumentException.class, () -> load("market.dma.periods=0\n"));
    }

    @Test
    void acceptsLegacyMomentumKeysAndWarnsWithNamespacedReplacement() throws IOException {
        PrintStream originalError = System.err;
        ByteArrayOutputStream warnings = new ByteArrayOutputStream();
        RotationConfig config;
        try {
            System.setErr(new PrintStream(warnings, true, StandardCharsets.UTF_8));
            config = load("lookback.days=60\ntop.n=3\nrebalance.mode=monthly\n");
        } finally {
            System.setErr(originalError);
        }

        assertEquals(60, config.lookbackDays());
        assertEquals(3, config.topN());
        assertTrue(warnings.toString(StandardCharsets.UTF_8)
                .contains("Deprecated config key 'lookback.days'; use 'momentum.lookback.days'."));
    }

    @Test
    void namespacedValueWinsWhenLegacyAliasIsAlsoPresent() throws IOException {
        RotationConfig config = load("lookback.days=30\nmomentum.lookback.days=75\n");

        assertEquals(75, config.lookbackDays());
    }

    @Test
    void rejectsUnknownKeysWithClosestSupportedSuggestion() throws IOException {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> load("momentum.lookbak.days=45\n"));

        assertTrue(error.getMessage().contains("momentum.lookback.days"));

        IllegalArgumentException legacyTypo = assertThrows(IllegalArgumentException.class,
            () -> load("lookback.dyas=45\n"));
        assertTrue(legacyTypo.getMessage().contains("momentum.lookback.days"));
    }

    private static RotationConfig load(String contents) throws IOException {
        Path root = Files.createTempDirectory("rotation-config-test");
        Path file = root.resolve("rotation.properties");
        Files.writeString(file, contents);
        return RotationConfig.load(file, root);
    }
}
