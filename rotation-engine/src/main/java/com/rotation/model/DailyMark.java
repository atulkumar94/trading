package com.rotation.model;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The engine's end-of-day state for one trading session: the book after that
 * session's rebalance and stop fills (all executed at its open) and before any
 * stop triggered by its own close (which fills at the next open). Account
 * equity uses the engine's mark-to-market formula at the session close.
 */
public final class DailyMark {

    public final LocalDate date;
    public final double accountEquity;
    public final double contribution;     // external cash credited at this session's open
    public final int rebalanceNumber;     // rebalance that established the current book (0 = none yet)
    public final Map<String, Double> holdings;

    public DailyMark(LocalDate date, double accountEquity, double contribution, int rebalanceNumber,
                     Map<String, Double> holdings) {
        this.date = date;
        this.accountEquity = accountEquity;
        this.contribution = contribution;
        this.rebalanceNumber = rebalanceNumber;
        this.holdings = Collections.unmodifiableMap(new LinkedHashMap<>(holdings));
    }
}
