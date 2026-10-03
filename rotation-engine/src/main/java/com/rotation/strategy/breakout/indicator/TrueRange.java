package com.rotation.strategy.breakout.indicator;

import java.util.Arrays;

/**
 * Wilder's True Range: for each bar the greatest of the high-low range, the move from
 * the prior close up to the high, and the move from the prior close down to the low.
 * The first bar (or any bar whose prior close is missing) falls back to high-low.
 */
public final class TrueRange {

    private TrueRange() {
    }

    public static double[] of(double[] high, double[] low, double[] close) {
        int n = high.length;
        double[] tr = new double[n];
        Arrays.fill(tr, Double.NaN);
        for (int i = 0; i < n; i++) {
            double h = high[i];
            double l = low[i];
            if (Double.isNaN(h) || Double.isNaN(l)) {
                continue;
            }
            double prevClose = i > 0 ? close[i - 1] : Double.NaN;
            if (Double.isNaN(prevClose)) {
                tr[i] = h - l;
            } else {
                tr[i] = Math.max(h - l, Math.max(Math.abs(h - prevClose), Math.abs(l - prevClose)));
            }
        }
        return tr;
    }
}
