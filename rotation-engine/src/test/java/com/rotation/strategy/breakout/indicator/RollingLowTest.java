package com.rotation.strategy.breakout.indicator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class RollingLowTest {

    @Test
    void matchesHandCalculatedWindows() {
        double[] low = {5, 3, 4, 2, 6, 1};
        double[] out = RollingLow.of(low, 3);

        assertTrue(Double.isNaN(out[0]));
        assertTrue(Double.isNaN(out[1]));
        assertEquals(3.0, out[2], 1e-9); // min(5,3,4)
        assertEquals(2.0, out[3], 1e-9); // min(3,4,2)
        assertEquals(2.0, out[4], 1e-9); // min(4,2,6)
        assertEquals(1.0, out[5], 1e-9); // min(2,6,1)
    }

    @Test
    void windowWithMissingValueIsNaN() {
        double[] low = {5, Double.NaN, 4, 2, 6};
        double[] out = RollingLow.of(low, 3);

        assertTrue(Double.isNaN(out[2])); // window 5,NaN,4
        assertTrue(Double.isNaN(out[3])); // window NaN,4,2
        assertEquals(2.0, out[4], 1e-9);  // window 4,2,6
    }
}
