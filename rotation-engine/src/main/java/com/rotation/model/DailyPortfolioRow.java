package com.rotation.model;

import java.time.LocalDate;

/** End-of-day account valuation for one trading session (see {@link DailyMark} for timing). */
public final class DailyPortfolioRow {

    public final LocalDate date;
    public final int rebalanceNumber;
    public final int positions;
    public final double cash;                    // cash ledger: capital + contributions - buys + sells
    public final double investedValue;           // sum of quantity * adjusted close
    public final double accountEquity;           // engine mark-to-market equity
    public final double contribution;            // external cash credited this session
    public final double cumulativeContributions;
    public final double netCapital;              // initial capital + cumulative contributions
    public final double dailyPnl;                // equity change excluding this session's contribution
    public final Double dailyReturnPct;          // contribution-adjusted; null when undefined
    public final double twrIndex;                // chained (1 + daily return), 1.0 at inception
    public final double drawdownPct;             // twrIndex vs its running peak
    public final double realizedPnl;             // cumulative, average-cost method
    public final double unrealizedPnl;           // market value - average cost basis
    public final double totalPnl;                // equity - net capital
    public final int missingPrices;              // held positions with no close (valued at zero)
    public final double reconciliationDiff;      // equity - (cash + investedValue)

    public DailyPortfolioRow(LocalDate date, int rebalanceNumber, int positions, double cash,
                             double investedValue, double accountEquity, double contribution,
                             double cumulativeContributions, double netCapital, double dailyPnl,
                             Double dailyReturnPct, double twrIndex, double drawdownPct, double realizedPnl,
                             double unrealizedPnl, double totalPnl, int missingPrices,
                             double reconciliationDiff) {
        this.date = date;
        this.rebalanceNumber = rebalanceNumber;
        this.positions = positions;
        this.cash = cash;
        this.investedValue = investedValue;
        this.accountEquity = accountEquity;
        this.contribution = contribution;
        this.cumulativeContributions = cumulativeContributions;
        this.netCapital = netCapital;
        this.dailyPnl = dailyPnl;
        this.dailyReturnPct = dailyReturnPct;
        this.twrIndex = twrIndex;
        this.drawdownPct = drawdownPct;
        this.realizedPnl = realizedPnl;
        this.unrealizedPnl = unrealizedPnl;
        this.totalPnl = totalPnl;
        this.missingPrices = missingPrices;
        this.reconciliationDiff = reconciliationDiff;
    }
}
