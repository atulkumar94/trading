package com.rotation.report;

import com.rotation.model.DailyBars;
import com.rotation.model.MarketSnapshotRow;
import com.rotation.model.SymbolDailyCandles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Exports a daily market snapshot for each symbol across all dates. */
public final class MarketSnapshotExporter {

    public void export(DailyBars bars, Path outputDir, String prefix) {
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to create output directory: " + outputDir, e);
        }

        List<MarketSnapshotRow> rows = buildRows(bars);
        writeDailyMarketSnapshot(rows, bars.dmaPeriods(),
            outputDir.resolve(prefix + "_daily_market_snapshot.csv"));
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
                boolean missingPrice = Double.isNaN(open) || Double.isNaN(high)
                        || Double.isNaN(low) || Double.isNaN(close);
                if (missingPrice && (!bars.hasSourceBarAt(d, i) || bars.validBarAt(d, i))) {
                    continue;
                }

                double prevClose = Double.NaN;
                if (d > 0) {
                    prevClose = bars.closeAt(d - 1, i);
                }
                if (Double.isNaN(prevClose) || prevClose == 0.0) {
                    prevClose = close;
                }
                double returnPct = Double.isNaN(close) || Double.isNaN(prevClose) || prevClose == 0.0
                    ? Double.NaN : ((close / prevClose) - 1.0) * 100.0;

                double[] dmas = new double[bars.dmaPeriods().size()];
                for (int p = 0; p < dmas.length; p++) {
                    dmas[p] = bars.dmaAt(bars.dmaPeriods().get(p), d, i);
                }

                rows.add(new MarketSnapshotRow(date, symbol, prevClose, open, high, low, close, returnPct,
                    bars.volumeAt(d, i), bars.rawCloseAt(d, i),
                    bars.adjustmentFactorAt(d, i), bars.validBarAt(d, i), dmas));
            }
        }

        return rows;
    }

    private void writeDailyMarketSnapshot(List<MarketSnapshotRow> rows, List<Integer> dmaPeriods, Path path) {
        try (Writer w = Files.newBufferedWriter(path)) {
            StringBuilder header = new StringBuilder("date,symbol,prev_close,open,high,low,close,"
                    + "return_vs_prev_close,volume,raw_close,adjustment_factor,valid_bar");
            for (int period : dmaPeriods) {
                header.append(",dma_").append(period);
            }
            w.write(header.append('\n').toString());
            for (MarketSnapshotRow row : rows) {
                List<String> cells = new ArrayList<>(List.of(
                        row.date.toString(),
                        row.symbol,
                        price(row.prevClose),
                        price(row.open),
                        price(row.high),
                        price(row.low),
                        price(row.close),
                        num(row.returnVsPrevClose),
                        optionalNumber(row.volume),
                        optionalNumber(row.rawClose),
                        optionalNumber(row.adjustmentFactor),
                        Boolean.toString(row.validBar)));
                for (double dma : row.dmas) {
                    cells.add(price(dma));
                }
                w.write(String.join(",", cells));
                w.write("\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write " + path, e);
        }
    }

    private static String num(double value) {
        return Double.isFinite(value) ? String.format(Locale.US, "%.2f", value) : "";
    }

    /** Full-precision price so the snapshot losslessly stores the DailyBars data. */
    private static String price(double value) {
        return Double.isNaN(value) ? "" : Double.toString(value);
    }

    private static String optionalNumber(double value) {
        return Double.isFinite(value) ? Double.toString(value) : "";
    }
}
