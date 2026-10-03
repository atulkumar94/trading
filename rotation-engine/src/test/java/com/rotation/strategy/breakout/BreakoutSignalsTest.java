package com.rotation.strategy.breakout;

import java.util.Properties;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BreakoutSignalsTest {

    private static BreakoutConfig config(Properties overrides) {
        Properties p = new Properties();
        // Keep slow trend windows short enough to be irrelevant to these focused tests.
        p.setProperty("breakout.sma.fast", "3");
        p.setProperty("breakout.sma.trend.mid", "3");
        p.setProperty("breakout.sma.trend.slow", "3");
        p.setProperty("breakout.sma.trend.slope.days", "1");
        p.setProperty("breakout.adx.period", "3");
        p.setProperty("breakout.atr.period", "3");
        p.setProperty("breakout.volume.avg.days", "2");
        p.setProperty("breakout.liquidity.avg.days", "2");
        p.setProperty("breakout.rank.lookback.days", "2");
        overrides.forEach(p::put);
        return BreakoutConfig.fromProperties(p);
    }

    @Test
    void crossUpFiresOnExactBarOnly() {
        // SMA3: idx2=10, idx3=10, idx4=11, idx5=11. Close crosses above only at idx4.
        double[] close = {10, 10, 10, 10, 13, 10};
        double[] vol = {1, 1, 1, 1, 1, 1};
        BreakoutSignals s = new BreakoutSignals(close, close, close, vol, config(new Properties()));

        assertFalse(s.crossUp(2));
        assertFalse(s.crossUp(3));
        assertTrue(s.crossUp(4));
        assertFalse(s.crossUp(5));
    }

    @Test
    void isEntryEqualsCrossWhenFiltersDisabled() {
        Properties off = new Properties();
        off.setProperty("breakout.filter.trend", "false");
        off.setProperty("breakout.filter.adx", "false");
        off.setProperty("breakout.filter.volume", "false");
        off.setProperty("breakout.filter.liquidity", "false");

        double[] close = {10, 10, 10, 10, 13, 10};
        double[] vol = {1, 1, 1, 1, 1, 1};
        BreakoutSignals s = new BreakoutSignals(close, close, close, vol, config(off));

        assertTrue(s.isEntry(4));
        assertFalse(s.isEntry(3));
        assertFalse(s.isEntry(5));
    }

    @Test
    void rankScoreUsesTrailingReturn() {
        double[] close = {10, 11, 12};
        double[] vol = {1, 1, 1};
        BreakoutSignals s = new BreakoutSignals(close, close, close, vol, config(new Properties()));

        assertEquals(0.2, s.rankScore(2), 1e-9); // 12/10 - 1
        assertTrue(Double.isNaN(s.rankScore(1))); // not enough history
    }

    @Test
    void trendFilterTogglesSignal() {
        assertTrue(BreakoutSignals.trendFilter(100, 90, 80, 85, true));   // close>mid>slow, mid rising
        assertFalse(BreakoutSignals.trendFilter(100, 90, 80, 95, true));  // mid falling
        assertFalse(BreakoutSignals.trendFilter(70, 90, 80, 85, true));   // close below mid
        assertTrue(BreakoutSignals.trendFilter(70, 90, 80, 95, false));   // disabled => pass
        assertFalse(BreakoutSignals.trendFilter(Double.NaN, 90, 80, 85, true)); // insufficient data
    }

    @Test
    void adxFilterTogglesSignal() {
        assertTrue(BreakoutSignals.adxFilter(25, 22, true));
        assertFalse(BreakoutSignals.adxFilter(20, 22, true));
        assertTrue(BreakoutSignals.adxFilter(20, 22, false));
        assertFalse(BreakoutSignals.adxFilter(Double.NaN, 22, true));
    }

    @Test
    void volumeFilterTogglesSignal() {
        assertTrue(BreakoutSignals.volumeFilter(100, 50, true));
        assertFalse(BreakoutSignals.volumeFilter(40, 50, true));
        assertTrue(BreakoutSignals.volumeFilter(40, 50, false));
        assertFalse(BreakoutSignals.volumeFilter(100, Double.NaN, true));
    }

    @Test
    void liquidityFilterTogglesSignal() {
        assertTrue(BreakoutSignals.liquidityFilter(6e7, 5e7, true));
        assertFalse(BreakoutSignals.liquidityFilter(4e7, 5e7, true));
        assertTrue(BreakoutSignals.liquidityFilter(4e7, 5e7, false));
        assertFalse(BreakoutSignals.liquidityFilter(Double.NaN, 5e7, true));
    }
}
