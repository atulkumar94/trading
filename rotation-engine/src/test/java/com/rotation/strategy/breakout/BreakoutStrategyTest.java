package com.rotation.strategy.breakout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import com.rotation.model.BacktestResult;
import com.rotation.model.DailyBars;
import com.rotation.model.DailyCandle;
import com.rotation.model.DailyMark;
import com.rotation.model.HoldingsRow;
import com.rotation.model.LedgerFill;
import com.rotation.model.RebalanceRecord;
import com.rotation.model.SymbolDailyCandles;
import com.rotation.model.YearEndEquity;

/**
 * Execution-engine behaviours for {@link BreakoutStrategy}: next-open entry fills,
 * sizing caps, cost baked into the fill price, gap-down hard stops, and the
 * concurrent-position cap. Scenarios use a short fast SMA with the four quality
 * filters disabled so entries fire on a deterministic cross-up.
 */
class BreakoutStrategyTest {

    private static final double EPS = 1e-6;

    /** Base tunables: filters off, fast SMA = 3, no partials. Each test overrides as needed. */
    private static Properties baseProps() {
        Properties p = new Properties();
        p.setProperty("breakout.sma.fast", "3");
        p.setProperty("breakout.filter.trend", "false");
        p.setProperty("breakout.filter.adx", "false");
        p.setProperty("breakout.filter.volume", "false");
        p.setProperty("breakout.filter.liquidity", "false");
        p.setProperty("breakout.partial.enabled", "false");
        return p;
    }

    private static DailyBars bars(String[] symbols, double[][][] ohlcv) {
        // ohlcv[s] = {open[], high[], low[], close[], volume[]} for symbol s.
        List<SymbolDailyCandles> series = new ArrayList<>();
        for (int s = 0; s < symbols.length; s++) {
            double[] open = ohlcv[s][0];
            double[] high = ohlcv[s][1];
            double[] low = ohlcv[s][2];
            double[] close = ohlcv[s][3];
            double[] volume = ohlcv[s][4];
            List<DailyCandle> candles = new ArrayList<>();
            for (int d = 0; d < close.length; d++) {
                candles.add(new DailyCandle(LocalDate.of(2020, 1, 1).plusDays(d),
                        open[d], high[d], low[d], close[d], volume[d]));
            }
            series.add(new SymbolDailyCandles(symbols[s], candles));
        }
        return DailyBars.build(series, false);
    }

    private static double[][] oneSymbol(double[] open, double[] close) {
        // Derive gentle highs/lows from the close so indicators stay well-defined.
        int n = close.length;
        double[] high = new double[n];
        double[] low = new double[n];
        double[] volume = new double[n];
        for (int i = 0; i < n; i++) {
            high[i] = close[i] + 1.0;
            low[i] = close[i] - 1.0;
            volume[i] = 1000.0;
        }
        return new double[][] {open, high, low, close, volume};
    }

    @Test
    void entryFillsAtNextOpenAtTheUncostedPrice() {
        Properties p = baseProps();
        p.setProperty("breakout.capital", "100000");
        p.setProperty("breakout.max.positions", "1");
        p.setProperty("breakout.max.weight.pct", "100");
        p.setProperty("breakout.cost.bps", "0");
        // No exits so the only fill is the entry.
        p.setProperty("breakout.exit.close.below.fast", "false");
        p.setProperty("breakout.exit.far.or.consec", "false");
        p.setProperty("breakout.exit.fast.below.slow", "false");
        p.setProperty("breakout.exit.atr.trail", "false");
        p.setProperty("breakout.exit.hard.stop", "false");

        // Cross above SMA3 at index 3 -> signalled on close[3], filled at open[4].
        double[] close = {100, 100, 100, 110, 110, 110};
        double[] open = {100, 100, 100, 110, 50, 110};
        BacktestResult result = new BreakoutStrategy(BreakoutConfig.fromProperties(p), null)
                .run(bars(new String[] {"AAA"}, new double[][][] {oneSymbol(open, close)}));

        assertEquals(1, result.fills().size(), "exactly one fill (the entry)");
        LedgerFill entry = result.fills().get(0);
        assertEquals(LedgerFill.ENTRY, entry.action);
        assertEquals("AAA", entry.symbol);
        assertEquals(LocalDate.of(2020, 1, 1).plusDays(4), entry.date, "filled at the next open");
        assertEquals(LocalDate.of(2020, 1, 1).plusDays(3), entry.signalDate, "signalled on the cross close");
        assertEquals(50.0, entry.price, EPS, "cost 0 -> fill at the raw open");
        assertEquals(2000.0, entry.quantity, EPS, "floor(100000 / 50)");
        assertEquals(6, result.dailyMarks().size(), "one mark per session");
    }

