package com.rotation.model;

import java.time.YearMonth;

/** A month-level market snapshot row for a single symbol. */
public final class MonthlyMarketSnapshotRow {
    public final YearMonth month;
    public final String symbol;
    public final double prevClose;
    public final double open;
    public final double high;
    public final double low;
    public final double close;
    public final double returnVsPrevClose;

    public MonthlyMarketSnapshotRow(YearMonth month, String symbol, double prevClose, double open,
                                   double high, double low, double close, double returnVsPrevClose) {
        this.month = month;
        this.symbol = symbol;
        this.prevClose = prevClose;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.returnVsPrevClose = returnVsPrevClose;
    }
}
