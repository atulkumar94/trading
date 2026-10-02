package com.rotation.model;

import java.time.LocalDate;

/**
 * One position change emitted by the engine, in execution order. Unlike the
 * tradebook this also records the share-count re-weights of held names at each
 * rebalance (ADD / TRIM) and write-offs of positions with no execution price
 * (DROP), so replaying every fill reproduces the engine's book exactly.
 */
public final class LedgerFill {

    public static final String ENTRY = "ENTRY";
    public static final String ADD = "ADD";
    public static final String TRIM = "TRIM";
    public static final String EXIT = "EXIT";
    public static final String STOP = "STOP";
    public static final String DROP = "DROP";

    public final LocalDate date;          // execution date (always a session open)
    public final LocalDate signalDate;    // close the decision was taken on
    public final int rebalanceNumber;     // rebalance that issued (or owns, for STOP) the fill
    public final String action;
    public final String symbol;
    public final double quantity;         // always positive; direction comes from the action
    public final double price;            // adjusted execution price (0 for DROP)
    public final String reason;

    public LedgerFill(LocalDate date, LocalDate signalDate, int rebalanceNumber, String action,
                      String symbol, double quantity, double price, String reason) {
        this.date = date;
        this.signalDate = signalDate;
        this.rebalanceNumber = rebalanceNumber;
        this.action = action;
        this.symbol = symbol;
        this.quantity = quantity;
        this.price = price;
        this.reason = reason;
    }

    public boolean isBuy() {
        return action.equals(ENTRY) || action.equals(ADD);
    }
}
