package com.rotation.config;

import java.util.Properties;

/** Strategy-owned ranking, cadence, sizing, stop, and sector-diversification parameters. */
public final class MomentumConfig {

    private final int lookbackDays;
    private final int topN;
    private final int exitN;
    private final double capitalPerStock;
    private final double monthlyContribution;
    private final double stopLossPct;
    private final double trailingStopPct;
    private final int minHistoryDays;
    private final String allocationMode;
    private final String rebalanceMode;
    private final int maxPerSector;

    MomentumConfig(Properties properties) {
        lookbackDays = integer(properties, "momentum.lookback.days", 30);
        topN = integer(properties, "momentum.top.n", 1);
        exitN = integer(properties, "momentum.exit.n", 0);
        capitalPerStock = decimal(properties, "momentum.capital.per.stock", 100000.0);
        monthlyContribution = decimal(properties, "momentum.monthly.contribution", 0.0);
        stopLossPct = decimal(properties, "momentum.stop.loss.pct", 0.0);
        trailingStopPct = decimal(properties, "momentum.trailing.stop.pct", 0.0);
        minHistoryDays = integer(properties, "momentum.min.history.days", 2);
        allocationMode = value(properties, "momentum.allocation.mode", "compound");
        rebalanceMode = value(properties, "momentum.rebalance.mode", "monthly_twice");
        maxPerSector = integer(properties, "momentum.max.per.sector", 0);
        validate();
    }

    private void validate() {
        if (topN <= 0) {
            throw new IllegalArgumentException("momentum.top.n must be greater than zero.");
        }
        if (exitN != 0 && exitN < topN) {
            throw new IllegalArgumentException(
                    "momentum.exit.n must be zero (disabled) or greater than or equal to momentum.top.n.");
        }
        if (lookbackDays <= 0) {
            throw new IllegalArgumentException("momentum.lookback.days must be greater than zero.");
        }
        if (capitalPerStock <= 0) {
            throw new IllegalArgumentException("momentum.capital.per.stock must be greater than zero.");
        }
        if (monthlyContribution < 0) {
            throw new IllegalArgumentException("momentum.monthly.contribution must be zero or positive.");
        }
        if (stopLossPct < 0 || stopLossPct >= 100) {
            throw new IllegalArgumentException("momentum.stop.loss.pct must be in [0, 100).");
        }
        if (trailingStopPct < 0 || trailingStopPct >= 100) {
            throw new IllegalArgumentException("momentum.trailing.stop.pct must be in [0, 100).");
        }
        if (maxPerSector < 0) {
            throw new IllegalArgumentException("momentum.max.per.sector must be zero or positive.");
        }
        if (!allocationMode.equals("compound") && !allocationMode.equals("fixed_principal")) {
            throw new IllegalArgumentException(
                    "momentum.allocation.mode must be 'compound' or 'fixed_principal'.");
        }
        if (!rebalanceMode.equals("monthly") && !rebalanceMode.equals("weekly")
                && !rebalanceMode.equals("monthly_twice")) {
            throw new IllegalArgumentException(
                    "momentum.rebalance.mode must be 'monthly', 'weekly', or 'monthly_twice'.");
        }
    }

    public int lookbackDays() { return lookbackDays; }
    public int topN() { return topN; }
    public int exitN() { return exitN; }
    public double capitalPerStock() { return capitalPerStock; }
    public double monthlyContribution() { return monthlyContribution; }
    public double stopLossPct() { return stopLossPct; }
    public double trailingStopPct() { return trailingStopPct; }
    public int minHistoryDays() { return minHistoryDays; }
    public String allocationMode() { return allocationMode; }
    public String rebalanceMode() { return rebalanceMode; }
    public int maxPerSector() { return maxPerSector; }

    public int effectiveExitN() {
        return exitN <= 0 ? topN : exitN;
    }

    public int effectiveMinHistoryDays() {
        return Math.max(minHistoryDays, lookbackDays);
    }

    public double effectiveInitialCapital() {
        return effectiveExitN() * capitalPerStock;
    }

    private static String value(Properties properties, String key, String fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : value.trim();
    }

    private static int integer(Properties properties, String key, int fallback) {
        String value = value(properties, key, "");
        return value.isBlank() ? fallback : Integer.parseInt(value);
    }

    private static double decimal(Properties properties, String key, double fallback) {
        String value = value(properties, key, "");
        return value.isBlank() ? fallback : Double.parseDouble(value);
    }
}
