package com.rotation.strategy.breakout.indicator;

import java.util.Arrays;

/**
 * Simple moving average. Pure array-in/array-out: {@code out[i]} is the mean of the
 * {@code period} values ending at {@code i}, or {@link Double#NaN} while the window is
 * not yet full or whenever any value in the window is missing (so leading warm-up and
 * data gaps both read as NaN rather than a wrong number).
 */
public final class Sma {

    private Sma() {
    }

    public static double[] of(double[] values, int period) {
        if (period <= 0) {
            throw new IllegalArgumentException("period must be greater than zero: " + period);
        }
        int n = values.length;
        double[] out = new double[n];
        Arrays.fill(out, Double.NaN);
        for (int i = period - 1; i < n; i++) {
            double sum = 0.0;
            boolean complete = true;
            for (int k = i - period + 1; k <= i; k++) {
                double v = values[k];
                if (Double.isNaN(v)) {
                    complete = false;
                    break;
                }
                sum += v;
            }
            if (complete) {
                out[i] = sum / period;
            }
        }
        return out;
    }
}
