package com.rotation.model;

import java.util.List;

/** Daily portfolio, daily positions and the fill ledger derived from one backtest run. */
public final class DailyValuation {

    private final List<DailyPortfolioRow> portfolioRows;
    private final List<DailyPositionRow> positionRows;
    private final List<TradeLedgerRow> ledgerRows;
    private final double initialCapital;

    public DailyValuation(List<DailyPortfolioRow> portfolioRows, List<DailyPositionRow> positionRows,
                          List<TradeLedgerRow> ledgerRows, double initialCapital) {
        this.portfolioRows = List.copyOf(portfolioRows);
        this.positionRows = List.copyOf(positionRows);
        this.ledgerRows = List.copyOf(ledgerRows);
        this.initialCapital = initialCapital;
    }

    public List<DailyPortfolioRow> portfolioRows() {
        return portfolioRows;
    }

    public List<DailyPositionRow> positionRows() {
        return positionRows;
    }

    public List<TradeLedgerRow> ledgerRows() {
        return ledgerRows;
    }

    public double initialCapital() {
        return initialCapital;
    }
}
