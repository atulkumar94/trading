package com.rotation.strategy.breakout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import com.rotation.model.DailyBars;
import com.rotation.model.DailyCandle;
import com.rotation.model.SymbolDailyCandles;
import com.rotation.strategy.breakout.BreakoutScanner.ScanRow;

/**
 * EOD scanner behaviour: only symbols whose latest close crosses above the fast SMA are
 * reported, full entries (all enabled filters pass) sort ahead of rejected cross-ups, and
 * the per-filter transparency flags reflect the configured toggles.
 */
class BreakoutScannerTest {

    /** Fast SMA = 3 with every quality filter disabled, so a cross-up is a full entry. */
    private static BreakoutConfig filtersOff() {
        Properties p = new Properties();
        p.setProperty("breakout.sma.fast", "3");
        p.setProperty("breakout.filter.trend", "false");
        p.setProperty("breakout.filter.adx", "false");
        p.setProperty("breakout.filter.volume", "false");
        p.setProperty("breakout.filter.liquidity", "false");
        return BreakoutConfig.fromProperties(p);
    }

    private static DailyBars bars(String[] symbols, double[][] closes) {
        List<SymbolDailyCandles> series = new ArrayList<>();
        for (int s = 0; s < symbols.length; s++) {
            double[] close = closes[s];
            List<DailyCandle> candles = new ArrayList<>();
            for (int d = 0; d < close.length; d++) {
                candles.add(new DailyCandle(LocalDate.of(2020, 1, 1).plusDays(d),
                        close[d], close[d] + 1.0, close[d] - 1.0, close[d], 1000.0));
            }
            series.add(new SymbolDailyCandles(symbols[s], candles));
        }
        return DailyBars.build(series, false);
    }

    @Test
    void reportsOnlyLatestSessionCrossUps() {
        // AAA crosses above SMA3 on the last bar; BBB stays flat (no cross).
        double[] crossing = {10, 10, 10, 10, 9, 20};
        double[] flat = {10, 10, 10, 10, 10, 10};
        DailyBars bars = bars(new String[] {"AAA", "BBB"}, new double[][] {crossing, flat});

        List<ScanRow> rows = new BreakoutScanner(filtersOff()).scan(bars);

        assertEquals(1, rows.size());
        ScanRow row = rows.get(0);
        assertEquals("AAA", row.symbol);
        assertEquals(LocalDate.of(2020, 1, 6), row.date);
        assertTrue(row.isEntry);
        assertTrue(row.trendOk);
        assertTrue(row.adxOk);
        assertTrue(row.volumeOk);
        assertTrue(row.liquidityOk);
        assertEquals(20.0, row.close, 1e-9);
    }

    @Test
    void rejectedCrossUpIsReportedWithFailingFilterFlag() {
        // AAA crosses up, but with the ADX filter on at an impossible threshold it is not a full entry.
        double[] crossing = {10, 10, 10, 10, 9, 20};
        Properties p = new Properties();
        p.setProperty("breakout.sma.fast", "3");
        p.setProperty("breakout.filter.trend", "false");
        p.setProperty("breakout.filter.adx", "true");
        p.setProperty("breakout.adx.min", "100");
        p.setProperty("breakout.filter.volume", "false");
        p.setProperty("breakout.filter.liquidity", "false");
        DailyBars bars = bars(new String[] {"AAA"}, new double[][] {crossing});

        List<ScanRow> rows = new BreakoutScanner(BreakoutConfig.fromProperties(p)).scan(bars);

        assertEquals(1, rows.size());
        ScanRow row = rows.get(0);
        assertFalse(row.isEntry);
        assertFalse(row.adxOk);
        assertTrue(row.trendOk);
    }

    @Test
    void entriesSortByRelativeStrength() {
        // Both symbols cross up and are full entries (filters off); STRONG has the bigger
        // trailing return over the (shortened) ranking window, so it sorts first.
        double[] strong = {10, 10, 10, 10, 9, 30};
        double[] weak = {10, 10, 10, 10, 9, 20};
        Properties p = new Properties();
        p.setProperty("breakout.sma.fast", "3");
        p.setProperty("breakout.rank.lookback.days", "2");
        p.setProperty("breakout.filter.trend", "false");
        p.setProperty("breakout.filter.adx", "false");
        p.setProperty("breakout.filter.volume", "false");
        p.setProperty("breakout.filter.liquidity", "false");
        DailyBars bars = bars(new String[] {"WEAK", "STRONG"}, new double[][] {weak, strong});

        List<ScanRow> rows = new BreakoutScanner(BreakoutConfig.fromProperties(p)).scan(bars);

        assertEquals(2, rows.size());
        assertTrue(rows.get(0).isEntry);
        assertTrue(rows.get(1).isEntry);
        assertEquals("STRONG", rows.get(0).symbol);
        assertEquals("WEAK", rows.get(1).symbol);
        assertTrue(rows.get(0).rankScore > rows.get(1).rankScore);
    }
}
