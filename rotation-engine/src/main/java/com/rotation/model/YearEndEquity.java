package com.rotation.model;

import java.time.LocalDate;

/**
 * Account equity marked to market at a calendar year's last trading-day close
 * (or, for the still-running final year, the latest available session). Used to
 * strike yearly return boundaries at the true year end rather than at whichever
 * rebalance date happened to fall nearest to it.
 */
public final class YearEndEquity {

    public final int year;
    public final LocalDate date;
    public final double equity;

    public YearEndEquity(int year, LocalDate date, double equity) {
        this.year = year;
        this.date = date;
        this.equity = equity;
    }
}
