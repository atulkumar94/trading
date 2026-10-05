package com.rotation.portfolio;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Final close valuation after the last rebalance, used for the holdings report tail row. */
public final class FinalPortfolioMark {

    public final LocalDate date;
    public final int rebalanceNumber;
    public final Map<String, Double> holdings;
    public final double investedValue;
    public final double cash;
    public final double portfolioValue;
    public final double accountEquity;
    public final double periodReturnPct;
    public final double cumulativeReturnPct;

    public FinalPortfolioMark(LocalDate date, int rebalanceNumber, Map<String, Double> holdings,
                              double investedValue, double cash, double portfolioValue,
                              double accountEquity, double periodReturnPct, double cumulativeReturnPct) {
        this.date = date;
        this.rebalanceNumber = rebalanceNumber;
        this.holdings = Collections.unmodifiableMap(new LinkedHashMap<>(holdings));
        this.investedValue = investedValue;
        this.cash = cash;
        this.portfolioValue = portfolioValue;
        this.accountEquity = accountEquity;
        this.periodReturnPct = periodReturnPct;
        this.cumulativeReturnPct = cumulativeReturnPct;
    }
}