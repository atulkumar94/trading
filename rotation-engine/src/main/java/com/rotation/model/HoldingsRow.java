package com.rotation.model;

import java.time.LocalDate;

/** Portfolio snapshot captured at each rebalance for the holdings sheet. */
public final class HoldingsRow {

    public final LocalDate date;
    public final LocalDate signalDate;
    public final int rebalanceNumber;
    public final int holdingsCount;
    public final String holdings;
    public final double investedValue;
    public final double cashBalance;
    public final double portfolioValue;
    public final double accountEquity;
    public final double periodReturnPct;
    public final double cumulativeReturnPct;

    public HoldingsRow(LocalDate date, LocalDate signalDate, int rebalanceNumber, int holdingsCount,
                       String holdings, double investedValue, double cashBalance, double portfolioValue,
                       double accountEquity, double periodReturnPct, double cumulativeReturnPct) {
        this.date = date;
        this.signalDate = signalDate;
        this.rebalanceNumber = rebalanceNumber;
        this.holdingsCount = holdingsCount;
        this.holdings = holdings;
        this.investedValue = investedValue;
        this.cashBalance = cashBalance;
        this.portfolioValue = portfolioValue;
        this.accountEquity = accountEquity;
        this.periodReturnPct = periodReturnPct;
        this.cumulativeReturnPct = cumulativeReturnPct;
    }
}
