package com.rotation.report;

import com.rotation.model.DailyBars;
import com.rotation.model.DailyCandle;
import com.rotation.model.SymbolDailyCandles;
import com.rotation.data.SnapshotDailyBarLoader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DailyMarketSnapshotExporterTest {

    @Test
    void exportsDailySnapshotWithPrevCloseAndReturn() throws IOException {
        DailyBars bars = DailyBars.build(List.of(
                new SymbolDailyCandles("AAA", List.of(
                        new DailyCandle(LocalDate.of(2024, 1, 1), 100.0, 110.0, 95.0, 105.0, 1000),
                        new DailyCandle(LocalDate.of(2024, 1, 2), 105.0, 112.0, 102.0, 110.0, 1100),
                        new DailyCandle(LocalDate.of(2024, 1, 3), 110.0, 118.0, 108.0, 115.0, 1200)
                ))
        ), false);

        Path dir = Files.createTempDirectory("snapshot-export");
        new MarketSnapshotExporter().export(bars, dir, "snapshot");

        Path daily = dir.resolve("snapshot_daily_market_snapshot.csv");
        assertTrue(Files.exists(daily));

        String content = Files.readString(daily);
        assertTrue(content.contains("date,symbol,prev_close,open,high,low,close,return_vs_prev_close"));
        assertTrue(content.contains("2024-01-02,AAA,105.0,105.0,112.0,102.0,110.0,4.76"));
    }

    @Test
    void roundTripsVolumeRawCloseAdjustmentAndValidity() throws IOException {
        DailyCandle source = new DailyCandle(LocalDate.of(2024, 1, 2), 50.0, 55.0,
                45.0, 52.5, 1234.0, 105.0, 0.5, true);
        DailyBars bars = DailyBars.build(List.of(new SymbolDailyCandles("AAA", List.of(source))), false);
        Path dir = Files.createTempDirectory("snapshot-metadata");

        new MarketSnapshotExporter().export(bars, dir, "snapshot");
        DailyBars reloaded = DailyBars.build(SnapshotDailyBarLoader.load(
                dir.resolve("snapshot_daily_market_snapshot.csv")), false);

        assertEquals(1234.0, reloaded.volumeAt(0, 0));
        assertEquals(105.0, reloaded.rawCloseAt(0, 0));
        assertEquals(0.5, reloaded.adjustmentFactorAt(0, 0));
        assertTrue(reloaded.validBarAt(0, 0));
        assertEquals(52.5, reloaded.closeAt(0, 0));
    }

        @Test
        void reloadsLegacySnapshotWithCompatibilityMetadataDefaults() throws IOException {
                Path file = Files.createTempFile("legacy-daily-market", ".csv");
                Files.writeString(file, "date,symbol,prev_close,open,high,low,close,return_vs_prev_close\n"
                                + "2024-01-02,AAA,52.5,50.0,55.0,45.0,52.5,0.0\n");

                DailyBars bars = DailyBars.build(SnapshotDailyBarLoader.load(file), false);

                assertTrue(Double.isNaN(bars.volumeAt(0, 0)));
                assertEquals(52.5, bars.rawCloseAt(0, 0));
                assertEquals(1.0, bars.adjustmentFactorAt(0, 0));
                assertTrue(bars.validBarAt(0, 0));
        }

        @Test
        void blankExtendedMetadataRemainsUnknownAndInvalid() throws IOException {
                DailyCandle invalidSourceBar = new DailyCandle(LocalDate.of(2024, 1, 2),
                        Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                        Double.NaN, Double.NaN, Double.NaN, false);
                DailyBars source = DailyBars.build(List.of(
                        new SymbolDailyCandles("AAA", List.of(invalidSourceBar))), false);
                Path dir = Files.createTempDirectory("invalid-daily-market");
                new MarketSnapshotExporter().export(source, dir, "snapshot");
                DailyBars bars = DailyBars.build(SnapshotDailyBarLoader.load(
                        dir.resolve("snapshot_daily_market_snapshot.csv")), false);

                assertTrue(Double.isNaN(bars.volumeAt(0, 0)));
                assertTrue(Double.isNaN(bars.rawCloseAt(0, 0)));
                assertTrue(Double.isNaN(bars.adjustmentFactorAt(0, 0)));
                assertFalse(bars.validBarAt(0, 0));
        }
}
