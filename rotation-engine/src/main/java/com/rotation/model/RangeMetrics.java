package com.rotation.model;

import java.time.LocalDate;

/**
 * Contribution-adjusted performance over a date range of the daily portfolio.
 * The range covers sessions {@code from..asOf}; it is measured from the close of
 * the session before {@code from} ({@code baseDate}), or from the initial capital
 * at inception when the range starts at the first backtest session.
 */
public final class RangeMetrics {

    public final String label;
    public final LocalDate requestedFrom;
    public final LocalDate asOf;             // effective (last session on/before the requested as-of)
    public final LocalDate firstSession;     // first session inside the range
    public final LocalDate baseDate;         // null = inception (initial capital)
    public final int sessions;
    public final double startEquity;
    public final double endEquity;
    public final double contributions;
    public final double pnl;                 // end - start - contributions
    public final double returnPct;           // time-weighted
    public final double maxDrawdownPct;      // within the range, peak reset at the base
    public final Double annualizedReturnPct; // null when the range spans < 365 days
    public final boolean truncated;          // requested window starts before the backtest has history

    public RangeMetrics(String label, LocalDate requestedFrom, LocalDate asOf, LocalDate firstSession,
                        LocalDate baseDate, int sessions, double startEquity, double endEquity,
                        double contributions, double pnl, double returnPct, double maxDrawdownPct,
                        Double annualizedReturnPct, boolean truncated) {
        this.label = label;
        this.requestedFrom = requestedFrom;
        this.asOf = asOf;
        this.firstSession = firstSession;
        this.baseDate = baseDate;
        this.sessions = sessions;
        this.startEquity = startEquity;
        this.endEquity = endEquity;
        this.contributions = contributions;
        this.pnl = pnl;
        this.returnPct = returnPct;
        this.maxDrawdownPct = maxDrawdownPct;
        this.annualizedReturnPct = annualizedReturnPct;
        this.truncated = truncated;
    }
}
