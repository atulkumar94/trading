package com.rotation.strategy.breakout.indicator;

import java.util.Arrays;

/**
 * Lowest low over a trailing window. {@code out[i]} is the minimum of the {@code period}
 * lows ending at {@code i}, or {@link Double#NaN} while the window is not yet full or
 * whenever any value in the window is missing (so warm-up and data gaps read as NaN).
 */
public final class RollingLow {

    private RollingLow() {
    }

    public static double[] of(double[] low, int period) {
        if (period <= 0) {
            throw new IllegalArgumentException("period must be greater than zero: " + period);
        }
        int n = low.length;
        double[] out = new double[n];
        Arrays.fill(out, Double.NaN);
        for (int i = period - 1; i < n; i++) {
            double min = Double.POSITIVE_INFINITY;
            boolean complete = true;
            for (int k = i - period + 1; k <= i; k++) {
                double v = low[k];
                if (Double.isNaN(v)) {
                    complete = false;
                    break;
                }
                if (v < min) {
                    min = v;
                }
            }
            if (complete) {
                out[i] = min;
            }
        }
        return out;
    }
}
