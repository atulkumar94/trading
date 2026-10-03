package com.rotation.strategy.breakout.indicator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdxTest {

    @Test
    void perfectUptrendGivesMaxAdx() {
        // Each bar makes a higher high and higher low by 1, so +DM=1, -DM=0 every bar
        // and every DX is 100, so the Wilder-smoothed ADX is also 100.
        double[] high = {10, 11, 12, 13, 14, 15, 16};
        double[] low = {9, 10, 11, 12, 13, 14, 15};
        double[] close = {9.5, 10.5, 11.5, 12.5, 13.5, 14.5, 15.5};

        Adx.Result r = Adx.compute(high, low, close, 3);

        assertEquals(100.0, r.adx[6], 1e-9);
        assertEquals(0.0, r.minusDi[6], 1e-9);
        assertTrue(r.plusDi[6] > r.minusDi[6]);
        assertTrue(Double.isNaN(r.adx[4])); // still in warm-up
    }

    @Test
    void flatSeriesGivesZeroAdx() {
        double[] high = {5, 5, 5, 5, 5, 5, 5};
        double[] low = {3, 3, 3, 3, 3, 3, 3};
        double[] close = {4, 4, 4, 4, 4, 4, 4};

        double[] adx = Adx.of(high, low, close, 3);

        assertEquals(0.0, adx[6], 1e-9);
    }
}
