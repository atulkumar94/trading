package com.rotation.strategy.breakout;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Tunables for the 10 DMA breakout strategy, parsed from the same
 * {@code .properties} file as the rest of the engine (keys are prefixed
 * {@code breakout.*}). Every threshold in the strategy comes from here so no value
 * is hard-coded. The strategy is self-contained, so it owns its own config object
 * rather than widening the shared {@link com.rotation.config.RotationConfig}.
 */
public final class BreakoutConfig {

    private final int smaFastPeriod;
    private final int smaMidPeriod;
    private final int smaSlowPeriod;
    private final int smaMidSlopeDays;
    private final int adxPeriod;
    private final double adxMin;
    private final int atrPeriod;
    private final int volumeAvgDays;
    private final int liquidityAvgDays;
    private final double liquidityMinValue;
    private final int rankLookbackDays;
    private final boolean filterTrend;
    private final boolean filterAdx;
    private final boolean filterVolume;
    private final boolean filterLiquidity;
    private final int exitSmaSlowPeriod;
    private final double exitBelowPct;
    private final int exitConsecutiveBelow;
    private final double atrTrailMultiple;
    private final int hardStopLookback;
    private final double hardStopAtrMultiple;
    private final boolean partialEnabled;
    private final double partialRMultiple;
    private final double partialFraction;
    private final boolean exitCloseBelowFast;
    private final boolean exitFarOrConsecutive;
    private final boolean exitFastBelowSlow;
    private final boolean exitAtrTrailing;
    private final boolean exitHardStop;
    private final double capital;
    private final int maxPositions;
    private final double maxWeightPct;
    private final double costBps;

    private BreakoutConfig(Properties p) {
        this.smaFastPeriod = intProp(p, "breakout.sma.fast", 10);
        this.smaMidPeriod = intProp(p, "breakout.sma.trend.mid", 50);
        this.smaSlowPeriod = intProp(p, "breakout.sma.trend.slow", 200);
        this.smaMidSlopeDays = intProp(p, "breakout.sma.trend.slope.days", 5);
        this.adxPeriod = intProp(p, "breakout.adx.period", 14);
        this.adxMin = doubleProp(p, "breakout.adx.min", 22.0);
        this.atrPeriod = intProp(p, "breakout.atr.period", 14);
        this.volumeAvgDays = intProp(p, "breakout.volume.avg.days", 20);
        this.liquidityAvgDays = intProp(p, "breakout.liquidity.avg.days", 20);
        this.liquidityMinValue = doubleProp(p, "breakout.liquidity.min.value", 50_000_000.0);
        this.rankLookbackDays = intProp(p, "breakout.rank.lookback.days", 126);
        this.filterTrend = boolProp(p, "breakout.filter.trend", true);
        this.filterAdx = boolProp(p, "breakout.filter.adx", true);
        this.filterVolume = boolProp(p, "breakout.filter.volume", true);
        this.filterLiquidity = boolProp(p, "breakout.filter.liquidity", true);
        this.exitSmaSlowPeriod = intProp(p, "breakout.exit.sma.slow", 20);
        this.exitBelowPct = doubleProp(p, "breakout.exit.below.pct", 1.5);
        this.exitConsecutiveBelow = intProp(p, "breakout.exit.consec.below", 2);
        this.atrTrailMultiple = doubleProp(p, "breakout.atr.trail.mult", 2.5);
        this.hardStopLookback = intProp(p, "breakout.hard.stop.lookback", 10);
        this.hardStopAtrMultiple = doubleProp(p, "breakout.hard.stop.atr.mult", 2.0);
        this.partialEnabled = boolProp(p, "breakout.partial.enabled", false);
        this.partialRMultiple = doubleProp(p, "breakout.partial.r.multiple", 2.0);
        this.partialFraction = doubleProp(p, "breakout.partial.fraction", 0.5);
        this.exitCloseBelowFast = boolProp(p, "breakout.exit.close.below.fast", true);
        this.exitFarOrConsecutive = boolProp(p, "breakout.exit.far.or.consec", true);
        this.exitFastBelowSlow = boolProp(p, "breakout.exit.fast.below.slow", true);
        this.exitAtrTrailing = boolProp(p, "breakout.exit.atr.trail", true);
        this.exitHardStop = boolProp(p, "breakout.exit.hard.stop", true);
        this.capital = doubleProp(p, "breakout.capital", 1_000_000.0);
        this.maxPositions = intProp(p, "breakout.max.positions", 10);
        this.maxWeightPct = doubleProp(p, "breakout.max.weight.pct", 15.0);
        this.costBps = doubleProp(p, "breakout.cost.bps", 0.0);
        validate();
    }

