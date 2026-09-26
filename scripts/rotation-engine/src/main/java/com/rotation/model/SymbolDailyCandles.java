package com.rotation.model;

import java.util.List;

/** All daily candles for one symbol, ordered by date ascending. */
public final class SymbolDailyCandles {

    private final String symbol;
    private final List<DailyCandle> candles;

    public SymbolDailyCandles(String symbol, List<DailyCandle> candles) {
        this.symbol = symbol;
        this.candles = candles;
    }

    public String symbol() {
        return symbol;
    }

    public List<DailyCandle> candles() {
        return candles;
    }
}
