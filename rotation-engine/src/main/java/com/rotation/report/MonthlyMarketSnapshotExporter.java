package com.rotation.report;

import com.rotation.model.DailyBars;
import com.rotation.model.MonthlyMarketSnapshotRow;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Exports a month-level market snapshot for each symbol. */
public final class MonthlyMarketSnapshotExporter {

    public void export(DailyBars bars, Path outputDir, String prefix) {
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to create output directory: " + outputDir, e);
        }

        List<MonthlyMarketSnapshotRow> rows = buildRows(bars);
        writeMonthlyMarketSnapshot(rows, outputDir.resolve(prefix + "_monthly_market_snapshot.csv"));
    }

    private List<MonthlyMarketSnapshotRow> buildRows(DailyBars bars) {
        Map<String, Map<YearMonth, List<LocalDate>>> bySymbolAndMonth = new LinkedHashMap<>();
        for (String symbol : bars.symbols()) {
            bySymbolAndMonth.put(symbol, new LinkedHashMap<>());
        }

        for (int d = 0; d < bars.dates().size(); d++) {
            LocalDate date = bars.dates().get(d);
            YearMonth month = YearMonth.from(date);
            for (int s = 0; s < bars.symbols().size(); s++) {
                String symbol = bars.symbols().get(s);
                double close = bars.closeAt(d, s);
                if (Double.isNaN(close)) {
                    continue;
                }
                bySymbolAndMonth.computeIfAbsent(symbol, ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(month, ignored -> new ArrayList<>())
                        .add(date);
            }
        }

        List<MonthlyMarketSnapshotRow> rows = new ArrayList<>();
        for (String symbol : bars.symbols()) {
            Map<YearMonth, List<LocalDate>> monthDates = bySymbolAndMonth.get(symbol);
            List<YearMonth> months = new ArrayList<>(monthDates.keySet());
            months.sort(Comparator.naturalOrder());

            for (YearMonth month : months) {
                List<LocalDate> dates = monthDates.get(month);
                LocalDate firstDate = dates.get(0);
                LocalDate lastDate = dates.get(dates.size() - 1);

                int firstIdx = bars.indexOfDate(firstDate);
                int lastIdx = bars.indexOfDate(lastDate);
                int symbolIdx = bars.indexOfSymbol(symbol);

                double open = bars.openAt(firstIdx, symbolIdx);
                double high = Double.NEGATIVE_INFINITY;
                double low = Double.POSITIVE_INFINITY;
                double closeVal = Double.NaN;
                for (int idx = firstIdx; idx <= lastIdx; idx++) {
                    double value = bars.closeAt(idx, symbolIdx);
                    if (!Double.isNaN(value)) {
                        closeVal = value;
                    }
                    double hi = bars.highAt(idx, symbolIdx);
                    double lo = bars.lowAt(idx, symbolIdx);
                    if (!Double.isNaN(hi) && hi > high) {
                        high = hi;
                    }
                    if (!Double.isNaN(lo) && lo < low) {
                        low = lo;
                    }
                }

                double prevClose = Double.NaN;
                int previousMonthIdx = bars.indexOfDate(firstDate) - 1;
                if (previousMonthIdx >= 0) {
                    prevClose = bars.closeAt(previousMonthIdx, symbolIdx);
                }
                if (Double.isNaN(prevClose) || prevClose == 0.0) {
                    prevClose = open;
                }
                double returnPct = ((closeVal / prevClose) - 1.0) * 100.0;

                rows.add(new MonthlyMarketSnapshotRow(month, symbol, prevClose, open, high, low, closeVal, returnPct));
            }
        }

        return rows;
    }

    private void writeMonthlyMarketSnapshot(List<MonthlyMarketSnapshotRow> rows, Path path) {
        try (Writer w = Files.newBufferedWriter(path)) {
            w.write("month,symbol,prev_close,open,high,low,close,return_vs_prev_close\n");
            for (MonthlyMarketSnapshotRow row : rows) {
                w.write(String.join(",",
                        row.month.toString(),
                        row.symbol,
                        num(row.prevClose),
                        num(row.open),
                        num(row.high),
                        num(row.low),
                        num(row.close),
                        num(row.returnVsPrevClose)));
                w.write("\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write " + path, e);
        }
    }

    private static String num(double value) {
        return String.format(Locale.US, "%.2f", value);
    }
}
