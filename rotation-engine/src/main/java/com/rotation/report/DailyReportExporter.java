package com.rotation.report;

import com.rotation.model.DailyPortfolioRow;
import com.rotation.model.DailyPositionRow;
import com.rotation.model.DailyValuation;
import com.rotation.model.TradeLedgerRow;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Writes the daily valuation reports: one row per session (portfolio), one row per
 * held symbol per session (positions), and every fill incl. re-weights (ledger).
 * Money is written to 2 decimals, per-share prices to 4, percentages to 4 and the
 * TWR index to 10 so ranges can be recomputed exactly from the CSV.
 */
public final class DailyReportExporter {

    public void export(DailyValuation valuation, Path outputDir, String prefix) {
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to create output directory: " + outputDir, e);
        }
        writePortfolio(valuation.portfolioRows(), outputDir.resolve(prefix + "_daily_portfolio.csv"));
        writePositions(valuation.positionRows(), outputDir.resolve(prefix + "_daily_positions.csv"));
        writeLedger(valuation.ledgerRows(), outputDir.resolve(prefix + "_trade_ledger.csv"));
    }

    private void writePortfolio(List<DailyPortfolioRow> rows, Path path) {
        try (Writer w = Files.newBufferedWriter(path)) {
            w.write("date,rebalance_number,positions,cash,invested_value,account_equity,contribution,"
                    + "cumulative_contributions,net_capital,daily_pnl,daily_return_pct,twr_index,drawdown_pct,"
                    + "realized_pnl,unrealized_pnl,total_pnl,missing_prices,reconciliation_diff\n");
            for (DailyPortfolioRow r : rows) {
                w.write(String.join(",",
                        date(r.date), Integer.toString(r.rebalanceNumber), Integer.toString(r.positions),
                        money(r.cash), money(r.investedValue), money(r.accountEquity), money(r.contribution),
                        money(r.cumulativeContributions), money(r.netCapital), money(r.dailyPnl),
                        r.dailyReturnPct == null ? "" : fixed(r.dailyReturnPct, 4), fixed(r.twrIndex, 10),
                        fixed(r.drawdownPct, 4), money(r.realizedPnl), money(r.unrealizedPnl), money(r.totalPnl),
                        Integer.toString(r.missingPrices), fixed(r.reconciliationDiff, 4)));
                w.write("\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write " + path, e);
        }
    }

    private void writePositions(List<DailyPositionRow> rows, Path path) {
        try (Writer w = Files.newBufferedWriter(path)) {
            w.write("date,symbol,sector,entry_date,rebalance_number,quantity,entry_price,average_cost,cost_basis,"
                    + "close,market_value,unrealized_pnl,unrealized_pct,weight_pct,price_status\n");
            for (DailyPositionRow r : rows) {
                w.write(String.join(",",
                        date(r.date), quote(r.symbol), quote(r.sector), date(r.entryDate),
                        Integer.toString(r.rebalanceNumber), money(r.quantity), price(r.entryPrice),
                        price(r.averageCost), money(r.costBasis), r.close == null ? "" : price(r.close),
                        money(r.marketValue), money(r.unrealizedPnl), fixed(r.unrealizedPct, 4),
                        fixed(r.weightPct, 4), r.priceStatus));
                w.write("\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write " + path, e);
        }
    }

    private void writeLedger(List<TradeLedgerRow> rows, Path path) {
        try (Writer w = Files.newBufferedWriter(path)) {
            w.write("trade_date,signal_date,rebalance_number,action,symbol,quantity,price,trade_value,entry_date,"
                    + "entry_price,average_cost_before,average_cost_after,position_after,realized_pnl,"
                    + "pnl_vs_entry_price,cash_after,reason\n");
            for (TradeLedgerRow r : rows) {
                w.write(String.join(",",
                        date(r.date), date(r.signalDate), Integer.toString(r.rebalanceNumber), r.action,
                        quote(r.symbol), money(r.quantity), price(r.price), money(r.tradeValue), date(r.entryDate),
                        price(r.entryPrice), price(r.averageCostBefore), price(r.averageCostAfter),
                        money(r.positionAfter), r.realizedPnl == null ? "" : money(r.realizedPnl),
                        r.pnlVsEntryPrice == null ? "" : money(r.pnlVsEntryPrice), money(r.cashAfter),
                        quote(r.reason)));
                w.write("\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write " + path, e);
        }
    }

    private static String date(LocalDate date) {
        return date == null ? "" : date.toString();
    }

    private static String money(double value) {
        return fixed(value, 2);
    }

    private static String price(double value) {
        return fixed(value, 4);
    }

    private static String fixed(double value, int decimals) {
        String s = String.format(Locale.US, "%." + decimals + "f", value);
        return s.startsWith("-") && s.matches("-0\\.0*") ? s.substring(1) : s;
    }

    private static String quote(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
