package com.rotation.model;

import java.time.LocalDate;

/** One row of the equity curve output. */
public final class EquityRow {

    public final LocalDate date;
    public final LocalDate signalDate;
    public final double portfolioValue;
    public final double accountEquity;
    public final double periodPnl;
    public final double capitalPerStock;
    public final String selectedSymbols;
    public final LocalDate lookbackStart;
    public final LocalDate previousRebalance;
    public final double contribution;

    public EquityRow(LocalDate date, LocalDate signalDate, double portfolioValue, double accountEquity,
                     double periodPnl, double capitalPerStock, String selectedSymbols,
                     LocalDate lookbackStart, LocalDate previousRebalance, double contribution) {
        this.date = date;
        this.signalDate = signalDate;
        this.portfolioValue = portfolioValue;
        this.accountEquity = accountEquity;
        this.periodPnl = periodPnl;
        this.capitalPerStock = capitalPerStock;
        this.selectedSymbols = selectedSymbols;
        this.lookbackStart = lookbackStart;
        this.previousRebalance = previousRebalance;
        this.contribution = contribution;
    }
}
