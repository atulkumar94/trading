package com.rotation.report;

import com.rotation.model.EntryDetail;
import com.rotation.model.ExitDetail;
import com.rotation.model.PerformanceRow;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Prints the human-readable per-rebalance runtime log (mirrors the reference tool). */
public final class RebalanceLogger {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String RULE = "=".repeat(78);

    public void logRunHeader(int universeSize, int sessions, LocalDate firstDate, LocalDate lastDate,
                             int topN, int lookbackDays, String schedule, int minHistory,
                             String allocationMode, int rebalances) {
        System.out.println(RULE);
        System.out.printf(Locale.US,
                "Rotation run | universe %d symbols | %d sessions (%s -> %s)%n",
                universeSize, sessions, fmtDate(firstDate), fmtDate(lastDate));
        System.out.printf(Locale.US,
                "Params | top_n=%d | lookback=%d | schedule=%s | min_history=%d | allocation=%s | rebalances=%d%n",
                topN, lookbackDays, schedule, minHistory, allocationMode, rebalances);
    }

    public void logRebalance(int rebalanceNumber, LocalDate signalDate, LocalDate executionDate,
                             LocalDate lookbackStart, int lookbackDays, LocalDate previousRebalanceDate,
                             int sessionsSincePrevious, List<PerformanceRow> performanceTable,
                             List<String> entered, List<String> exited, List<String> held,
                             double portfolioValueBefore, double periodPnl, double periodReturnPct,
                             double portfolioValueAfter, double accountEquity, double contribution,
                             List<EntryDetail> entryDetails, List<ExitDetail> exitDetails) {
        int topDisplay = 5;
        System.out.println(RULE);
        System.out.printf(Locale.US,
                "Rebalance #%d  |  rebalance day %s (orders fill at OPEN)  |  ranked on prior close %s%n",
                rebalanceNumber, fmtDate(executionDate), fmtDate(signalDate));
        if (lookbackStart != null) {
            System.out.printf(Locale.US,
                    "Lookback window: %d sessions (%s close -> %s close)%n",
                    lookbackDays, fmtDate(lookbackStart), fmtDate(signalDate));
        } else {
            System.out.printf(Locale.US,
                    "Lookback window: %d sessions required, but history before %s is too short%n",
                    lookbackDays, fmtDate(signalDate));
        }
        if (previousRebalanceDate != null) {
            System.out.printf(Locale.US,
                    "Held since previous rebalance %s (%d sessions)%n",
                    fmtDate(previousRebalanceDate), sessionsSincePrevious);
        } else {
            System.out.println("First rebalance (started from cash, no prior holdings)");
        }

        if (performanceTable.isEmpty()) {
            System.out.printf(Locale.US,
                    "Ranking: no symbol has the required %d-session history yet -> holding cash this period%n",
                    lookbackDays);
        } else {
            int shown = Math.min(topDisplay, performanceTable.size());
            System.out.printf(Locale.US, "Top %d of %d eligible by %d-day return:%n",
                    shown, performanceTable.size(), lookbackDays);
            for (int i = 0; i < shown; i++) {
                PerformanceRow row = performanceTable.get(i);
                String marker = row.selected ? "  <== SELECTED" : "";
                System.out.printf(Locale.US,
                        "   %2d. %-12s %+8.2f%%   (%.2f -> %.2f)%s%n",
                        row.rank, row.symbol, row.lookbackReturnPct, row.lookbackPrice, row.currentPrice, marker);
            }
        }

        System.out.printf(Locale.US, "Actions: entered=%s  exited=%s  held=%s%n",
                fmtList(entered), fmtList(exited), fmtList(held));
        for (ExitDetail exit : exitDetails) {
            String entryStr = exit.entryPrice != null ? String.format(Locale.US, "%.2f", exit.entryPrice) : "n/a";
            System.out.printf(Locale.US,
                    "   EXIT  %-12s qty %.4f @ %.2f = %,.2f  (entry %s -> exit %.2f, realized P&L %+,.2f)%n",
                    exit.symbol, exit.quantity, exit.exitPrice, exit.exitValue, entryStr, exit.exitPrice, exit.realizedPnl);
        }
        for (EntryDetail entry : entryDetails) {
            System.out.printf(Locale.US,
                    "   ENTER %-12s qty %.4f @ %.2f = %,.2f%n",
                    entry.symbol, entry.quantity, entry.entryPrice, entry.entryValue);
        }
        if (contribution != 0.0) {
            System.out.printf(Locale.US, "Monthly contribution added to principal: %+,.2f%n", contribution);
        }
        System.out.printf(Locale.US,
                "Portfolio: before %,.2f | period P&L %+,.2f (%+.2f%%) | after %,.2f | account equity %,.2f%n",
                portfolioValueBefore - periodPnl, periodPnl, periodReturnPct, portfolioValueAfter, accountEquity);
    }

    private static String fmtDate(LocalDate date) {
        return date == null ? "--" : date.format(DATE);
    }

    private static String fmtList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('\'').append(values.get(i)).append('\'');
        }
        return sb.append(']').toString();
    }
}
