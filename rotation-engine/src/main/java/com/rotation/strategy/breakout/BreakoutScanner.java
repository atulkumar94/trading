package com.rotation.strategy.breakout;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.rotation.model.DailyBars;

/**
 * End-of-day breakout scanner. Runs the same {@link BreakoutSignals} rules as the
 * backtest over the latest available session and lists the symbols whose close crossed
 * above the fast SMA, flagging which ones clear every enabled filter (a full entry
 * signal) and why the rest were rejected.
 *
 * <p>It is stateless and read-only: it holds no book and places no orders. A trade on a
 * signal would execute at the <em>next</em> session's open, exactly as in the backtest,
 * so this is a watchlist of candidates rather than an instruction to buy on the scan day.
 */
public final class BreakoutScanner {

    private final BreakoutConfig cfg;

    public BreakoutScanner(BreakoutConfig cfg) {
        this.cfg = cfg;
    }

    /** Scan the latest session in {@code bars}. */
    public List<ScanRow> scan(DailyBars bars) {
        return scan(bars, bars.dateCount() - 1);
    }

    /** Scan the session at {@code dateIdx}; returns one row per cross-up, entries first. */
    public List<ScanRow> scan(DailyBars bars, int dateIdx) {
        if (dateIdx < 1 || dateIdx >= bars.dateCount()) {
            throw new IllegalArgumentException("dateIdx out of range for a cross-up scan: " + dateIdx);
        }
        LocalDate date = bars.dates().get(dateIdx);
        int symbolCount = bars.symbolCount();
        int dateCount = bars.dateCount();
        List<ScanRow> rows = new ArrayList<>();
        for (int s = 0; s < symbolCount; s++) {
            double[] high = new double[dateCount];
            double[] low = new double[dateCount];
            double[] close = new double[dateCount];
            double[] volume = new double[dateCount];
            for (int d = 0; d < dateCount; d++) {
                high[d] = bars.highAt(d, s);
                low[d] = bars.lowAt(d, s);
                close[d] = bars.closeAt(d, s);
                volume[d] = bars.volumeAt(d, s);
            }
            BreakoutSignals signals = new BreakoutSignals(high, low, close, volume, cfg);
            if (!signals.crossUp(dateIdx)) {
                continue;
            }
            rows.add(new ScanRow(bars.symbols().get(s), date, signals.isEntry(dateIdx),
                    signals.trendOk(dateIdx), signals.adxOk(dateIdx), signals.volumeOk(dateIdx),
                    signals.liquidityOk(dateIdx), signals.close(dateIdx), signals.smaFast(dateIdx),
                    signals.smaMid(dateIdx), signals.smaSlow(dateIdx), signals.adx(dateIdx),
                    signals.atr(dateIdx), signals.volume(dateIdx), signals.avgVolume(dateIdx),
                    signals.avgTradedValue(dateIdx), signals.rankScore(dateIdx)));
        }
        // Full entries first, then by relative-strength score (NaN last), then symbol.
        rows.sort(Comparator.comparing((ScanRow r) -> r.isEntry).reversed()
                .thenComparing(Comparator.comparingDouble((ScanRow r) -> rankKey(r.rankScore)).reversed())
                .thenComparing(r -> r.symbol));
        return rows;
    }

    private static double rankKey(double score) {
        return Double.isNaN(score) ? Double.NEGATIVE_INFINITY : score;
    }

    /** One scanned symbol: the cross-up plus its per-filter outcomes and indicator values. */
    public static final class ScanRow {
        public final String symbol;
        public final LocalDate date;
        public final boolean isEntry;
        public final boolean trendOk;
        public final boolean adxOk;
        public final boolean volumeOk;
        public final boolean liquidityOk;
        public final double close;
        public final double smaFast;
        public final double smaMid;
        public final double smaSlow;
        public final double adx;
        public final double atr;
        public final double volume;
        public final double avgVolume;
        public final double avgTradedValue;
        public final double rankScore;

        public ScanRow(String symbol, LocalDate date, boolean isEntry, boolean trendOk, boolean adxOk,
                       boolean volumeOk, boolean liquidityOk, double close, double smaFast, double smaMid,
                       double smaSlow, double adx, double atr, double volume, double avgVolume,
                       double avgTradedValue, double rankScore) {
            this.symbol = symbol;
            this.date = date;
            this.isEntry = isEntry;
            this.trendOk = trendOk;
            this.adxOk = adxOk;
            this.volumeOk = volumeOk;
            this.liquidityOk = liquidityOk;
            this.close = close;
            this.smaFast = smaFast;
            this.smaMid = smaMid;
            this.smaSlow = smaSlow;
            this.adx = adx;
            this.atr = atr;
            this.volume = volume;
            this.avgVolume = avgVolume;
            this.avgTradedValue = avgTradedValue;
            this.rankScore = rankScore;
        }
    }
}
