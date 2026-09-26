package com.rotation.model;

import java.time.LocalDate;

/** One row of the rebalance log (mirrors the Python RebalanceRecord). */
public final class RebalanceRecord {

    public final LocalDate date;            // execution (next open) date
    public final LocalDate signalDate;      // ranking close date
    public final double portfolioValueBefore;
    public final double portfolioValueAfter;
    public final double accountEquity;
    public final double periodPnl;
    public final int selectedCount;
    public final double capitalPerStock;
    public final String entered;
    public final String exited;
    public final String held;
    public final double periodReturnPct;

    public RebalanceRecord(LocalDate date, LocalDate signalDate, double portfolioValueBefore,
                           double portfolioValueAfter, double accountEquity, double periodPnl,
                           int selectedCount, double capitalPerStock, String entered, String exited,
                           String held, double periodReturnPct) {
        this.date = date;
        this.signalDate = signalDate;
        this.portfolioValueBefore = portfolioValueBefore;
        this.portfolioValueAfter = portfolioValueAfter;
        this.accountEquity = accountEquity;
        this.periodPnl = periodPnl;
        this.selectedCount = selectedCount;
        this.capitalPerStock = capitalPerStock;
        this.entered = entered;
        this.exited = exited;
        this.held = held;
        this.periodReturnPct = periodReturnPct;
    }
}
