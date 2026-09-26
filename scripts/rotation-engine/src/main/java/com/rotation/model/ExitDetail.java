package com.rotation.model;

/** Details of a single position exit, for logging. */
public final class ExitDetail {

    public final String symbol;
    public final Double entryPrice; // null when the entry price is unknown
    public final double exitPrice;
    public final double quantity;
    public final double exitValue;
    public final double realizedPnl;

    public ExitDetail(String symbol, Double entryPrice, double exitPrice, double quantity,
                      double exitValue, double realizedPnl) {
        this.symbol = symbol;
        this.entryPrice = entryPrice;
        this.exitPrice = exitPrice;
        this.quantity = quantity;
        this.exitValue = exitValue;
        this.realizedPnl = realizedPnl;
    }
}
