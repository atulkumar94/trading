package com.rotation.model;

import java.time.LocalDate;

/** A daily market snapshot row for one symbol on one day. */
public final class MarketSnapshotRow {
    public final LocalDate date;
    public final String symbol;
    public final double prevClose;
    public final double open;
    public final double high;
    public final double low;
    public final double close;
    public final double returnVsPrevClose;

    public MarketSnapshotRow(LocalDate date, String symbol, double prevClose, double open, double high,
                            double low, double close, double returnVsPrevClose) {
        this.date = date;
        this.symbol = symbol;
        this.prevClose = prevClose;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.returnVsPrevClose = returnVsPrevClose;
    }
}
