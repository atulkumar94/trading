package com.rotation.strategy.breakout;

import java.util.Arrays;

import com.rotation.strategy.breakout.indicator.Adx;
import com.rotation.strategy.breakout.indicator.Atr;
import com.rotation.strategy.breakout.indicator.Sma;

/**
 * Entry signals for one symbol. All indicator series (fast/mid/slow SMAs, ATR, ADX,
 * trailing average volume and traded value) are computed once in the constructor, so
 * the per-bar checks during a backtest are O(1) lookups — no indicator is recomputed
 * inside a loop.
 *
 * <p>No look-ahead: every value at bar {@code t} uses data up to and including {@code t}
 * only; the engine executes the resulting trade at {@code t + 1}'s open. The entry is a
 * close-cross above the fast SMA, gated by the toggleable trend / ADX / volume /
 * liquidity filters. Insufficient history (NaN indicators) fails the entry rather than
 * throwing, so the 200-bar warm-up and any data gaps are handled naturally.
 */
public final class BreakoutSignals {

    private final BreakoutConfig cfg;
    private final double[] close;
    private final double[] volume;
    private final double[] smaFast;
    private final double[] smaMid;
    private final double[] smaSlow;
    private final double[] atr;
    private final double[] adx;
    private final double[] avgVolumePrior;
    private final double[] avgTradedValue;

    public BreakoutSignals(double[] high, double[] low, double[] close, double[] volume, BreakoutConfig cfg) {
        this.cfg = cfg;
        this.close = close;
        this.volume = volume;
        this.smaFast = Sma.of(close, cfg.smaFastPeriod());
        this.smaMid = Sma.of(close, cfg.smaMidPeriod());
        this.smaSlow = Sma.of(close, cfg.smaSlowPeriod());
        this.atr = Atr.of(high, low, close, cfg.atrPeriod());
        this.adx = Adx.of(high, low, close, cfg.adxPeriod());
        this.avgVolumePrior = trailingMeanPrior(volume, cfg.volumeAvgDays());
        this.avgTradedValue = trailingMeanValue(close, volume, cfg.liquidityAvgDays());
    }

    /** True when the close crosses above the fast SMA on bar {@code t} (no off-by-one). */
    public boolean crossUp(int t) {
        if (t < 1) {
            return false;
        }
        double fPrev = smaFast[t - 1];
        double fNow = smaFast[t];
        double cPrev = close[t - 1];
        double cNow = close[t];
        if (Double.isNaN(fPrev) || Double.isNaN(fNow) || Double.isNaN(cPrev) || Double.isNaN(cNow)) {
            return false;
        }
        return cPrev <= fPrev && cNow > fNow;
    }

    /** Full entry decision on bar {@code t}: cross-up plus every enabled filter. */
    public boolean isEntry(int t) {
        if (!crossUp(t)) {
            return false;
        }
        double midThen = t - cfg.smaMidSlopeDays() >= 0 ? smaMid[t - cfg.smaMidSlopeDays()] : Double.NaN;
        return trendFilter(close[t], smaMid[t], smaSlow[t], midThen, cfg.filterTrend())
                && adxFilter(adx[t], cfg.adxMin(), cfg.filterAdx())
                && volumeFilter(volume[t], avgVolumePrior[t], cfg.filterVolume())
                && liquidityFilter(avgTradedValue[t], cfg.liquidityMinValue(), cfg.filterLiquidity());
    }

    /** Relative-strength score (trailing return) used to rank competing signals; NaN when too new. */
    public double rankScore(int t) {
        int from = t - cfg.rankLookbackDays();
        if (from < 0) {
            return Double.NaN;
        }
        double past = close[from];
        double now = close[t];
        if (Double.isNaN(past) || Double.isNaN(now) || past <= 0.0) {
            return Double.NaN;
        }
        return now / past - 1.0;
    }

    public double atr(int t) {
        return atr[t];
    }

    public double adx(int t) {
        return adx[t];
    }

    public double smaFast(int t) {
        return smaFast[t];
    }

    public double smaMid(int t) {
        return smaMid[t];
    }

    public double smaSlow(int t) {
        return smaSlow[t];
    }

