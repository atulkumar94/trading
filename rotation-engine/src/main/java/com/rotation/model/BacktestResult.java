package com.rotation.model;

import java.util.List;

import com.rotation.portfolio.Fill;
import com.rotation.portfolio.Ledger;

/** Aggregated outputs from a rotation backtest run. */
public final class BacktestResult {

    private final List<RebalanceRecord> rebalances;
    private final List<EquityRow> equityCurve;
    private final List<PerformanceRow> performanceRows;
    private final List<LookbackRow> lookbackRows;
    private final List<HoldingsRow> holdingsRows;
    private final List<YearEndEquity> yearEndMarks;
    private final double initialCapital;
    private final Ledger ledger;

    public BacktestResult(List<RebalanceRecord> rebalances, List<EquityRow> equityCurve,
                          List<PerformanceRow> performanceRows, List<TradebookRow> tradebookRows,
                          List<LookbackRow> lookbackRows, List<HoldingsRow> holdingsRows,
                          List<YearEndEquity> yearEndMarks, double initialCapital) {
        this(rebalances, equityCurve, performanceRows, tradebookRows, lookbackRows, holdingsRows,
                yearEndMarks, initialCapital, List.of(), List.of());
    }

    public BacktestResult(List<RebalanceRecord> rebalances, List<EquityRow> equityCurve,
                          List<PerformanceRow> performanceRows, List<TradebookRow> tradebookRows,
                          List<LookbackRow> lookbackRows, List<HoldingsRow> holdingsRows,
                          List<YearEndEquity> yearEndMarks, double initialCapital,
                          List<Fill> fills, List<DailyMark> dailyMarks) {
        this(rebalances, equityCurve, performanceRows, tradebookRows, lookbackRows, holdingsRows,
                yearEndMarks, initialCapital, Ledger.from(fills, dailyMarks, tradebookRows));
    }

    public BacktestResult(List<RebalanceRecord> rebalances, List<EquityRow> equityCurve,
                          List<PerformanceRow> performanceRows, List<TradebookRow> tradebookRows,
                          List<LookbackRow> lookbackRows, List<HoldingsRow> holdingsRows,
                          List<YearEndEquity> yearEndMarks, double initialCapital, Ledger ledger) {
        if (ledger.tradebookRows().isEmpty() && !tradebookRows.isEmpty()) {
            tradebookRows.forEach(ledger::recordTradebookRow);
        }
        this.rebalances = rebalances;
        this.equityCurve = equityCurve;
        this.performanceRows = performanceRows;
        this.lookbackRows = lookbackRows;
        this.holdingsRows = holdingsRows;
        this.yearEndMarks = yearEndMarks;
        this.initialCapital = initialCapital;
        this.ledger = ledger;
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
        return ledger.tradebookRows();
    }

    public List<LookbackRow> lookbackRows() {
        return lookbackRows;
    }

    public List<HoldingsRow> holdingsRows() {
        return holdingsRows;
    }

    public List<YearEndEquity> yearEndMarks() {
        return yearEndMarks;
    }

    public double initialCapital() {
        return initialCapital;
    }

    /** Every position change in execution order, including re-weights (see {@link Fill}). */
    public List<Fill> fills() {
        return ledger.fills();
    }

    /** End-of-day engine state for every session from the trade start date. */
    public List<DailyMark> dailyMarks() {
        return ledger.dailyMarks();
    }

    public Ledger ledger() {
        return ledger;
    }
}
