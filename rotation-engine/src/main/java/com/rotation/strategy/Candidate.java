package com.rotation.strategy;

/**
 * One scored, eligible symbol produced by momentum ranking at a signal
 * date. The ranking is strategy-defined: {@code score} is whatever the strategy
 * ranks by (higher is better). For the momentum strategy it is the trailing
 * lookback return in percent, with {@code referencePrice}/{@code currentPrice}
 * being the lookback-window endpoints that produced it. New strategies are free
 * to populate these fields with their own interpretation (e.g. a composite score
 * with the raw endpoints that back it), which keeps the performance and lookback
 * reports working unchanged.
 */
public final class Candidate {

    public final String symbol;
    /** Strategy score used for ranking (desc). Momentum: trailing lookback return %. */
    public final double score;
    /** Price at the start of the scoring window (momentum: lookback-day close). */
    public final double referencePrice;
    /** Price at the signal date (momentum: signal-day close). */
    public final double currentPrice;
    /** Tracked daily bars behind this symbol at the signal date (eligibility count). */
    public final int historyDays;

    public Candidate(String symbol, double score, double referencePrice, double currentPrice, int historyDays) {
        this.symbol = symbol;
        this.score = score;
        this.referencePrice = referencePrice;
        this.currentPrice = currentPrice;
        this.historyDays = historyDays;
    }
}
