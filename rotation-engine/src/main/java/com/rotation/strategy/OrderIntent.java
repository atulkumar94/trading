package com.rotation.strategy;

/** Immutable strategy decision. It describes desired action; it is not a fill. */
public final class OrderIntent {

    public enum Kind { ENTER, EXIT, TRIM, TARGET_ALLOCATION, STOP, DROP }

    public final String symbol;
    public final Kind kind;
    public final double weightOrQty;
    public final double stopPrice;
    public final String reason;
    public final int priority;
    public final int signalIndex;

    public OrderIntent(String symbol, Kind kind, double weightOrQty, double stopPrice,
                       String reason, int priority, int signalIndex) {
        this.symbol = symbol;
        this.kind = kind;
        this.weightOrQty = weightOrQty;
        this.stopPrice = stopPrice;
        this.reason = reason;
        this.priority = priority;
        this.signalIndex = signalIndex;
    }
}
