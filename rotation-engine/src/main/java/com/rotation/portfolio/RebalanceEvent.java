package com.rotation.portfolio;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable end-of-rebalance account event from which legacy report rows are projected. */
public final class RebalanceEvent {

    public final LocalDate date;
    public final LocalDate signalDate;
    public final LocalDate lookbackStart;
    public final LocalDate previousRebalanceDate;
    public final List<String> selectedSymbols;
    public final String entered;
    public final String exited;
    public final String held;
    public final int selectedCount;
    public final double portfolioValueBefore;
    public final double portfolioValueAfter;
    public final double accountEquity;
    public final double periodPnl;
    public final double capitalPerStock;
    public final double periodReturnPct;
    public final double contribution;
    public final Map<String, Double> holdings;
    public final double investedValue;
    public final double cash;
    public final double portfolioValue;
    public final double cumulativeReturnPct;

    public RebalanceEvent(LocalDate date, LocalDate signalDate, LocalDate lookbackStart,
                          LocalDate previousRebalanceDate, List<String> selectedSymbols,
                          String entered, String exited, String held, int selectedCount,
                          double portfolioValueBefore, double portfolioValueAfter,
                          double accountEquity, double periodPnl, double capitalPerStock,
                          double periodReturnPct, double contribution, Map<String, Double> holdings,
                          double investedValue, double cash, double portfolioValue,
                          double cumulativeReturnPct) {
        this.date = date;
        this.signalDate = signalDate;
        this.lookbackStart = lookbackStart;
        this.previousRebalanceDate = previousRebalanceDate;
        this.selectedSymbols = List.copyOf(selectedSymbols);
        this.entered = entered;
        this.exited = exited;
        this.held = held;
        this.selectedCount = selectedCount;
        this.portfolioValueBefore = portfolioValueBefore;
        this.portfolioValueAfter = portfolioValueAfter;
        this.accountEquity = accountEquity;
        this.periodPnl = periodPnl;
        this.capitalPerStock = capitalPerStock;
        this.periodReturnPct = periodReturnPct;
        this.contribution = contribution;
        this.holdings = Collections.unmodifiableMap(new LinkedHashMap<>(holdings));
        this.investedValue = investedValue;
        this.cash = cash;
        this.portfolioValue = portfolioValue;
        this.cumulativeReturnPct = cumulativeReturnPct;
    }
}