    @Test
    void perPositionWeightCapLimitsShares() {
        Properties p = baseProps();
        p.setProperty("breakout.capital", "100000");
        p.setProperty("breakout.max.positions", "5");
        p.setProperty("breakout.max.weight.pct", "15");
        p.setProperty("breakout.cost.bps", "0");
        p.setProperty("breakout.exit.hard.stop", "false");

        double[] close = {100, 100, 100, 110, 110, 110};
        double[] open = {100, 100, 100, 110, 10, 110};
        BacktestResult result = new BreakoutStrategy(BreakoutConfig.fromProperties(p), null)
                .run(bars(new String[] {"AAA"}, new double[][][] {oneSymbol(open, close)}));

        LedgerFill entry = result.fills().get(0);
        assertEquals(LedgerFill.ENTRY, entry.action);
        assertEquals(1500.0, entry.quantity, EPS, "15% of 100000 at price 10 -> floor(15000 / 10)");
    }

    @Test
    void transactionCostIsBakedIntoTheFillPrice() {
        Properties p = baseProps();
        p.setProperty("breakout.capital", "100000");
        p.setProperty("breakout.max.positions", "1");
        p.setProperty("breakout.max.weight.pct", "100");
        p.setProperty("breakout.cost.bps", "100"); // 1% per side
        p.setProperty("breakout.exit.close.below.fast", "true");
        p.setProperty("breakout.exit.far.or.consec", "false");
        p.setProperty("breakout.exit.fast.below.slow", "false");
        p.setProperty("breakout.exit.atr.trail", "false");
        p.setProperty("breakout.exit.hard.stop", "false");

        // Enter on the cross at idx 3 (fill idx 4), then collapse below SMA3 to exit (fill idx 6).
        double[] close = {100, 100, 100, 110, 110, 50, 50};
        double[] open = {100, 100, 100, 110, 100, 55, 60};
        BacktestResult result = new BreakoutStrategy(BreakoutConfig.fromProperties(p), null)
                .run(bars(new String[] {"AAA"}, new double[][][] {oneSymbol(open, close)}));

        LedgerFill entry = result.fills().get(0);
        assertEquals(LedgerFill.ENTRY, entry.action);
        assertEquals(101.0, entry.price, EPS, "buy pays the open * (1 + cost)");

        LedgerFill exit = result.fills().get(1);
        assertEquals(LedgerFill.EXIT, exit.action);
        assertEquals(LocalDate.of(2020, 1, 1).plusDays(6), exit.date, "exit fills at the next open");
        assertEquals(60.0 * 0.99, exit.price, EPS, "sell receives the open * (1 - cost)");
    }

    @Test
    void hardStopFillsAtTheOpenOnAGapDown() {
        Properties p = baseProps();
        p.setProperty("breakout.capital", "100000");
        p.setProperty("breakout.max.positions", "1");
        p.setProperty("breakout.max.weight.pct", "100");
        p.setProperty("breakout.cost.bps", "0");
        p.setProperty("breakout.atr.period", "3");
        p.setProperty("breakout.hard.stop.lookback", "3");
        p.setProperty("breakout.hard.stop.atr.mult", "0"); // stop = min(low10, entry) only
        // Isolate the hard stop: disable the close-based exits.
        p.setProperty("breakout.exit.close.below.fast", "false");
        p.setProperty("breakout.exit.far.or.consec", "false");
        p.setProperty("breakout.exit.fast.below.slow", "false");
        p.setProperty("breakout.exit.atr.trail", "false");
        p.setProperty("breakout.exit.hard.stop", "true");

        // Entry at idx4 (open 100). lowestLow over idx2..4 = 99 -> stop = min(99, 100) = 99.
        // idx5 gaps to an open of 95 (<= 99) -> stop fills at the open.
        double[] open = {100, 100, 100, 110, 100, 95, 110};
        double[] close = {100, 100, 100, 110, 110, 110, 110};
        double[] high = {101, 101, 101, 111, 101, 96, 111};
        double[] low = {100, 100, 99, 109, 100, 94, 109};
        double[] volume = {1000, 1000, 1000, 1000, 1000, 1000, 1000};
        BacktestResult result = new BreakoutStrategy(BreakoutConfig.fromProperties(p), null)
                .run(bars(new String[] {"AAA"}, new double[][][] {{open, high, low, close, volume}}));

        LedgerFill entry = result.fills().get(0);
        assertEquals(LedgerFill.ENTRY, entry.action);
        assertEquals(LocalDate.of(2020, 1, 1).plusDays(4), entry.date);

        LedgerFill stop = result.fills().get(1);
        assertEquals(LedgerFill.STOP, stop.action);
        assertEquals(LocalDate.of(2020, 1, 1).plusDays(5), stop.date, "stops during the gap-down session");
        assertEquals(95.0, stop.price, EPS, "gap-down fills at the open, not the stop level");
        assertEquals(entry.quantity, stop.quantity, EPS, "the whole position is closed");
    }

