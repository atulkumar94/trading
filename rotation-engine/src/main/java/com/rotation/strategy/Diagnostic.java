package com.rotation.strategy;

import java.time.LocalDate;

/** Ranked candidates retained for legacy performance and lookback report projections. */
public final class Diagnostic {

    public enum Kind { REBALANCE, LOOKBACK }

    public final Kind kind;
    public final LocalDate signalDate;
    public final LocalDate lookbackStart;
    public final int signalIndex;
    public final int rank;
    public final String symbol;
    public final double referencePrice;
    public final double currentPrice;
    public final double score;
    public final int historyDays;
    public final boolean selected;
    public final boolean coreSelected;
    public final boolean rebalanceEvent;

    public Diagnostic(Kind kind, LocalDate signalDate, LocalDate lookbackStart,
                      int signalIndex, int rank, String symbol,
                      double referencePrice, double currentPrice, double score, int historyDays,
                      boolean selected, boolean coreSelected, boolean rebalanceEvent) {
        this.kind = kind;
        this.signalDate = signalDate;
        this.lookbackStart = lookbackStart;
        this.signalIndex = signalIndex;
        this.rank = rank;
        this.symbol = symbol;
        this.referencePrice = referencePrice;
        this.currentPrice = currentPrice;
        this.score = score;
        this.historyDays = historyDays;
        this.selected = selected;
        this.coreSelected = coreSelected;
        this.rebalanceEvent = rebalanceEvent;
    }
}
