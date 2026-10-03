package com.rotation.strategy.breakout;

import java.util.OptionalDouble;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import com.rotation.strategy.breakout.BreakoutExits.ExitReason;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BreakoutExitsTest {

    private static BreakoutConfig config(Properties overrides) {
        Properties p = new Properties();
        p.setProperty("breakout.sma.fast", "3");
        p.setProperty("breakout.exit.sma.slow", "5");
        p.setProperty("breakout.atr.period", "3");
        p.setProperty("breakout.hard.stop.lookback", "3");
        overrides.forEach(p::put);
        return BreakoutConfig.fromProperties(p);
    }

    private static Properties only(String enabledKey) {
        Properties p = new Properties();
        for (String key : new String[] {
                "breakout.exit.close.below.fast", "breakout.exit.far.or.consec",
                "breakout.exit.fast.below.slow", "breakout.exit.atr.trail", "breakout.exit.hard.stop" }) {
            p.setProperty(key, "false");
        }
        if (enabledKey != null) {
            p.setProperty(enabledKey, "true");
        }
        return p;
    }

    @Test
    void exitAClosesBelowFast() {
        double[] close = {10, 10, 10, 8};
        double[] hi = {11, 11, 11, 9};
        double[] lo = {9, 9, 9, 7};
        BreakoutExits ex = new BreakoutExits(hi, lo, close, config(new Properties()));

        assertEquals(ExitReason.CLOSE_BELOW_FAST, ex.closeExit(3, Double.NaN, 1));
        assertNull(ex.closeExit(2, Double.NaN, 0)); // close == fast, still held
    }

    @Test
    void exitBFiresOnFarBelowOrConsecutive() {
        double[] close = {100, 100, 100, 97}; // SMA3[3] = 99, 97 < 99*0.985
        double[] hi = {101, 101, 101, 98};
        double[] lo = {99, 99, 99, 96};
        BreakoutExits ex = new BreakoutExits(hi, lo, close, config(only("breakout.exit.far.or.consec")));

        assertEquals(ExitReason.FAR_OR_CONSECUTIVE_BELOW, ex.closeExit(3, Double.NaN, 1)); // far below

        double[] close2 = {100, 100, 100, 99}; // 99 just below SMA3=99.667 but not far
        BreakoutExits ex2 = new BreakoutExits(hi, lo, close2, config(only("breakout.exit.far.or.consec")));
        assertEquals(ExitReason.FAR_OR_CONSECUTIVE_BELOW, ex2.closeExit(3, Double.NaN, 2)); // 2 consecutive
        assertNull(ex2.closeExit(3, Double.NaN, 1)); // not far, only 1 below
    }

    @Test
    void exitCFiresWhenFastCrossesBelowSlow() {
        double[] close = {1, 2, 3, 4, 5, 6, 5, 4, 3, 2};
        double[] hi = new double[close.length];
        double[] lo = new double[close.length];
        for (int i = 0; i < close.length; i++) {
            hi[i] = close[i] + 1;
            lo[i] = close[i] - 1;
        }
        BreakoutExits ex = new BreakoutExits(hi, lo, close, config(only("breakout.exit.fast.below.slow")));

        assertNull(ex.closeExit(7, Double.NaN, 0));                       // fast still above slow
        assertEquals(ExitReason.FAST_CROSS_BELOW_SLOW, ex.closeExit(8, Double.NaN, 0)); // cross under
    }

    @Test
    void exitDFiresOnAtrTrailingStop() {
        // Closes decline by 1 with a constant 2-wide range, so ATR settles at 2 and the
        // trailing stop sits 2.5*2 = 5 below the highest close since entry (100).
        double[] close = {100, 99, 98, 97, 96, 95, 94, 93};
        double[] hi = new double[close.length];
        double[] lo = new double[close.length];
        for (int i = 0; i < close.length; i++) {
            hi[i] = close[i] + 1;
            lo[i] = close[i] - 1;
        }
        BreakoutExits ex = new BreakoutExits(hi, lo, close, config(only("breakout.exit.atr.trail")));

        assertNull(ex.closeExit(3, 100.0, 1));                       // 97 >= 100 - 5
        assertEquals(ExitReason.ATR_TRAILING_STOP, ex.closeExit(6, 100.0, 1)); // 94 < 95
    }

    @Test
    void hardStopLevelIsLowerOfLowAndAtrLeg() {
        double[] close = {100, 99, 98, 97, 96, 95, 94, 93};
        double[] hi = new double[close.length];
        double[] lo = new double[close.length];
        for (int i = 0; i < close.length; i++) {
            hi[i] = close[i] + 1;
            lo[i] = close[i] - 1;
        }
        BreakoutExits ex = new BreakoutExits(hi, lo, close, config(new Properties()));

        // entry idx 5: low3 = min(96,95,94) = 94; atr = 2; atrLeg = 95 - 2*2 = 91 -> min = 91.
        assertEquals(91.0, ex.hardStopLevel(5, 95.0), 1e-9);
    }

    @Test
    void hardStopFillHonoursGapDown() {
        double[] close = {100, 99, 98};
        double[] hi = {101, 100, 99};
        double[] lo = {99, 98, 97};
        BreakoutExits ex = new BreakoutExits(hi, lo, close, config(new Properties()));

        double stop = 91.0;
        assertEquals(90.0, ex.hardStopFill(stop, 90.0, 89.0).getAsDouble(), 1e-9); // gap-down -> open
        assertEquals(91.0, ex.hardStopFill(stop, 95.0, 90.0).getAsDouble(), 1e-9); // intraday touch -> stop
        assertFalse(ex.hardStopFill(stop, 95.0, 92.0).isPresent());                // never reached
    }

    @Test
    void hardStopDisabledYieldsNoLevel() {
        double[] close = {100, 99, 98, 97};
        double[] hi = {101, 100, 99, 98};
        double[] lo = {99, 98, 97, 96};
        BreakoutExits ex = new BreakoutExits(hi, lo, close, config(only(null))); // all exits off

        double level = ex.hardStopLevel(3, 97.0);
        assertTrue(Double.isNaN(level));
        assertFalse(ex.hardStopFill(level, 80.0, 70.0).isPresent());
    }

    @Test
    void profitTargetUsesRiskMultiple() {
        Properties on = new Properties();
        on.setProperty("breakout.partial.enabled", "true");
        double[] close = {100, 110, 120, 119};
        double[] hi = {101, 111, 121, 120};
        double[] lo = {99, 109, 119, 118};
        BreakoutExits ex = new BreakoutExits(hi, lo, close, config(on));

        double target = ex.profitTargetLevel(100.0, 90.0); // risk 10 -> 100 + 2*10
        assertEquals(120.0, target, 1e-9);
        assertTrue(ex.reachedProfitTarget(2, target));  // close 120 >= 120
        assertFalse(ex.reachedProfitTarget(1, target)); // close 110 < 120
    }

    @Test
    void profitTargetRequiresPartialEnabled() {
        double[] close = {100, 120};
        double[] hi = {101, 121};
        double[] lo = {99, 119};
        BreakoutExits ex = new BreakoutExits(hi, lo, close, config(new Properties())); // partial disabled
        double target = ex.profitTargetLevel(100.0, 90.0);
        assertFalse(ex.reachedProfitTarget(1, target));
    }

    @Test
    void closeExitReturnsNullWhenAllDisabled() {
        double[] close = {10, 10, 10, 5};
        double[] hi = {11, 11, 11, 6};
        double[] lo = {9, 9, 9, 4};
        BreakoutExits ex = new BreakoutExits(hi, lo, close, config(only(null)));
        assertNull(ex.closeExit(3, 10.0, 3));
    }
}
