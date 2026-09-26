package com.rotation.model;

import java.time.LocalDate;

/** One dated trade action emitted by a rebalance. */
public final class TradebookRow {

    public final LocalDate tradeDate;
    public final String action;
    public final String symbol;
    public final Double quantity;
    public final Double price;
    public final Double tradeValue;
    public final Double entryPrice;
    public final Double exitPrice;
    public final Double realizedPnl;
    public final Double initialBalance;
    public final Double remainingBalance;
    public final LocalDate signalDate;
    public final int rebalanceNumber;

    public TradebookRow(LocalDate tradeDate, String action, String symbol, Double quantity,
                        Double price, Double tradeValue, Double entryPrice, Double exitPrice,
                        Double realizedPnl, Double initialBalance, Double remainingBalance,
                        LocalDate signalDate, int rebalanceNumber) {
        this.tradeDate = tradeDate;
        this.action = action;
        this.symbol = symbol;
        this.quantity = quantity;
        this.price = price;
        this.tradeValue = tradeValue;
        this.entryPrice = entryPrice;
        this.exitPrice = exitPrice;
        this.realizedPnl = realizedPnl;
        this.initialBalance = initialBalance;
        this.remainingBalance = remainingBalance;
        this.signalDate = signalDate;
        this.rebalanceNumber = rebalanceNumber;
    }
}