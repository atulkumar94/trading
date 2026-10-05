package com.rotation.model;

import java.time.LocalDate;

/** A {@link com.rotation.portfolio.Fill} with its cash and average-cost accounting applied. */
public final class TradeLedgerRow {

    public final LocalDate date;
    public final LocalDate signalDate;
    public final int rebalanceNumber;
    public final String action;
    public final String symbol;
    public final double quantity;
    public final double price;
    public final double tradeValue;
    public final LocalDate entryDate;        // entry date of the position this fill belongs to
    public final double entryPrice;          // first ENTRY price of that position
    public final double averageCostBefore;   // 0 when opening a position
    public final double averageCostAfter;    // 0 once the position is closed
    public final double positionAfter;
    public final Double realizedPnl;         // average-cost realized P&L; null for buys
    public final Double pnlVsEntryPrice;     // tradebook convention (exit - entry) * qty; EXIT/STOP only
    public final double cashAfter;
    public final String reason;

    public TradeLedgerRow(LocalDate date, LocalDate signalDate, int rebalanceNumber, String action,
                          String symbol, double quantity, double price, double tradeValue, LocalDate entryDate,
                          double entryPrice, double averageCostBefore, double averageCostAfter,
                          double positionAfter, Double realizedPnl, Double pnlVsEntryPrice, double cashAfter,
                          String reason) {
        this.date = date;
        this.signalDate = signalDate;
        this.rebalanceNumber = rebalanceNumber;
        this.action = action;
        this.symbol = symbol;
        this.quantity = quantity;
        this.price = price;
        this.tradeValue = tradeValue;
        this.entryDate = entryDate;
        this.entryPrice = entryPrice;
        this.averageCostBefore = averageCostBefore;
        this.averageCostAfter = averageCostAfter;
        this.positionAfter = positionAfter;
        this.realizedPnl = realizedPnl;
        this.pnlVsEntryPrice = pnlVsEntryPrice;
        this.cashAfter = cashAfter;
        this.reason = reason;
    }
}
