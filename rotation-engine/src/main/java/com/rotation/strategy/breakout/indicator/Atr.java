package com.rotation.strategy.breakout.indicator;

import java.util.Arrays;

/**
 * Average True Range (Wilder smoothing). The first value is seeded as the simple
 * average of the first {@code period} true ranges and then smoothed recursively:
 * {@code atr[i] = (atr[i-1] * (period - 1) + tr[i]) / period}. Values before the seed
 * are {@link Double#NaN}.
 */
public final class Atr {

    private Atr() {
    }

    /** Convenience: compute true range from OHLC, then smooth. */
    public static double[] of(double[] high, double[] low, double[] close, int period) {
        return ofTrueRange(TrueRange.of(high, low, close), period);
    }

    public static double[] ofTrueRange(double[] trueRange, int period) {
        if (period <= 0) {
            throw new IllegalArgumentException("period must be greater than zero: " + period);
        }
        int n = trueRange.length;
        double[] atr = new double[n];
        Arrays.fill(atr, Double.NaN);
        int first = 0;
        while (first < n && Double.isNaN(trueRange[first])) {
            first++;
        }
        int seedEnd = first + period - 1;
        if (seedEnd >= n) {
            return atr;
        }
        double sum = 0.0;
        for (int i = first; i <= seedEnd; i++) {
            if (Double.isNaN(trueRange[i])) {
                return atr; // cannot seed across a gap
            }
            sum += trueRange[i];
        }
        atr[seedEnd] = sum / period;
        for (int i = seedEnd + 1; i < n; i++) {
            double tr = trueRange[i];
            if (Double.isNaN(tr)) {
                atr[i] = atr[i - 1]; // carry across a gap day
            } else {
                atr[i] = (atr[i - 1] * (period - 1) + tr) / period;
            }
        }
        return atr;
    }
}