    public double close(int t) {
        return close[t];
    }

    public double volume(int t) {
        return volume[t];
    }

    public double avgVolume(int t) {
        return avgVolumePrior[t];
    }

    public double avgTradedValue(int t) {
        return avgTradedValue[t];
    }

    /** Filter 1 (trend) outcome on bar {@code t}; true when the filter is disabled. */
    public boolean trendOk(int t) {
        double midThen = t - cfg.smaMidSlopeDays() >= 0 ? smaMid[t - cfg.smaMidSlopeDays()] : Double.NaN;
        return trendFilter(close[t], smaMid[t], smaSlow[t], midThen, cfg.filterTrend());
    }

    /** Filter 2 (ADX) outcome on bar {@code t}; true when the filter is disabled. */
    public boolean adxOk(int t) {
        return adxFilter(adx[t], cfg.adxMin(), cfg.filterAdx());
    }

    /** Filter 3 (volume) outcome on bar {@code t}; true when the filter is disabled. */
    public boolean volumeOk(int t) {
        return volumeFilter(volume[t], avgVolumePrior[t], cfg.filterVolume());
    }

    /** Filter 5 (liquidity) outcome on bar {@code t}; true when the filter is disabled. */
    public boolean liquidityOk(int t) {
        return liquidityFilter(avgTradedValue[t], cfg.liquidityMinValue(), cfg.filterLiquidity());
    }

    // --- Filters. Each returns true when disabled, so a toggle flips the signal as expected. ---

    /** Filter 1: close above the mid and slow SMAs, and the mid SMA rising over the slope window. */
    static boolean trendFilter(double close, double smaMid, double smaSlow, double smaMidThen, boolean enabled) {
        if (!enabled) {
            return true;
        }
        if (Double.isNaN(close) || Double.isNaN(smaMid) || Double.isNaN(smaSlow) || Double.isNaN(smaMidThen)) {
            return false;
        }
        return close > smaMid && close > smaSlow && smaMid > smaMidThen;
    }

    /** Filter 2: trend strength above the minimum ADX. */
    static boolean adxFilter(double adx, double min, boolean enabled) {
        if (!enabled) {
            return true;
        }
        return !Double.isNaN(adx) && adx > min;
    }

    /** Filter 3: the cross-day volume exceeds the trailing average volume. */
    static boolean volumeFilter(double volume, double avgVolume, boolean enabled) {
        if (!enabled) {
            return true;
        }
        return !Double.isNaN(volume) && !Double.isNaN(avgVolume) && volume > avgVolume;
    }

    /** Filter 5: the trailing average traded value clears the liquidity floor. */
    static boolean liquidityFilter(double avgTradedValue, double minValue, boolean enabled) {
        if (!enabled) {
            return true;
        }
        return !Double.isNaN(avgTradedValue) && avgTradedValue > minValue;
    }

    /** Mean of the {@code period} values strictly before {@code t} (baseline excluding the cross day). */
    private static double[] trailingMeanPrior(double[] values, int period) {
        int n = values.length;
        double[] out = new double[n];
        Arrays.fill(out, Double.NaN);
        for (int t = period; t < n; t++) {
            double sum = 0.0;
            boolean complete = true;
            for (int k = t - period; k <= t - 1; k++) {
                if (Double.isNaN(values[k])) {
                    complete = false;
                    break;
                }
                sum += values[k];
            }
            if (complete) {
                out[t] = sum / period;
            }
        }
        return out;
    }

    /** Mean traded value (close * volume) over the {@code period} sessions ending at {@code t}. */
    private static double[] trailingMeanValue(double[] close, double[] volume, int period) {
        int n = close.length;
        double[] out = new double[n];
        Arrays.fill(out, Double.NaN);
        for (int t = period - 1; t < n; t++) {
            double sum = 0.0;
            boolean complete = true;
            for (int k = t - period + 1; k <= t; k++) {
                if (Double.isNaN(close[k]) || Double.isNaN(volume[k])) {
                    complete = false;
                    break;
                }
                sum += close[k] * volume[k];
            }
            if (complete) {
                out[t] = sum / period;
            }
        }
        return out;
    }
}
