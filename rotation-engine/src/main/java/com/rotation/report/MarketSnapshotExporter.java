package com.rotation.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.rotation.model.DailyBars;
import com.rotation.model.MarketSnapshotRow;

/** Exports a daily market snapshot for each symbol across all dates. */
public final class MarketSnapshotExporter {

    public void export(DailyBars bars, Path outputDir, String prefix) {
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to create output directory: " + outputDir, e);
        }

        List<MarketSnapshotRow> rows = buildRows(bars);
        writeDailyMarketSnapshot(rows, outputDir.resolve(prefix + "_daily_market_snapshot.csv"));
    }

    private List<MarketSnapshotRow> buildRows(DailyBars bars) {
        List<MarketSnapshotRow> rows = new ArrayList<>();
        List<String> symbols = bars.symbols();
        List<LocalDate> dates = bars.dates();

        for (int i = 0; i < symbols.size(); i++) {
            String symbol = symbols.get(i);
            for (int d = 0; d < dates.size(); d++) {
                LocalDate date = dates.get(d);
                double open = bars.openAt(d, i);
                double high = bars.highAt(d, i);
                double low = bars.lowAt(d, i);
                double close = bars.closeAt(d, i);
                if (Double.isNaN(open) || Double.isNaN(high) || Double.isNaN(low) || Double.isNaN(close)) {
                    continue;
                }

                double prevClose = Double.NaN;
                if (d > 0) {
                    prevClose = bars.closeAt(d - 1, i);
                }
                if (Double.isNaN(prevClose) || prevClose == 0.0) {
                    prevClose = close;
                }
                double returnPct = ((close / prevClose) - 1.0) * 100.0;

                double volume = bars.volumeAt(d, i);
                rows.add(new MarketSnapshotRow(date, symbol, prevClose, open, high, low, close, returnPct, volume));
            }
        }

        return rows;
    }

    private void writeDailyMarketSnapshot(List<MarketSnapshotRow> rows, Path path) {
        try (Writer w = Files.newBufferedWriter(path)) {
            w.write("date,symbol,prev_close,open,high,low,close,return_vs_prev_close,volume\n");
            for (MarketSnapshotRow row : rows) {
                w.write(String.join(",",
                        row.date.toString(),
                        row.symbol,
                        price(row.prevClose),
                        price(row.open),
                        price(row.high),
                        price(row.low),
                        price(row.close),
                        num(row.returnVsPrevClose),
                        vol(row.volume)));
                w.write("\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write " + path, e);
        }
    }

    private static String num(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    /** Full-precision price so the snapshot losslessly stores the DailyBars data. */
    private static String price(double value) {
        return Double.isNaN(value) ? "" : Double.toString(value);
    }

    /** Volume as a plain integer when whole (the usual case), blank when unknown. */
    private static String vol(double value) {
        if (Double.isNaN(value)) {
            return "";
        }
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }
}
