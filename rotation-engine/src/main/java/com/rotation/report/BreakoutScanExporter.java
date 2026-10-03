package com.rotation.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import com.rotation.strategy.breakout.BreakoutScanner.ScanRow;

/** Writes {@code {prefix}_scan.csv}: the latest-session breakout cross-up scan. */
public final class BreakoutScanExporter {

    public Path export(List<ScanRow> rows, Path outputDir, String prefix) {
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to create output directory: " + outputDir, e);
        }
        Path path = outputDir.resolve(prefix + "_scan.csv");
        try (Writer w = Files.newBufferedWriter(path)) {
            w.write("date,symbol,is_entry,trend_ok,adx_ok,volume_ok,liquidity_ok,close,sma_fast,sma_mid,"
                    + "sma_slow,adx,atr,volume,avg_volume,avg_traded_value,rank_score\n");
            for (ScanRow r : rows) {
                w.write(String.join(",",
                        date(r.date), quote(r.symbol), Boolean.toString(r.isEntry), Boolean.toString(r.trendOk),
                        Boolean.toString(r.adxOk), Boolean.toString(r.volumeOk), Boolean.toString(r.liquidityOk),
                        num(r.close), num(r.smaFast), num(r.smaMid), num(r.smaSlow), num(r.adx), num(r.atr),
                        num(r.volume), num(r.avgVolume), num(r.avgTradedValue), num(r.rankScore)));
                w.write("\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write " + path, e);
        }
        return path;
    }

    private static String date(LocalDate date) {
        return date == null ? "" : date.toString();
    }

    private static String num(double value) {
        return Double.isNaN(value) ? "" : String.format(Locale.US, "%.4f", value);
    }

    private static String quote(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
