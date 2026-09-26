package com.rotation.model;

import java.time.LocalDate;

/** One ranked symbol within a rebalance's performance table. */
public final class PerformanceRow {

    public final int rebalanceNumber;
    public final LocalDate signalDate;
    public final LocalDate executionDate;
    public final LocalDate lookbackStart;
    public final int rank;
    public final String symbol;
    public final double lookbackPrice;
    public final double currentPrice;
    public final double lookbackReturnPct;
    public final int historyDays;
    public final boolean selected;

    public PerformanceRow(int rebalanceNumber, LocalDate signalDate, LocalDate executionDate,
                          LocalDate lookbackStart, int rank, String symbol, double lookbackPrice,
                          double currentPrice, double lookbackReturnPct, int historyDays, boolean selected) {
        this.rebalanceNumber = rebalanceNumber;
        this.signalDate = signalDate;
        this.executionDate = executionDate;
        this.lookbackStart = lookbackStart;
        this.rank = rank;
        this.symbol = symbol;
        this.lookbackPrice = lookbackPrice;
        this.currentPrice = currentPrice;
        this.lookbackReturnPct = lookbackReturnPct;
        this.historyDays = historyDays;
        this.selected = selected;
    }
}
