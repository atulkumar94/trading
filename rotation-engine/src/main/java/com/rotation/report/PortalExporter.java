package com.rotation.report;

import com.rotation.model.BacktestResult;
import com.rotation.model.DailyBars;
import com.rotation.model.DailyPortfolioRow;
import com.rotation.model.DailyPositionRow;
import com.rotation.model.DailyValuation;
import com.rotation.model.EquityRow;
import com.rotation.model.LedgerFill;
import com.rotation.model.LookbackRow;
import com.rotation.model.PerformanceRow;
import com.rotation.model.RebalanceRecord;
import com.rotation.model.TradeLedgerRow;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;

/**
 * Writes {@code {prefix}_portal.html}: a single self-contained, read-only HTML report.
 * Every engine-generated dataset (daily portfolio, positions, ledger, rebalances,
 * rankings, yearly report, adjusted prices) and the chart library are embedded, so the
 * file opens offline without fetching CSVs or any CDN. The browser only filters,
 * charts and compounds the engine's daily figures; it does not re-run the strategy.
 *
 * <p>Large tables are columnar ({@code {"col": [..]}}) and dates/symbols are indexes
 * into the {@code sessions}/{@code symbols} arrays. Prices are integers scaled by
 * {@code 10^k}: closes delta-encoded, open/high/low as offsets from the close.
 */
public final class PortalExporter {

    private static final String RESOURCE_ROOT = "/portal/";
    static final List<String> ACTIONS = List.of(LedgerFill.ENTRY, LedgerFill.ADD, LedgerFill.TRIM,
            LedgerFill.EXIT, LedgerFill.STOP, LedgerFill.DROP);
    static final List<String> PRICE_STATUSES = List.of(DailyPositionRow.PRICE_OK,
            DailyPositionRow.PRICE_MISSING, DailyPositionRow.PRICE_UNCHANGED);

