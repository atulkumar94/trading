package com.rotation.model;

import java.time.LocalDate;

/** One held position valued at a trading session's close. */
public final class DailyPositionRow {

    public static final String PRICE_OK = "OK";
    /** No close for the session: valued at zero, matching the engine's mark-to-market. */
    public static final String PRICE_MISSING = "MISSING";
    /** OHLC identical to the prior session: likely a forward-filled (stale) bar. */
    public static final String PRICE_UNCHANGED = "UNCHANGED";

    public final LocalDate date;
    public final String symbol;
    public final String sector;
    public final LocalDate entryDate;        // first ENTRY of the current continuous holding
    public final int rebalanceNumber;        // rebalance whose book is held (engine state)
    public final double quantity;
    public final double entryPrice;          // price of that first ENTRY (tradebook convention)
    public final double averageCost;         // average cost per share including re-weights
    public final double costBasis;
    public final Double close;               // adjusted close; null when missing
    public final double marketValue;
    public final double unrealizedPnl;
    public final double unrealizedPct;
    public final double weightPct;           // market value / account equity
    public final String priceStatus;

    public DailyPositionRow(LocalDate date, String symbol, String sector, LocalDate entryDate,
                            int rebalanceNumber, double quantity, double entryPrice, double averageCost,
                            double costBasis, Double close, double marketValue, double unrealizedPnl,
                            double unrealizedPct, double weightPct, String priceStatus) {
        this.date = date;
        this.symbol = symbol;
        this.sector = sector;
        this.entryDate = entryDate;
        this.rebalanceNumber = rebalanceNumber;
        this.quantity = quantity;
        this.entryPrice = entryPrice;
        this.averageCost = averageCost;
        this.costBasis = costBasis;
        this.close = close;
        this.marketValue = marketValue;
        this.unrealizedPnl = unrealizedPnl;
        this.unrealizedPct = unrealizedPct;
        this.weightPct = weightPct;
        this.priceStatus = priceStatus;
    }
}
