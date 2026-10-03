package com.rotation.strategy.breakout.indicator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtrTest {

    private static final double[] HIGH = {10, 12, 13, 12, 15};
    private static final double[] LOW = {8, 9, 11, 10, 11};
    private static final double[] CLOSE = {9, 11, 12, 10, 14};

    @Test
    void trueRangeMatchesHandCalc() {
        double[] tr = TrueRange.of(HIGH, LOW, CLOSE);
        assertEquals(2.0, tr[0], 1e-9); // first bar: high-low
        assertEquals(3.0, tr[1], 1e-9); // max(3, |12-9|, |9-9|)
        assertEquals(2.0, tr[2], 1e-9);
        assertEquals(2.0, tr[3], 1e-9); // max(2, |12-12|, |10-12|)
        assertEquals(5.0, tr[4], 1e-9); // max(4, |15-10|, |11-10|)
    }

    @Test
    void wilderAtrMatchesHandCalc() {
        double[] atr = Atr.of(HIGH, LOW, CLOSE, 3);
        assertTrue(Double.isNaN(atr[0]));
        assertTrue(Double.isNaN(atr[1]));
        assertEquals(7.0 / 3.0, atr[2], 1e-9);        // seed = mean(2,3,2)
        assertEquals(2.22222222222, atr[3], 1e-9);    // (seed*2 + 2)/3
        assertEquals(3.14814814814, atr[4], 1e-9);    // (prev*2 + 5)/3
    }
}
