package com.rotation.strategy.breakout.indicator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class SmaTest {

    @Test
    void matchesHandCalculatedWindows() {
        double[] values = {1, 2, 3, 4, 5, 6};
        double[] sma = Sma.of(values, 3);

        assertTrue(Double.isNaN(sma[0]));
        assertTrue(Double.isNaN(sma[1]));
        assertEquals(2.0, sma[2], 1e-9);
        assertEquals(3.0, sma[3], 1e-9);
        assertEquals(4.0, sma[4], 1e-9);
        assertEquals(5.0, sma[5], 1e-9);
    }

    @Test
    void windowWithMissingValueIsNaN() {
        double[] values = {1, Double.NaN, 3, 4, 5};
        double[] sma = Sma.of(values, 3);

        assertTrue(Double.isNaN(sma[2])); // window 1,NaN,3
        assertTrue(Double.isNaN(sma[3])); // window NaN,3,4
        assertEquals(4.0, sma[4], 1e-9);  // window 3,4,5
    }
}
