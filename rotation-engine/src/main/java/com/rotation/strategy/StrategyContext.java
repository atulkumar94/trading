package com.rotation.strategy;

/** Non-market initialization parameters shared with a strategy for one run. */
public final class StrategyContext {

    public final int tradeStartIndex;
    public final int firstSignalIndex;
    public final int lastSignalIndex;
    public final int minHistory;

    public StrategyContext(int tradeStartIndex, int firstSignalIndex, int lastSignalIndex,
                           int minHistory) {
        this.tradeStartIndex = tradeStartIndex;
        this.firstSignalIndex = firstSignalIndex;
        this.lastSignalIndex = lastSignalIndex;
        this.minHistory = minHistory;
    }
}
