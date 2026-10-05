package com.rotation.report;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.rotation.model.BacktestResult;
import com.rotation.model.DailyBars;
import com.rotation.model.DailyPortfolioRow;
import com.rotation.model.DailyPositionRow;
import com.rotation.model.DailyValuation;
import com.rotation.model.HoldingsRow;
import com.rotation.model.RebalanceRecord;
import com.rotation.model.ReconciliationReport;
import com.rotation.model.ReconciliationReport.Check;
import com.rotation.model.TradeLedgerRow;
import com.rotation.model.TradebookRow;
import com.rotation.model.YearEndEquity;

/**
 * Cross-checks the daily reports against each other and against the engine's existing
 * reports (rebalances, holdings, yearly marks, tradebook). Hard checks must pass;
 * warnings describe data-quality issues that are reported but do not stop the run.
 */
public final class ReportReconciler {

    /** Money tolerance: the existing reports are rounded to 2 decimals. */
    private static final double MONEY_TOLERANCE = 0.011;
    private static final double QTY_TOLERANCE = 1e-6;

    public ReconciliationReport check(DailyBars bars, BacktestResult result, DailyValuation valuation,
                                      LocalDate startDate) {
        List<Check> checks = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<DailyPortfolioRow> days = valuation.portfolioRows();
        List<DailyPositionRow> positions = valuation.positionRows();
        List<LocalDate> dates = bars.dates();

        // --- Row counts and uniqueness ---
        int tradeStartIdx = 0;
        if (startDate != null) {
            while (tradeStartIdx < dates.size() && dates.get(tradeStartIdx).isBefore(startDate)) {
                tradeStartIdx++;
            }
        }
        int expectedDays = dates.size() - tradeStartIdx;
        boolean ascending = true;
        for (int i = 1; i < days.size(); i++) {
            if (!days.get(i).date.isAfter(days.get(i - 1).date)) {
                ascending = false;
                break;
            }
        }
        boolean firstMatches = !days.isEmpty() && days.get(0).date.equals(dates.get(tradeStartIdx));
        checks.add(new Check("daily_portfolio_rows",
                days.size() == expectedDays && ascending && firstMatches,
                String.format(Locale.US, "%d rows (expected %d sessions from %s to %s), unique ascending dates: %s",
                        days.size(), expectedDays, dates.get(tradeStartIdx), dates.get(dates.size() - 1), ascending)));

        Set<String> keys = new HashSet<>();
        int duplicates = 0;
        Map<LocalDate, Integer> countByDate = new HashMap<>();
        Map<LocalDate, Double> valueByDate = new HashMap<>();
        for (DailyPositionRow p : positions) {
            if (!keys.add(p.date + "|" + p.symbol)) {
                duplicates++;
            }
            countByDate.merge(p.date, 1, Integer::sum);
            valueByDate.merge(p.date, p.marketValue, Double::sum);
        }
        int expectedPositionRows = 0;
        int countMismatches = 0;
        double maxValueGap = 0.0;
        for (DailyPortfolioRow day : days) {
            expectedPositionRows += day.positions;
            if (countByDate.getOrDefault(day.date, 0) != day.positions) {
                countMismatches++;
            }
            maxValueGap = Math.max(maxValueGap,
                    Math.abs(valueByDate.getOrDefault(day.date, 0.0) - day.investedValue));
        }
        checks.add(new Check("daily_positions_unique",
                duplicates == 0 && countMismatches == 0 && positions.size() == expectedPositionRows,
                String.format(Locale.US, "%d rows (expected %d), duplicate date/symbol keys: %d, days with count mismatch: %d",
                        positions.size(), expectedPositionRows, duplicates, countMismatches)));
        checks.add(new Check("invested_equals_position_sum", maxValueGap <= MONEY_TOLERANCE,
                String.format(Locale.US, "max |sum(position market value) - invested_value| = %.6f", maxValueGap)));

        // --- Accounting identities ---
        double maxReconDiff = 0.0;
        LocalDate worstReconDate = null;
        double maxPnlGap = 0.0;
        for (DailyPortfolioRow day : days) {
            if (Math.abs(day.reconciliationDiff) > maxReconDiff) {
                maxReconDiff = Math.abs(day.reconciliationDiff);
                worstReconDate = day.date;
            }
            maxPnlGap = Math.max(maxPnlGap, Math.abs(day.realizedPnl + day.unrealizedPnl - day.totalPnl));
        }
        checks.add(new Check("equity_equals_cash_plus_invested", maxReconDiff <= MONEY_TOLERANCE,
                String.format(Locale.US, "max |equity - (cash + invested)| = %.6f%s", maxReconDiff,
                        worstReconDate == null ? "" : " on " + worstReconDate)));
        checks.add(new Check("pnl_decomposition", maxPnlGap <= MONEY_TOLERANCE,
                String.format(Locale.US, "max |realized + unrealized - total P&L| = %.6f", maxPnlGap)));

        double contributionsDaily = days.stream().mapToDouble(d -> d.contribution).sum();
        double contributionsEngine = result.equityCurve().stream().mapToDouble(r -> r.contribution).sum();
        checks.add(new Check("contributions_match_equity_report",
                Math.abs(contributionsDaily - contributionsEngine) <= MONEY_TOLERANCE,
                String.format(Locale.US, "daily %.2f vs equity report %.2f", contributionsDaily, contributionsEngine)));

        // --- Reconcile against the existing reports on matching valuation dates ---
        Map<LocalDate, DailyPortfolioRow> dayByDate = new HashMap<>();
        for (DailyPortfolioRow day : days) {
            dayByDate.put(day.date, day);
        }
        double maxYearGap = 0.0;
        int yearMissing = 0;
        for (YearEndEquity mark : result.yearEndMarks()) {
            DailyPortfolioRow day = dayByDate.get(mark.date);
            if (day == null) {
                yearMissing++;
                continue;
            }
            maxYearGap = Math.max(maxYearGap, Math.abs(day.accountEquity - mark.equity));
        }
        checks.add(new Check("year_end_marks_match_yearly_report", yearMissing == 0 && maxYearGap <= MONEY_TOLERANCE,
                String.format(Locale.US, "%d year-end closes compared, max gap %.4f, missing dates %d",
                        result.yearEndMarks().size(), maxYearGap, yearMissing)));

        Map<LocalDate, List<DailyPositionRow>> positionsByDate = new HashMap<>();
        for (DailyPositionRow p : positions) {
            positionsByDate.computeIfAbsent(p.date, ignored -> new ArrayList<>()).add(p);
        }
        Map<String, Integer> symbolIndex = new HashMap<>();
        for (int i = 0; i < bars.symbols().size(); i++) {
            symbolIndex.put(bars.symbols().get(i), i);
        }
        Map<LocalDate, Integer> dateIndex = new HashMap<>();
        for (int i = 0; i < dates.size(); i++) {
            dateIndex.put(dates.get(i), i);
        }
        double maxOpenGap = 0.0;
        double maxAfterGap = 0.0;
        LocalDate worstOpenDate = null;
        for (RebalanceRecord r : result.rebalances()) {
            DailyPortfolioRow day = dayByDate.get(r.date);
            if (day == null) {
                maxOpenGap = Double.POSITIVE_INFINITY;
                worstOpenDate = r.date;
                continue;
            }
            int d = dateIndex.get(r.date);
            double atOpen = 0.0;
            for (DailyPositionRow p : positionsByDate.getOrDefault(r.date, List.of())) {
                double open = bars.openAt(d, symbolIndex.get(p.symbol));
                atOpen += Double.isNaN(open) ? 0.0 : p.quantity * open;
            }
            double gap = Math.abs(day.cash + atOpen - r.accountEquity);
            if (gap > maxOpenGap) {
                maxOpenGap = gap;
                worstOpenDate = r.date;
            }
            maxAfterGap = Math.max(maxAfterGap, Math.abs(atOpen - r.portfolioValueAfter));
        }
        checks.add(new Check("rebalance_open_marks_match_rebalance_report",
                maxOpenGap <= MONEY_TOLERANCE && maxAfterGap <= MONEY_TOLERANCE,
                String.format(Locale.US,
                        "%d rebalances: max |cash + qty*open - account_equity| = %.4f%s, max |qty*open - portfolio_value_after| = %.4f",
                        result.rebalances().size(), maxOpenGap, worstOpenDate == null ? "" : " on " + worstOpenDate,
                        maxAfterGap)));

        List<HoldingsRow> holdingsRows = result.holdingsRows();
        DailyPortfolioRow lastDay = days.isEmpty() ? null : days.get(days.size() - 1);
        if (lastDay != null && lastDay.positions > 0 && !holdingsRows.isEmpty()) {
            HoldingsRow markRow = holdingsRows.get(holdingsRows.size() - 1);
            double gap = Math.abs(markRow.accountEquity - lastDay.accountEquity);
            boolean sameDate = markRow.date.equals(lastDay.date);
            checks.add(new Check("final_mark_matches_holdings_report", sameDate && gap <= MONEY_TOLERANCE,
                    String.format(Locale.US, "holdings mark %s equity %.2f vs daily %s equity %.2f",
                            markRow.date, markRow.accountEquity, lastDay.date, lastDay.accountEquity)));
        }

        List<TradeLedgerRow> ledgerTrades = new ArrayList<>();
        for (TradeLedgerRow row : valuation.ledgerRows()) {
            if (row.action.equals("ENTRY") || row.action.equals("EXIT") || row.action.equals("STOP")) {
                ledgerTrades.add(row);
            }
        }
        List<TradebookRow> tradebook = result.tradebookRows();
        int tradeMismatches = 0;
        String firstMismatch = "";
        if (ledgerTrades.size() != tradebook.size()) {
            tradeMismatches = Math.abs(ledgerTrades.size() - tradebook.size());
            firstMismatch = "row counts differ";
        } else {
            for (int i = 0; i < tradebook.size(); i++) {
                TradebookRow t = tradebook.get(i);
                TradeLedgerRow l = ledgerTrades.get(i);
                boolean same = t.tradeDate.equals(l.date) && t.action.equals(l.action) && t.symbol.equals(l.symbol)
                        && Math.abs(t.quantity - l.quantity) <= QTY_TOLERANCE
                        && Math.abs(t.price - l.price) <= QTY_TOLERANCE
                        && (t.realizedPnl == null) == (l.pnlVsEntryPrice == null)
                        && (t.realizedPnl == null || Math.abs(t.realizedPnl - l.pnlVsEntryPrice) <= MONEY_TOLERANCE);
                if (!same) {
                    if (tradeMismatches == 0) {
                        firstMismatch = "first at " + t.tradeDate + " " + t.action + " " + t.symbol;
                    }
                    tradeMismatches++;
                }
            }
        }
        checks.add(new Check("ledger_matches_tradebook", tradeMismatches == 0,
                String.format(Locale.US, "%d tradebook rows vs %d ledger ENTRY/EXIT/STOP rows, mismatches %d %s",
                        tradebook.size(), ledgerTrades.size(), tradeMismatches, firstMismatch).trim()));

        int lookAhead = 0;
        for (TradeLedgerRow row : valuation.ledgerRows()) {
            if (!row.date.isAfter(row.signalDate)) {
                lookAhead++;
            }
        }
        checks.add(new Check("fills_execute_after_signal", lookAhead == 0,
                lookAhead + " fills dated on/before their signal close (each must execute at a later open)"));

        // --- Data-quality warnings ---
        int missing = 0;
        int unchanged = 0;
        int zeroQty = 0;
        List<String> missingSamples = new ArrayList<>();
        for (DailyPositionRow p : positions) {
            if (p.priceStatus.equals(DailyPositionRow.PRICE_MISSING)) {
                if (missingSamples.size() < 5) {
                    missingSamples.add(p.symbol + "@" + p.date);
                }
                missing++;
            } else if (p.priceStatus.equals(DailyPositionRow.PRICE_UNCHANGED)) {
                unchanged++;
            }
            if (p.quantity == 0.0) {
                zeroQty++;
            }
        }
        if (missing > 0) {
            warnings.add(missing + " held position-days have no close and are valued at zero (engine convention), e.g. "
                    + String.join(", ", missingSamples));
        }
        if (unchanged > 0) {
            warnings.add(unchanged + " held position-days use a bar identical to the prior session (likely forward-filled, stale price)");
        }
        if (zeroQty > 0) {
            warnings.add(zeroQty + " held position-days carry 0 shares (allocation below one share's price)");
        }
        long drops = valuation.ledgerRows().stream().filter(r -> r.action.equals("DROP")).count();
        if (drops > 0) {
            warnings.add(drops + " positions were written off at zero because no open price existed on the execution day");
        }
        long negativeCash = days.stream().filter(d -> d.cash < -MONEY_TOLERANCE).count();
        if (negativeCash > 0) {
            warnings.add(negativeCash + " sessions end with negative cash (allocation exceeded available cash)");
        }
        int staleBars = 0;
        for (int s = 0; s < bars.symbolCount(); s++) {
            for (int d = 1; d < dates.size(); d++) {
                if (!Double.isNaN(bars.closeAt(d, s)) && bars.closeAt(d, s) == bars.closeAt(d - 1, s)
                        && bars.openAt(d, s) == bars.openAt(d - 1, s) && bars.highAt(d, s) == bars.highAt(d - 1, s)
                        && bars.lowAt(d, s) == bars.lowAt(d - 1, s)) {
                    staleBars++;
                }
            }
        }
        if (staleBars > 0) {
            warnings.add(staleBars + " symbol-sessions in the daily snapshot repeat the prior bar exactly (possible forward fill by the loader)");
        }
        boolean hasVolume = false;
        for (int symbol = 0; symbol < bars.symbolCount() && !hasVolume; symbol++) {
            for (int day = 0; day < dates.size(); day++) {
                if (Double.isFinite(bars.volumeAt(day, symbol))) {
                    hasVolume = true;
                    break;
                }
            }
        }
        if (!hasVolume) {
            warnings.add("The daily market snapshot has no finite volume values, so volume is unavailable.");
        } else {
            warnings.add("The daily market snapshot includes volume; the portal charts do not currently render it.");
        }
        return new ReconciliationReport(checks, warnings);
    }
}