    public Path export(String manifestJson, DailyBars bars, BacktestResult result, DailyValuation valuation,
                       Map<String, String> sectors, Path outputDir, String prefix) {
        String data = buildData(manifestJson, bars, result, valuation, sectors,
                outputDir.resolve(prefix + "_yearly.csv"));
        String html = resource("portal.html")
                .replace("/*__PORTAL_CSS__*/", resource("portal.css"))
                .replace("/*__LIGHTWEIGHT_CHARTS__*/", resource("vendor/lightweight-charts.standalone.production.js"))
                .replace("/*__PORTAL_METRICS__*/", resource("portal-metrics.js"))
                .replace("/*__PORTAL_APP__*/", resource("portal.js"))
                .replace("__PORTAL_DATA__", data);
        Path path = outputDir.resolve(prefix + "_portal.html");
        try {
            Files.createDirectories(outputDir);
            Files.writeString(path, html, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write " + path, e);
        }
        return path;
    }

    /** The embedded dataset (also used by tests). */
    public String buildData(String manifestJson, DailyBars bars, BacktestResult result, DailyValuation valuation,
                            Map<String, String> sectors, Path yearlyCsv) {
        List<LocalDate> sessions = bars.dates();
        Map<LocalDate, Integer> sessionIdx = new HashMap<>();
        for (int i = 0; i < sessions.size(); i++) {
            sessionIdx.put(sessions.get(i), i);
        }
        List<String> symbols = bars.symbols();
        Map<String, Integer> symbolIdx = new HashMap<>();
        for (int i = 0; i < symbols.size(); i++) {
            symbolIdx.put(symbols.get(i), i);
        }
        Function<LocalDate, Long> d = date -> date == null ? null : (long) sessionIdx.get(date);
        Function<String, Long> s = sym -> {
            Integer idx = symbolIdx.get(sym);
            if (idx == null) {
                throw new IllegalStateException("Symbol not in bars: " + sym);
            }
            return (long) idx;
        };

        JsonWriter j = new JsonWriter(new StringBuilder(16 << 20));
        j.beginObject();
        j.name("manifest").raw(manifestJson);
        j.name("sessions").beginArray();
        for (LocalDate date : sessions) {
            j.value(date);
        }
        j.endArray();
        j.name("symbols").beginArray();
        for (String symbol : symbols) {
            j.value(symbol);
        }
        j.endArray();
        j.name("sectors").beginObject();
        for (String symbol : symbols) {
            if (sectors.containsKey(symbol)) {
                j.field(symbol, sectors.get(symbol));
            }
        }
        j.endObject();
        j.name("actions").beginArray();
        ACTIONS.forEach(j::value);
        j.endArray();
        j.name("priceStatuses").beginArray();
        PRICE_STATUSES.forEach(j::value);
        j.endArray();

        List<DailyPortfolioRow> days = valuation.portfolioRows();
        j.name("portfolio").beginObject();
        indexes(j, "d", days, r -> d.apply(r.date));
        longs(j, "reb", days, r -> r.rebalanceNumber);
        longs(j, "pos", days, r -> r.positions);
        doubles(j, "cash", days, r -> r.cash, 2);
        doubles(j, "inv", days, r -> r.investedValue, 2);
        doubles(j, "eq", days, r -> r.accountEquity, 2);
        doubles(j, "con", days, r -> r.contribution, 2);
        doubles(j, "ccon", days, r -> r.cumulativeContributions, 2);
        doubles(j, "net", days, r -> r.netCapital, 2);
        doubles(j, "dpnl", days, r -> r.dailyPnl, 2);
        j.name("ret").beginArray();
        for (DailyPortfolioRow r : days) {
            j.value(r.dailyReturnPct, 6);
        }
        j.endArray();
        doubles(j, "twr", days, r -> r.twrIndex, 10);
        doubles(j, "dd", days, r -> r.drawdownPct, 6);
        doubles(j, "real", days, r -> r.realizedPnl, 2);
        doubles(j, "unr", days, r -> r.unrealizedPnl, 2);
        doubles(j, "tpnl", days, r -> r.totalPnl, 2);
        longs(j, "miss", days, r -> r.missingPrices);
        j.endObject();

        List<DailyPositionRow> pos = valuation.positionRows();
        j.name("positions").beginObject();
        indexes(j, "d", pos, r -> d.apply(r.date));
        indexes(j, "s", pos, r -> s.apply(r.symbol));
        indexes(j, "ed", pos, r -> d.apply(r.entryDate));
        longs(j, "reb", pos, r -> r.rebalanceNumber);
        doubles(j, "q", pos, r -> r.quantity, 4);
        doubles(j, "ep", pos, r -> r.entryPrice, 4);
        doubles(j, "ac", pos, r -> r.averageCost, 4);
        doubles(j, "cb", pos, r -> r.costBasis, 2);
        j.name("c").beginArray();
        for (DailyPositionRow r : pos) {
            j.value(r.close, 4);
        }
        j.endArray();
        doubles(j, "mv", pos, r -> r.marketValue, 2);
        doubles(j, "upnl", pos, r -> r.unrealizedPnl, 2);
        doubles(j, "upct", pos, r -> r.unrealizedPct, 4);
        doubles(j, "w", pos, r -> r.weightPct, 4);
        longs(j, "st", pos, r -> PRICE_STATUSES.indexOf(r.priceStatus));
        j.endObject();

        List<TradeLedgerRow> ledger = valuation.ledgerRows();
        j.name("ledger").beginObject();
        indexes(j, "d", ledger, r -> d.apply(r.date));
        indexes(j, "sd", ledger, r -> d.apply(r.signalDate));
        longs(j, "reb", ledger, r -> r.rebalanceNumber);
        longs(j, "a", ledger, r -> ACTIONS.indexOf(r.action));
        indexes(j, "s", ledger, r -> s.apply(r.symbol));
        doubles(j, "q", ledger, r -> r.quantity, 4);
        doubles(j, "p", ledger, r -> r.price, 4);
        doubles(j, "v", ledger, r -> r.tradeValue, 2);
        indexes(j, "ed", ledger, r -> d.apply(r.entryDate));
        doubles(j, "ep", ledger, r -> r.entryPrice, 4);
        doubles(j, "acb", ledger, r -> r.averageCostBefore, 4);
        doubles(j, "aca", ledger, r -> r.averageCostAfter, 4);
        doubles(j, "pa", ledger, r -> r.positionAfter, 4);
        j.name("rp").beginArray();
        for (TradeLedgerRow r : ledger) {
            j.value(r.realizedPnl, 2);
        }
        j.endArray();
        j.name("pe").beginArray();
        for (TradeLedgerRow r : ledger) {
            j.value(r.pnlVsEntryPrice, 2);
        }
        j.endArray();
        doubles(j, "cash", ledger, r -> r.cashAfter, 2);
        j.name("r").beginArray();
        for (TradeLedgerRow r : ledger) {
            j.value(r.reason);
        }
        j.endArray();
        j.endObject();

        List<RebalanceRecord> rebalances = result.rebalances();
        List<EquityRow> equity = result.equityCurve();
        j.name("rebalances").beginArray();
        for (int i = 0; i < rebalances.size(); i++) {
            RebalanceRecord r = rebalances.get(i);
            j.beginObject();
            j.field("n", i + 1);
            j.field("d", d.apply(r.date));
            j.field("sd", d.apply(r.signalDate));
            LocalDate lookbackStart = i < equity.size() ? equity.get(i).lookbackStart : null;
            j.name("lb");
            if (lookbackStart == null) {
                j.nullValue();
            } else {
                j.value(d.apply(lookbackStart));
            }
            j.field("before", r.portfolioValueBefore, 2);
            j.field("after", r.portfolioValueAfter, 2);
            j.field("eq", r.accountEquity, 2);
            j.field("pnl", r.periodPnl, 2);
            j.field("ret", r.periodReturnPct, 2);
            j.field("con", i < equity.size() ? equity.get(i).contribution : 0.0, 2);
            j.field("cnt", r.selectedCount);
            j.field("entered", r.entered);
            j.field("exited", r.exited);
            j.field("held", r.held);
            j.endObject();
        }
        j.endArray();

        List<PerformanceRow> perf = result.performanceRows();
        j.name("rankings").beginObject();
        longs(j, "n", perf, r -> r.rebalanceNumber);
        longs(j, "r", perf, r -> r.rank);
        indexes(j, "s", perf, r -> s.apply(r.symbol));
        doubles(j, "lp", perf, r -> r.lookbackPrice, 4);
        doubles(j, "cp", perf, r -> r.currentPrice, 4);
        doubles(j, "ret", perf, r -> r.lookbackReturnPct, 4);
        longs(j, "h", perf, r -> r.historyDays);
        longs(j, "sel", perf, r -> r.selected ? 1 : 0);
        j.endObject();

        List<LookbackRow> lookback = result.lookbackRows();
        j.name("lookback").beginObject();
        indexes(j, "sd", lookback, r -> d.apply(r.signalDate));
        j.name("ed").beginArray();
        for (LookbackRow r : lookback) {
            if (r.executionDate == null) {
                j.nullValue();
            } else {
                j.value(d.apply(r.executionDate));
            }
        }
        j.endArray();
        indexes(j, "lb", lookback, r -> d.apply(r.lookbackStart));
        longs(j, "r", lookback, r -> r.rank);
        indexes(j, "s", lookback, r -> s.apply(r.symbol));
        doubles(j, "lp", lookback, r -> r.lookbackPrice, 4);
        doubles(j, "cp", lookback, r -> r.currentPrice, 4);
        doubles(j, "ret", lookback, r -> r.lookbackReturnPct, 4);
        longs(j, "h", lookback, r -> r.historyDays);
        longs(j, "sel", lookback, r -> r.selected ? 1 : 0);
        j.endObject();

        writeYearly(j, yearlyCsv);
        writePrices(j, bars);
        j.endObject();
        return j.toString();
    }

    /** The yearly report exactly as CsvExporter wrote it (columns kept by header name). */
    private static void writeYearly(JsonWriter j, Path yearlyCsv) {
        j.name("yearly").beginArray();
        if (yearlyCsv != null && Files.exists(yearlyCsv)) {
            try (BufferedReader reader = Files.newBufferedReader(yearlyCsv)) {
                String header = reader.readLine();
                String[] cols = header == null ? new String[0] : header.split(",");
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    String[] parts = line.split(",", -1);
                    j.beginObject();
                    for (int i = 0; i < cols.length && i < parts.length; i++) {
                        String col = cols[i].trim();
                        String value = parts[i].trim();
                        if (col.endsWith("_date")) {
                            j.field(col, value);
                        } else if (value.isEmpty()) {
                            j.name(col).nullValue();
                        } else {
                            j.name(col).value(Double.parseDouble(value), 6);
                        }
                    }
                    j.endObject();
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to read " + yearlyCsv, e);
            }
        }
        j.endArray();
    }

    private static void writePrices(JsonWriter j, DailyBars bars) {
        j.name("prices").beginObject();
        for (int s = 0; s < bars.symbolCount(); s++) {
            int first = -1;
            int last = -1;
            double min = Double.POSITIVE_INFINITY;
            for (int d = 0; d < bars.dateCount(); d++) {
                double c = bars.closeAt(d, s);
                if (!Double.isNaN(c)) {
                    if (first < 0) {
                        first = d;
                    }
                    last = d;
                    min = Math.min(min, Math.min(c, bars.lowAt(d, s)));
                }
            }
            if (first < 0) {
                continue;
            }
            int k = min >= 10 ? 2 : min >= 1 ? 3 : 4;
            double scale = Math.pow(10, k);
            j.name(bars.symbols().get(s)).beginObject();
            j.field("s", first);
            j.field("k", k);
            StringBuilder c = new StringBuilder("[");
            StringBuilder o = new StringBuilder("[");
            StringBuilder h = new StringBuilder("[");
            StringBuilder l = new StringBuilder("[");
            long previous = 0;
            for (int d = first; d <= last; d++) {
                if (d > first) {
                    c.append(',');
                    o.append(',');
                    h.append(',');
                    l.append(',');
                }
                double close = bars.closeAt(d, s);
                if (Double.isNaN(close)) {
                    c.append("null");
                    o.append("null");
                    h.append("null");
                    l.append("null");
                    continue;
                }
                long ci = Math.round(close * scale);
                c.append(ci - previous);
                previous = ci;
                o.append(offset(bars.openAt(d, s), scale, ci));
                h.append(offset(bars.highAt(d, s), scale, ci));
                l.append(offset(bars.lowAt(d, s), scale, ci));
            }
            j.name("c").raw(c.append(']').toString());
            j.name("o").raw(o.append(']').toString());
            j.name("h").raw(h.append(']').toString());
            j.name("l").raw(l.append(']').toString());
            j.endObject();
        }
        j.endObject();
    }

    private static String offset(double value, double scale, long closeInt) {
        return Double.isNaN(value) ? "null" : Long.toString(Math.round(value * scale) - closeInt);
    }

    private static <T> void indexes(JsonWriter j, String name, List<T> rows, Function<T, Long> f) {
        j.name(name).beginArray();
        for (T row : rows) {
            Long v = f.apply(row);
            if (v == null) {
                j.nullValue();
            } else {
                j.value(v);
            }
        }
        j.endArray();
    }

    private static <T> void longs(JsonWriter j, String name, List<T> rows, ToLongFunction<T> f) {
        j.name(name).beginArray();
        for (T row : rows) {
            j.value(f.applyAsLong(row));
        }
        j.endArray();
    }

    private static <T> void doubles(JsonWriter j, String name, List<T> rows, ToDoubleFunction<T> f, int decimals) {
        j.name(name).beginArray();
        for (T row : rows) {
            j.value(f.applyAsDouble(row), decimals);
        }
        j.endArray();
    }

    private static String resource(String name) {
        try (InputStream in = PortalExporter.class.getResourceAsStream(RESOURCE_ROOT + name)) {
            if (in == null) {
                throw new IllegalStateException("Portal resource missing from the classpath: " + RESOURCE_ROOT + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read portal resource " + name, e);
        }
    }
}
