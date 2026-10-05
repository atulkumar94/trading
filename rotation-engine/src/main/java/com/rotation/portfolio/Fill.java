package com.rotation.portfolio;

import java.time.LocalDate;

/**
 * One immutable portfolio change in execution order. Reweights remain explicit
 * so a fresh ledger replay can reproduce the engine's share counts exactly.
 */
public final class Fill {

    public static final String ENTRY = "ENTRY";
    public static final String ADD = "ADD";
    public static final String TRIM = "TRIM";
    public static final String EXIT = "EXIT";
    public static final String STOP = "STOP";
    public static final String DROP = "DROP";

    public final LocalDate date;
    public final LocalDate signalDate;
    public final int rebalanceNumber;
    public final String action;
    public final String symbol;
    public final double quantity;
    public final double price;
    public final String reason;

    public Fill(LocalDate date, LocalDate signalDate, int rebalanceNumber, String action,
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