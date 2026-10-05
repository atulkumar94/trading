package com.rotation.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.rotation.model.EquityRow;
import com.rotation.model.HoldingsRow;
import com.rotation.model.RebalanceRecord;
import com.rotation.portfolio.FinalPortfolioMark;
import com.rotation.portfolio.Ledger;
import com.rotation.portfolio.RebalanceEvent;

/** Projects the established CSV row models from immutable portfolio ledger events. */
public final class BacktestReportBuilder {

    public BacktestReports build(Ledger ledger) {
        List<RebalanceRecord> rebalances = new ArrayList<>();
        List<EquityRow> equityRows = new ArrayList<>();
        List<HoldingsRow> holdingsRows = new ArrayList<>();
        for (RebalanceEvent event : ledger.rebalances()) {
            rebalances.add(new RebalanceRecord(event.date, event.signalDate,
                    round2(event.portfolioValueBefore), round2(event.portfolioValueAfter),
                    round2(event.accountEquity), round2(event.periodPnl), event.selectedCount,
                    round2(event.capitalPerStock), event.entered, event.exited, event.held,
                    round2(event.periodReturnPct)));
            equityRows.add(new EquityRow(event.date, event.signalDate,
                    round2(event.portfolioValueAfter), round2(event.accountEquity),
                    round2(event.periodPnl), round2(event.capitalPerStock),
                    String.join(",", event.selectedSymbols), event.lookbackStart,
                    event.previousRebalanceDate, round2(event.contribution)));
            holdingsRows.add(new HoldingsRow(event.date, event.signalDate, rebalances.size(),
                    event.holdings.size(), holdingsString(event.holdings), round2(event.investedValue),
                    round2(event.cash), round2(event.portfolioValue), round2(event.accountEquity),
                    round2(event.periodReturnPct), round2(event.cumulativeReturnPct)));
        }
        for (FinalPortfolioMark mark : ledger.finalMarks()) {
            holdingsRows.add(new HoldingsRow(mark.date, mark.date, mark.rebalanceNumber,
                    mark.holdings.size(), holdingsString(mark.holdings), round2(mark.investedValue),
                    round2(mark.cash), round2(mark.portfolioValue), round2(mark.accountEquity),
                    round2(mark.periodReturnPct), round2(mark.cumulativeReturnPct)));
        }
        return new BacktestReports(rebalances, equityRows, holdingsRows, ledger.yearEndMarks());
    }

    private static String holdingsString(Map<String, Double> holdings) {
        StringBuilder value = new StringBuilder();
        for (Map.Entry<String, Double> holding : holdings.entrySet()) {
            if (value.length() > 0) {
                value.append('|');
            }
            value.append(holding.getKey()).append(':')
                    .append(String.format(Locale.US, "%.2f", holding.getValue()));
        }
        return value.toString();
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}