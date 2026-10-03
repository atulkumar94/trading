package com.rotation.strategy.breakout;

import java.util.OptionalDouble;

import com.rotation.strategy.breakout.indicator.Atr;
import com.rotation.strategy.breakout.indicator.RollingLow;
import com.rotation.strategy.breakout.indicator.Sma;

/**
 * Exit rules for one symbol's open breakout position. Like {@link BreakoutSignals} the
 * indicator series (fast/slow SMA, ATR, trailing low) are computed once in the
 * constructor; every per-bar check is an O(1) lookup. All methods are pure: the caller
 * (the execution engine) owns the position state — entry price, highest close since
 * entry, consecutive closes below the fast SMA — and passes it in, so each rule can be
 * unit-tested in isolation.
 *
 * <p>The close-based exits (A–D) are evaluated on bar {@code t}'s close and the engine
 * fills them at {@code t + 1}'s open. The initial hard stop (E) is an intraday level:
 * a gap-down below it fills at the open, otherwise it fills at the stop price. Each exit
 * is independently toggleable so a single rule can be isolated for ablation.
 */
public final class BreakoutExits {

    /** Which rule closed (or partially closed) a position. */
    public enum ExitReason {
        CLOSE_BELOW_FAST,          // A
        FAR_OR_CONSECUTIVE_BELOW,  // B
        FAST_CROSS_BELOW_SLOW,     // C
        ATR_TRAILING_STOP,         // D
        HARD_STOP                  // E
    }

    private final BreakoutConfig cfg;
    private final double[] close;
    private final double[] smaFast;
    private final double[] smaSlow;
    private final double[] atr;
    private final double[] lowestLow;

    public BreakoutExits(double[] high, double[] low, double[] close, BreakoutConfig cfg) {
        this.cfg = cfg;
        this.close = close;
        this.smaFast = Sma.of(close, cfg.smaFastPeriod());
        this.smaSlow = Sma.of(close, cfg.exitSmaSlowPeriod());
        this.atr = Atr.of(high, low, close, cfg.atrPeriod());
        this.lowestLow = RollingLow.of(low, cfg.hardStopLookback());
    }

    /** True when bar {@code t}'s close is below the fast SMA (engine uses this to count consecutives). */
    public boolean isBelowFast(int t) {
        double c = close[t];
        double f = smaFast[t];
        return !Double.isNaN(c) && !Double.isNaN(f) && c < f;
    }

    /**
     * First close-based exit (A–D, in priority order) that fires on bar {@code t}, or
     * {@code null} if the position is held. {@code consecutiveBelowCount} is the number
     * of consecutive closes up to and including {@code t} that sit below the fast SMA.
     */
    public ExitReason closeExit(int t, double highestCloseSinceEntry, int consecutiveBelowCount) {
        double c = close[t];
        double fast = smaFast[t];

        if (cfg.exitCloseBelowFast() && !Double.isNaN(c) && !Double.isNaN(fast) && c < fast) {
            return ExitReason.CLOSE_BELOW_FAST;
        }
        if (cfg.exitFarOrConsecutive() && !Double.isNaN(c) && !Double.isNaN(fast)) {
            boolean farBelow = c < fast * (1.0 - cfg.exitBelowPct() / 100.0);
            boolean stayedBelow = consecutiveBelowCount >= cfg.exitConsecutiveBelow();
            if (farBelow || stayedBelow) {
                return ExitReason.FAR_OR_CONSECUTIVE_BELOW;
            }
        }
        if (cfg.exitFastBelowSlow() && t >= 1) {
            double fPrev = smaFast[t - 1];
            double sPrev = smaSlow[t - 1];
            double sNow = smaSlow[t];
            if (!Double.isNaN(fast) && !Double.isNaN(sNow) && !Double.isNaN(fPrev) && !Double.isNaN(sPrev)
                    && fPrev >= sPrev && fast < sNow) {
                return ExitReason.FAST_CROSS_BELOW_SLOW;
            }
        }
        if (cfg.exitAtrTrailing() && !Double.isNaN(c) && !Double.isNaN(atr[t])
                && !Double.isNaN(highestCloseSinceEntry)) {
            if (c < highestCloseSinceEntry - cfg.atrTrailMultiple() * atr[t]) {
                return ExitReason.ATR_TRAILING_STOP;
            }
        }
        return null;
    }

    /**
     * The initial hard-stop price fixed at entry: the lower of the trailing low at entry
     * and {@code entryPrice - mult * ATR(entry)}. {@link Double#NaN} when the stop is
     * disabled or neither leg has enough history.
     */
    public double hardStopLevel(int entryIndex, double entryPrice) {
        if (!cfg.exitHardStop()) {
            return Double.NaN;
        }
        double lowLeg = lowestLow[entryIndex];
        double a = atr[entryIndex];
        double atrLeg = Double.isNaN(a) ? Double.NaN : entryPrice - cfg.hardStopAtrMultiple() * a;

        if (Double.isNaN(lowLeg) && Double.isNaN(atrLeg)) {
            return Double.NaN;
        }
        if (Double.isNaN(lowLeg)) {
            return atrLeg;
        }
        if (Double.isNaN(atrLeg)) {
            return lowLeg;
        }
        return Math.min(lowLeg, atrLeg);
    }

    /**
     * Fill price if the hard stop is breached on a bar, else empty. A session that opens
     * at or below the stop fills at the open (gap-down); otherwise a session whose low
     * reaches the stop fills at the stop price.
     */
    public OptionalDouble hardStopFill(double stopLevel, double open, double low) {
        if (Double.isNaN(stopLevel)) {
            return OptionalDouble.empty();
        }
        if (!Double.isNaN(open) && open <= stopLevel) {
            return OptionalDouble.of(open);
        }
        if (!Double.isNaN(low) && low <= stopLevel) {
            return OptionalDouble.of(stopLevel);
        }
        return OptionalDouble.empty();
    }

    /** Profit target for the optional partial: {@code entry + R-multiple * (entry - initialStop)}. */
    public double profitTargetLevel(double entryPrice, double initialStopLevel) {
        if (Double.isNaN(initialStopLevel)) {
            return Double.NaN;
        }
        double risk = entryPrice - initialStopLevel;
        if (risk <= 0.0) {
            return Double.NaN;
        }
        return entryPrice + cfg.partialRMultiple() * risk;
    }

    /** True when bar {@code t}'s close has reached the profit target. */
    public boolean reachedProfitTarget(int t, double targetLevel) {
        return cfg.partialEnabled() && !Double.isNaN(targetLevel)
                && !Double.isNaN(close[t]) && close[t] >= targetLevel;
    }

    public double smaFast(int t) {
        return smaFast[t];
    }

    public double smaSlow(int t) {
        return smaSlow[t];
    }

    public double atr(int t) {
        return atr[t];
    }

    public double lowestLow(int t) {
        return lowestLow[t];
    }
}
