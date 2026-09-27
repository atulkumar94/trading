package com.rotation.model;

/** Details of a single position entry, for logging. */
public final class EntryDetail {

    public final String symbol;
    public final double entryPrice;
    public final double quantity;
    public final double entryValue;

    public EntryDetail(String symbol, double entryPrice, double quantity, double entryValue) {
        this.symbol = symbol;
        this.entryPrice = entryPrice;
        this.quantity = quantity;
        this.entryValue = entryValue;
    }
}