    public static BreakoutConfig fromProperties(Properties props) {
        return new BreakoutConfig(props);
    }

    public static BreakoutConfig load(Path configFile) {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(configFile)) {
            props.load(in);
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to read config file: " + configFile, e);
        }
        return new BreakoutConfig(props);
    }

    private void validate() {
        if (smaFastPeriod <= 0 || smaMidPeriod <= 0 || smaSlowPeriod <= 0) {
            throw new IllegalArgumentException("breakout SMA periods must be greater than zero.");
        }
        if (smaMidSlopeDays < 0) {
            throw new IllegalArgumentException("breakout.sma.trend.slope.days must be zero or positive.");
        }
        if (adxPeriod <= 0 || atrPeriod <= 0) {
            throw new IllegalArgumentException("breakout ADX/ATR periods must be greater than zero.");
        }
        if (volumeAvgDays <= 0 || liquidityAvgDays <= 0 || rankLookbackDays <= 0) {
            throw new IllegalArgumentException("breakout averaging/ranking windows must be greater than zero.");
        }
        if (liquidityMinValue < 0) {
            throw new IllegalArgumentException("breakout.liquidity.min.value must be zero or positive.");
        }
        if (exitSmaSlowPeriod <= 0) {
            throw new IllegalArgumentException("breakout.exit.sma.slow must be greater than zero.");
        }
        if (hardStopLookback <= 0) {
            throw new IllegalArgumentException("breakout.hard.stop.lookback must be greater than zero.");
        }
        if (exitConsecutiveBelow <= 0) {
            throw new IllegalArgumentException("breakout.exit.consec.below must be greater than zero.");
        }
        if (exitBelowPct < 0 || atrTrailMultiple < 0 || hardStopAtrMultiple < 0) {
            throw new IllegalArgumentException("breakout exit distances must be zero or positive.");
        }
        if (partialRMultiple <= 0) {
            throw new IllegalArgumentException("breakout.partial.r.multiple must be greater than zero.");
        }
        if (partialFraction <= 0 || partialFraction >= 1) {
            throw new IllegalArgumentException("breakout.partial.fraction must be between 0 and 1 (exclusive).");
        }
        if (capital <= 0) {
            throw new IllegalArgumentException("breakout.capital must be greater than zero.");
        }
        if (maxPositions <= 0) {
            throw new IllegalArgumentException("breakout.max.positions must be greater than zero.");
        }
        if (maxWeightPct <= 0 || maxWeightPct > 100) {
            throw new IllegalArgumentException("breakout.max.weight.pct must be in (0, 100].");
        }
        if (costBps < 0) {
            throw new IllegalArgumentException("breakout.cost.bps must be zero or positive.");
        }
    }

    public int smaFastPeriod() {
        return smaFastPeriod;
    }

    public int smaMidPeriod() {
        return smaMidPeriod;
    }

    public int smaSlowPeriod() {
        return smaSlowPeriod;
    }

    public int smaMidSlopeDays() {
        return smaMidSlopeDays;
    }

    public int adxPeriod() {
        return adxPeriod;
    }

    public double adxMin() {
        return adxMin;
    }

    public int atrPeriod() {
        return atrPeriod;
    }

    public int volumeAvgDays() {
        return volumeAvgDays;
    }

    public int liquidityAvgDays() {
        return liquidityAvgDays;
    }

    /** Minimum 20-day average traded value (in rupees) for a name to be liquid enough. */
    public double liquidityMinValue() {
        return liquidityMinValue;
    }

    /** Trailing window (sessions) used to score relative strength when ranking signals. */
    public int rankLookbackDays() {
        return rankLookbackDays;
    }

    public boolean filterTrend() {
        return filterTrend;
    }

    public boolean filterAdx() {
        return filterAdx;
    }

    public boolean filterVolume() {
        return filterVolume;
    }

    public boolean filterLiquidity() {
        return filterLiquidity;
    }

    /** Slow SMA (default 20) whose cross-under by the fast SMA triggers exit C. */
    public int exitSmaSlowPeriod() {
        return exitSmaSlowPeriod;
    }

    /** Exit B distance: how far below the fast SMA (percent) the close may fall before exiting. */
    public double exitBelowPct() {
        return exitBelowPct;
    }

    /** Exit B: number of consecutive closes below the fast SMA that force an exit. */
    public int exitConsecutiveBelow() {
        return exitConsecutiveBelow;
    }

    /** Exit D: ATR multiple for the trailing stop below the highest close since entry. */
    public double atrTrailMultiple() {
        return atrTrailMultiple;
    }

    /** Exit E: lookback (sessions) for the lowest-low leg of the initial hard stop. */
    public int hardStopLookback() {
        return hardStopLookback;
    }

    /** Exit E: ATR multiple for the entry-price leg of the initial hard stop. */
    public double hardStopAtrMultiple() {
        return hardStopAtrMultiple;
    }

    /** Whether to book a partial profit at the R-multiple target and trail the rest. */
    public boolean partialEnabled() {
        return partialEnabled;
    }

    /** Profit target as a multiple of initial risk (R) at which the partial is booked. */
    public double partialRMultiple() {
        return partialRMultiple;
    }

    /** Fraction of the position sold when the profit target is reached. */
    public double partialFraction() {
        return partialFraction;
    }

    /** Exit A toggle: sell when the close is below the fast SMA. */
    public boolean exitCloseBelowFast() {
        return exitCloseBelowFast;
    }

    /** Exit B toggle: sell when the close is far below the fast SMA or stays below it. */
    public boolean exitFarOrConsecutive() {
        return exitFarOrConsecutive;
    }

    /** Exit C toggle: sell when the fast SMA crosses below the slow SMA. */
    public boolean exitFastBelowSlow() {
        return exitFastBelowSlow;
    }

    /** Exit D toggle: ATR trailing stop below the highest close since entry. */
    public boolean exitAtrTrailing() {
        return exitAtrTrailing;
    }

    /** Exit E toggle: the initial hard stop. */
    public boolean exitHardStop() {
        return exitHardStop;
    }

    /** Starting portfolio capital for the breakout backtest. */
    public double capital() {
        return capital;
    }

    /** Maximum number of positions held at once. */
    public int maxPositions() {
        return maxPositions;
    }

    /** Per-position cap as a percent of account equity at entry. */
    public double maxWeightPct() {
        return maxWeightPct;
    }

    /** Per-side transaction cost in basis points, baked into the fill price. */
    public double costBps() {
        return costBps;
    }

    private static int intProp(Properties props, String key, int def) {
        String value = props.getProperty(key);
        return value == null || value.isBlank() ? def : Integer.parseInt(value.trim());
    }

    private static double doubleProp(Properties props, String key, double def) {
        String value = props.getProperty(key);
        return value == null || value.isBlank() ? def : Double.parseDouble(value.trim());
    }

    private static boolean boolProp(Properties props, String key, boolean def) {
        String value = props.getProperty(key);
        return value == null || value.isBlank() ? def : Boolean.parseBoolean(value.trim());
    }
}
