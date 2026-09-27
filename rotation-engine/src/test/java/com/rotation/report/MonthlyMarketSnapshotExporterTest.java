package com.rotation.report;

import com.rotation.model.DailyBars;
import com.rotation.model.DailyCandle;
import com.rotation.model.SymbolDailyCandles;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MonthlyMarketSnapshotExporterTest {

    @Test
    void exportsOneRowPerSymbolPerMonth() throws IOException {
        DailyBars bars = DailyBars.build(List.of(
                new SymbolDailyCandles("AAA", List.of(
                        new DailyCandle(LocalDate.of(2024, 1, 1), 100.0, 100.0, 95.0, 99.0, 1000),
                        new DailyCandle(LocalDate.of(2024, 1, 2), 99.0, 105.0, 98.0, 104.0, 1000),
                        new DailyCandle(LocalDate.of(2024, 2, 1), 104.0, 110.0, 103.0, 108.0, 1000)
                ))
        ), false);

        Path dir = Files.createTempDirectory("monthly-snapshot");
        new MonthlyMarketSnapshotExporter().export(bars, dir, "snapshot");

        Path monthly = dir.resolve("snapshot_monthly_market_snapshot.csv");
        assertTrue(Files.exists(monthly));

        String content = Files.readString(monthly);
        assertTrue(content.contains("month,symbol,prev_close,open,high,low,close,return_vs_prev_close"));
        assertTrue(content.contains("2024-01,AAA"));
        assertTrue(content.contains("2024-02,AAA"));
    }
}
