package com.rotation.model;

import java.util.List;

/** Aggregated outputs from a rotation backtest run. */
public final class BacktestResult {

    private final List<RebalanceRecord> rebalances;
    private final List<EquityRow> equityCurve;
    private final List<PerformanceRow> performanceRows;
    private final List<TradebookRow> tradebookRows;
    private final List<LookbackRow> lookbackRows;
    private final List<HoldingsRow> holdingsRows;
    private final double initialCapital;

    public BacktestResult(List<RebalanceRecord> rebalances, List<EquityRow> equityCurve,
                          List<PerformanceRow> performanceRows, List<TradebookRow> tradebookRows,
                          List<LookbackRow> lookbackRows, List<HoldingsRow> holdingsRows,
                          double initialCapital) {
        this.rebalances = rebalances;
        this.equityCurve = equityCurve;
        this.performanceRows = performanceRows;
        this.tradebookRows = tradebookRows;
        this.lookbackRows = lookbackRows;
        this.holdingsRows = holdingsRows;
        this.initialCapital = initialCapital;
    }

    public List<RebalanceRecord> rebalances() {
        return rebalances;
    }

    public List<EquityRow> equityCurve() {
        return equityCurve;
    }

    public List<PerformanceRow> performanceRows() {
        return performanceRows;
    }

    public List<TradebookRow> tradebookRows() {
        return tradebookRows;
    }

    public List<LookbackRow> lookbackRows() {
        return lookbackRows;
    }

    public List<HoldingsRow> holdingsRows() {
        return holdingsRows;
    }

    public double initialCapital() {
        return initialCapital;
    }
}