    @Test
    void positionCapLimitsConcurrentEntries() {
        Properties p = baseProps();
        p.setProperty("breakout.capital", "100000");
        p.setProperty("breakout.max.positions", "1");
        p.setProperty("breakout.max.weight.pct", "100");
        p.setProperty("breakout.cost.bps", "0");
        p.setProperty("breakout.exit.hard.stop", "false");

        // Both names cross up at idx3; with one slot only the first-ranked fills.
        double[] close = {100, 100, 100, 110, 110, 110};
        double[] open = {100, 100, 100, 110, 50, 110};
        double[][] aaa = oneSymbol(open, close);
        double[][] bbb = oneSymbol(open.clone(), close.clone());
        BacktestResult result = new BreakoutStrategy(BreakoutConfig.fromProperties(p), null)
                .run(bars(new String[] {"AAA", "BBB"}, new double[][][] {aaa, bbb}));

        long entries = result.fills().stream().filter(f -> f.action.equals(LedgerFill.ENTRY)).count();
        assertEquals(1, entries, "the position cap of 1 blocks the second entry");
        LedgerFill entry = result.fills().stream().filter(f -> f.action.equals(LedgerFill.ENTRY))
                .findFirst().orElseThrow();
        assertNotNull(entry.symbol);
        assertTrue(entry.symbol.equals("AAA") || entry.symbol.equals("BBB"));
    }

    @Test
    void unknownSymbolNeverEntersWhenNoSignalFires() {
        // A flat series never crosses the fast SMA, so nothing trades and equity is preserved.
        Properties p = baseProps();
        p.setProperty("breakout.capital", "100000");
        p.setProperty("breakout.exit.hard.stop", "false");
        double[] close = {100, 100, 100, 100, 100, 100};
        double[] open = {100, 100, 100, 100, 100, 100};
        BacktestResult result = new BreakoutStrategy(BreakoutConfig.fromProperties(p), null)
                .run(bars(new String[] {"AAA"}, new double[][][] {oneSymbol(open, close)}));

        assertTrue(result.fills().isEmpty(), "no cross -> no fills");
        assertEquals(100000.0, result.dailyMarks().get(result.dailyMarks().size() - 1).accountEquity, EPS,
                "all cash, equity unchanged");
    }

    @Test
    void reportsTheBreakoutName() {
        BreakoutStrategy strategy = new BreakoutStrategy(BreakoutConfig.fromProperties(baseProps()), null);
        assertEquals("breakout", strategy.name());
    }

    @Test
    void populatesRebalanceEquityHoldingsAndYearEndReports() {
        Properties p = baseProps();
        p.setProperty("breakout.capital", "100000");
        p.setProperty("breakout.max.positions", "1");
        p.setProperty("breakout.max.weight.pct", "100");
        p.setProperty("breakout.cost.bps", "0");
        p.setProperty("breakout.exit.close.below.fast", "false");
        p.setProperty("breakout.exit.far.or.consec", "false");
        p.setProperty("breakout.exit.fast.below.slow", "false");
        p.setProperty("breakout.exit.atr.trail", "false");
        p.setProperty("breakout.exit.hard.stop", "false");

        // Single entry at the open of index 4, held to the end (no exits). One book-change event.
        double[] close = {100, 100, 100, 110, 110, 110};
        double[] open = {100, 100, 100, 110, 50, 110};
        BacktestResult result = new BreakoutStrategy(BreakoutConfig.fromProperties(p), null)
                .run(bars(new String[] {"AAA"}, new double[][][] {oneSymbol(open, close)}));

        // One rebalance event, with a parallel equity row.
        assertEquals(1, result.rebalances().size());
        assertEquals(1, result.equityCurve().size());
        RebalanceRecord reb = result.rebalances().get(0);
        assertEquals(LocalDate.of(2020, 1, 1).plusDays(4), reb.date);
        assertEquals("AAA", reb.entered);
        assertEquals("AAA", reb.held);
        assertEquals(100000.0, reb.portfolioValueAfter, 0.01, "2000 shares * open 50");
        assertEquals(100000.0, reb.accountEquity, 0.01, "no cash left, book at open");

        // Momentum-only ranking reports stay empty for the per-position breakout.
        assertTrue(result.performanceRows().isEmpty());
        assertTrue(result.lookbackRows().isEmpty());

        // The final holdings row lines up with the last daily mark.
        DailyMark lastMark = result.dailyMarks().get(result.dailyMarks().size() - 1);
        HoldingsRow lastHolding = result.holdingsRows().get(result.holdingsRows().size() - 1);
        assertEquals(lastMark.date, lastHolding.date);
        assertEquals(lastMark.accountEquity, lastHolding.accountEquity, 0.01);
        assertEquals(220000.0, lastHolding.accountEquity, 0.01, "2000 shares * close 110");

        // One calendar year, marked to the last session's equity.
        assertEquals(1, result.yearEndMarks().size());
        YearEndEquity yearEnd = result.yearEndMarks().get(0);
        assertEquals(2020, yearEnd.year);
        assertEquals(lastMark.date, yearEnd.date);
        assertEquals(lastMark.accountEquity, yearEnd.equity, 0.01);
    }
}
