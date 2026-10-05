package com.rotation.portfolio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.rotation.model.DailyMark;
import com.rotation.model.TradebookRow;
import com.rotation.model.YearEndEquity;

/**
 * Ordered execution events and end-of-day marks produced by one backtest run.
 * Report builders replay this ledger instead of consulting live portfolio state.
 */
public final class Ledger {

    private final List<Fill> fills = new ArrayList<>();
    private final List<DailyMark> dailyMarks = new ArrayList<>();
    private final List<TradebookRow> tradebookRows = new ArrayList<>();
    private final List<RebalanceEvent> rebalances = new ArrayList<>();
    private final List<FinalPortfolioMark> finalMarks = new ArrayList<>();
    private final List<YearEndEquity> yearEndMarks = new ArrayList<>();

    public void recordFill(Fill fill) {
        fills.add(fill);
    }

    public void recordMark(DailyMark mark) {
        dailyMarks.add(mark);
    }

    public void recordTradebookRow(TradebookRow row) {
        tradebookRows.add(row);
    }

    public void recordRebalance(RebalanceEvent event) {
        rebalances.add(event);
    }

    public void recordFinalMark(FinalPortfolioMark mark) {
        finalMarks.add(mark);
    }

    public void recordYearEndMark(YearEndEquity mark) {
        yearEndMarks.add(mark);
    }

    public List<Fill> fills() {
        return Collections.unmodifiableList(fills);
    }

    public List<DailyMark> dailyMarks() {
        return Collections.unmodifiableList(dailyMarks);
    }

    public List<TradebookRow> tradebookRows() {
        return Collections.unmodifiableList(tradebookRows);
    }

    public List<RebalanceEvent> rebalances() {
        return Collections.unmodifiableList(rebalances);
    }

    public List<FinalPortfolioMark> finalMarks() {
        return Collections.unmodifiableList(finalMarks);
    }

    public List<YearEndEquity> yearEndMarks() {
        return Collections.unmodifiableList(yearEndMarks);
    }

    /** Mutable projection sink for execution code; report consumers use {@link #tradebookRows()}. */
    public List<TradebookRow> executionTradebookRows() {
        return tradebookRows;
    }

    public static Ledger from(List<Fill> fills, List<DailyMark> dailyMarks,
                              List<TradebookRow> tradebookRows) {
        Ledger ledger = new Ledger();
        fills.forEach(ledger::recordFill);
        dailyMarks.forEach(ledger::recordMark);
        tradebookRows.forEach(ledger::recordTradebookRow);
        return ledger;
    }
}