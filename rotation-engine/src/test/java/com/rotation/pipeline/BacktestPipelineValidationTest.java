package com.rotation.pipeline;

import com.rotation.config.RotationConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static java.nio.file.Files.exists;

class BacktestPipelineValidationTest {

    @Test
    void warnModeContinuesButFailModeStopsBeforeSnapshotExport() throws IOException {
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        Path data = root.resolve("stocks/daily");
        Path temp = Files.createTempDirectory("pipeline-validation");
        Path warnConfig = config(temp, data, "warn", "warn-output");
        RotationConfig warn = RotationConfig.load(warnConfig, temp);

        assertDoesNotThrow(() -> new BacktestPipeline().run(warn, true));
        assertTrue(exists(warn.resolveOutputDir().resolve("rotation_daily_market_snapshot.csv")));

        Path failConfig = config(temp, data, "fail", "fail-output");
        RotationConfig fail = RotationConfig.load(failConfig, temp);
        assertThrows(IllegalArgumentException.class, () -> new BacktestPipeline().run(fail, true));
        assertTrue(!exists(fail.resolveOutputDir().resolve("rotation_daily_market_snapshot.csv")));
    }

    private static Path config(Path root, Path data, String mode, String output) throws IOException {
        Path file = root.resolve(output + ".properties");
        Files.writeString(file, "data.path=" + data + "\n"
                + "start.date=2024-01-01\n"
                + "end.date=2026-09-30\n"
                + "momentum.lookback.days=90\n"
                + "momentum.top.n=5\n"
                + "momentum.capital.per.stock=20000\n"
                + "momentum.rebalance.mode=monthly\n"
                + "momentum.min.history.days=5\n"
                + "sector.file=" + root.getParent().resolve("symbols.csv") + "\n"
                + "momentum.max.per.sector=1\n"
                + "output.dir=" + root.resolve(output) + "\n"
                + "portal.enabled=false\n"
                + "verbose=false\n"
                + "data.validation.mode=" + mode + "\n");
        return file;
    }
}