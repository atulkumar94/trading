package com.rotation.strategy.breakout.indicator;

import java.util.Arrays;

/**
 * Average Directional Index (Wilder). Computes the directional movement (+DM/-DM) and
 * true range per bar, Wilder-smooths each over {@code period}, forms the directional
 * indicators (+DI/-DI) and the DX, then Wilder-smooths DX into ADX. Warm-up bars (the
 * first {@code ~2 * period}) are {@link Double#NaN}. The +DI/-DI series are returned
 * alongside ADX for callers (and tests) that need trend direction, not just strength.
 */
public final class Adx {

    private Adx() {
    }

    /** ADX together with the +DI and -DI series it was built from (all aligned to the input). */
    public static final class Result {
        public final double[] adx;
        public final double[] plusDi;
        public final double[] minusDi;

        Result(double[] adx, double[] plusDi, double[] minusDi) {
            this.adx = adx;
            this.plusDi = plusDi;
            this.minusDi = minusDi;
        }
    }

    /** Convenience returning only the ADX series. */
    public static double[] of(double[] high, double[] low, double[] close, int period) {
        return compute(high, low, close, period).adx;
    }

    public static Result compute(double[] high, double[] low, double[] close, int period) {
        if (period <= 0) {
            throw new IllegalArgumentException("period must be greater than zero: " + period);
        }
        int n = high.length;
        double[] adx = new double[n];
        double[] plusDi = new double[n];
        double[] minusDi = new double[n];
        Arrays.fill(adx, Double.NaN);
        Arrays.fill(plusDi, Double.NaN);
        Arrays.fill(minusDi, Double.NaN);

        int first = 0;
        while (first < n && (Double.isNaN(high[first]) || Double.isNaN(low[first]) || Double.isNaN(close[first]))) {
            first++;
        }
        // Need the seed window for DI (period) plus the ADX seed window (period) on top.
        if (first + 2 * period - 1 >= n) {
            return new Result(adx, plusDi, minusDi);
        }

        double[] tr = new double[n];
        double[] plusDm = new double[n];
        double[] minusDm = new double[n];
        for (int i = first + 1; i < n; i++) {
            double up = high[i] - high[i - 1];
            double down = low[i - 1] - low[i];
            plusDm[i] = (up > down && up > 0) ? up : 0.0;
            minusDm[i] = (down > up && down > 0) ? down : 0.0;
            double pc = close[i - 1];
            tr[i] = Math.max(high[i] - low[i], Math.max(Math.abs(high[i] - pc), Math.abs(low[i] - pc)));
        }

        // Wilder seed sums over the first `period` bars after `first`.
        double trS = 0.0;
        double pdmS = 0.0;
        double mdmS = 0.0;
        for (int i = first + 1; i <= first + period; i++) {
            trS += tr[i];
            pdmS += plusDm[i];
            mdmS += minusDm[i];
        }

        double[] dx = new double[n];
        Arrays.fill(dx, Double.NaN);
        int diStart = first + period; // first index with smoothed DI/DX available
        for (int i = diStart; i < n; i++) {
            if (i > diStart) {
                trS = trS - trS / period + tr[i];
                pdmS = pdmS - pdmS / period + plusDm[i];
                mdmS = mdmS - mdmS / period + minusDm[i];
            }
            double pdi = trS == 0.0 ? 0.0 : 100.0 * pdmS / trS;
            double mdi = trS == 0.0 ? 0.0 : 100.0 * mdmS / trS;
            plusDi[i] = pdi;
            minusDi[i] = mdi;
            double sum = pdi + mdi;
            dx[i] = sum == 0.0 ? 0.0 : 100.0 * Math.abs(pdi - mdi) / sum;
        }

        // ADX: seed as the average of the first `period` DX values, then Wilder-smooth.
        int adxSeed = diStart + period - 1;
        double dxSum = 0.0;
        for (int i = diStart; i <= adxSeed; i++) {
            dxSum += dx[i];
        }
        adx[adxSeed] = dxSum / period;
        for (int i = adxSeed + 1; i < n; i++) {
            adx[i] = (adx[i - 1] * (period - 1) + dx[i]) / period;
        }
        return new Result(adx, plusDi, minusDi);
    }
}
