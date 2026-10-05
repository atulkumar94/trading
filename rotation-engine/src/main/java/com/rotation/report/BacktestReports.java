package com.rotation.report;

import java.util.List;

import com.rotation.model.EquityRow;
import com.rotation.model.HoldingsRow;
import com.rotation.model.RebalanceRecord;
import com.rotation.model.YearEndEquity;

/** Immutable legacy report rows projected from the portfolio ledger. */
public final class BacktestReports {

    private final List<RebalanceRecord> rebalances;
    private final List<EquityRow> equityRows;
    private final List<HoldingsRow> holdingsRows;
    private final List<YearEndEquity> yearEndMarks;

    public BacktestReports(List<RebalanceRecord> rebalances, List<EquityRow> equityRows,
                           List<HoldingsRow> holdingsRows, List<YearEndEquity> yearEndMarks) {
        this.rebalances = List.copyOf(rebalances);
        this.equityRows = List.copyOf(equityRows);
        this.holdingsRows = List.copyOf(holdingsRows);
        this.yearEndMarks = List.copyOf(yearEndMarks);
    }

    public List<RebalanceRecord> rebalances() {
        return rebalances;
    }

    public List<EquityRow> equityRows() {
        return equityRows;
    }

    public List<HoldingsRow> holdingsRows() {
        return holdingsRows;
    }

    public List<YearEndEquity> yearEndMarks() {
        return yearEndMarks;
    }
}