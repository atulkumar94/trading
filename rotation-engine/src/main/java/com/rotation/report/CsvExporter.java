package com.rotation.report;

import com.rotation.model.BacktestResult;
import com.rotation.model.EquityRow;
import com.rotation.model.HoldingsRow;
import com.rotation.model.LookbackRow;
import com.rotation.model.PerformanceRow;
import com.rotation.model.RebalanceRecord;
import com.rotation.model.TradebookRow;
import com.rotation.model.YearEndEquity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Writes the rebalance log, equity curve and performance table to CSV files. */
public final class CsvExporter {

    public void export(BacktestResult result, Path outputDir, String prefix) {
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to create output directory: " + outputDir, e);
        }
        writeRebalances(result.rebalances(), outputDir.resolve(prefix + "_rebalances.csv"));
        writeEquity(result.equityCurve(), outputDir.resolve(prefix + "_equity.csv"));
        writePerformance(result.performanceRows(), outputDir.resolve(prefix + "_performance.csv"));
        writeTradebook(result.tradebookRows(), outputDir.resolve(prefix + "_tradebook.csv"));
        writeHoldings(result.holdingsRows(), outputDir.resolve(prefix + "_holdings.csv"));
        writeLookback(result.lookbackRows(), outputDir.resolve(prefix + "_lookback.csv"));
        writeYearly(result.equityCurve(), result.yearEndMarks(), outputDir.resolve(prefix + "_yearly.csv"));
    }

    private void writeRebalances(List<RebalanceRecord> rows, Path path) {
        try (Writer w = Files.newBufferedWriter(path)) {
            w.write("date,signal_date,portfolio_value_before,portfolio_value_after,account_equity,"
                    + "period_pnl,selected_count,capital_per_stock,entered,exited,held,period_return_pct\n");
            for (RebalanceRecord r : rows) {
                w.write(String.join(",",
                        date(r.date), date(r.signalDate), num(r.portfolioValueBefore), num(r.portfolioValueAfter),
                        num(r.accountEquity), num(r.periodPnl), Integer.toString(r.selectedCount),
                        num(r.capitalPerStock), quote(r.entered), quote(r.exited), quote(r.held),
                        num(r.periodReturnPct)));
                w.write("\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write " + path, e);
        }
    }

    private void writeEquity(List<EquityRow> rows, Path path) {
        try (Writer w = Files.newBufferedWriter(path)) {
            w.write("date,signal_date,portfolio_value,account_equity,period_pnl,capital_per_stock,"
                    + "selected_symbols,lookback_start,previous_rebalance\n");
            for (EquityRow r : rows) {
                w.write(String.join(",",
                        date(r.date), date(r.signalDate), num(r.portfolioValue), num(r.accountEquity),
                        num(r.periodPnl), num(r.capitalPerStock), quote(r.selectedSymbols),
                        date(r.lookbackStart), date(r.previousRebalance)));
                w.write("\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write " + path, e);
        }
    }

    private void writePerformance(List<PerformanceRow> rows, Path path) {
        try (Writer w = Files.newBufferedWriter(path)) {
            w.write("rebalance_number,signal_date,execution_date,lookback_start,rank,symbol,"
                    + "lookback_price,current_price,lookback_return_pct,history_days,selected\n");
            for (PerformanceRow r : rows) {
                w.write(String.join(",",
                        Integer.toString(r.rebalanceNumber), date(r.signalDate), date(r.executionDate),
                        date(r.lookbackStart), Integer.toString(r.rank), quote(r.symbol),
                        num(r.lookbackPrice), num(r.currentPrice), num(r.lookbackReturnPct),
                        Integer.toString(r.historyDays), Boolean.toString(r.selected)));
                w.write("\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write " + path, e);
        }
    }

    private void writeTradebook(List<TradebookRow> rows, Path path) {
        try (Writer w = Files.newBufferedWriter(path)) {
            w.write("trade_date,action,symbol,quantity,price,trade_value,entry_price,exit_price,"
                    + "realized_pnl,initial_balance,remaining_balance,signal_date,rebalance_number\n");
            for (TradebookRow r : rows) {
                w.write(String.join(",",
                        date(r.tradeDate), quote(r.action), quote(r.symbol), num(r.quantity), num(r.price),
                        num(r.tradeValue), num(r.entryPrice), num(r.exitPrice), num(r.realizedPnl),
                        num(r.initialBalance), num(r.remainingBalance), date(r.signalDate),
                        Integer.toString(r.rebalanceNumber)));
                w.write("\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write " + path, e);
        }
    }

    private void writeHoldings(List<HoldingsRow> rows, Path path) {
        try (Writer w = Files.newBufferedWriter(path)) {
            w.write("date,signal_date,rebalance_number,holdings_count,holdings,invested_value,"
                    + "cash_balance,portfolio_value,account_equity,period_return_pct,cumulative_return_pct\n");
            for (HoldingsRow r : rows) {
                w.write(String.join(",",
                        date(r.date), date(r.signalDate), Integer.toString(r.rebalanceNumber),
                        Integer.toString(r.holdingsCount), quote(r.holdings), num(r.investedValue),
                        num(r.cashBalance), num(r.portfolioValue), num(r.accountEquity),
                        num(r.periodReturnPct), num(r.cumulativeReturnPct)));
                w.write("\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write " + path, e);
        }
    }

    private void writeLookback(List<LookbackRow> rows, Path path) {
        try (Writer w = Files.newBufferedWriter(path)) {
            w.write("signal_date,execution_date,lookback_start,rank,symbol,lookback_price,current_price,lookback_return_pct,history_days,selected\n");
            for (LookbackRow r : rows) {
                w.write(String.join(",",
                        date(r.signalDate), date(r.executionDate), date(r.lookbackStart),
                        Integer.toString(r.rank), quote(r.symbol), num(r.lookbackPrice),
                        num(r.currentPrice), num(r.lookbackReturnPct), Integer.toString(r.historyDays),
                        Boolean.toString(r.selected)));
                w.write("\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write " + path, e);
        }
    }

    private void writeYearly(List<EquityRow> equityRows, List<YearEndEquity> yearEndMarks, Path path) {
        try (Writer w = Files.newBufferedWriter(path)) {
            w.write("year,start_date,end_date,start_equity,end_equity,contributions,period_pnl,yearly_return_pct,cagr_pct,rebalance_count\n");
            if (equityRows.isEmpty() || yearEndMarks.isEmpty()) {
                return;
            }

            List<EquityRow> allRows = new ArrayList<>(equityRows);
            allRows.sort(Comparator.comparing((EquityRow r) -> r.date));
            LocalDate inceptionDate = allRows.get(0).date;
            double inceptionEquity = allRows.get(0).accountEquity;

            // Contributions and rebalance counts stay keyed to the calendar year they
            // occur in; only the equity boundaries move to the true year end.
            Map<Integer, Double> contributionsByYear = new LinkedHashMap<>();
            Map<Integer, Integer> rebalancesByYear = new LinkedHashMap<>();
            for (EquityRow r : allRows) {
                int year = r.date.getYear();
                contributionsByYear.merge(year, r.contribution, Double::sum);
                rebalancesByYear.merge(year, 1, Integer::sum);
            }

            List<YearEndEquity> marks = new ArrayList<>(yearEndMarks);
            marks.sort(Comparator.comparingInt((YearEndEquity m) -> m.year));

            // Chain each year's closing (year-end mark-to-market) equity into the next
            // year's opening so the series is continuous with no PnL dropped at a boundary.
            LocalDate startDate = inceptionDate;
            double startEquity = inceptionEquity;
            for (YearEndEquity mark : marks) {
                int year = mark.year;
                LocalDate endDate = mark.date;
                double endEquity = mark.equity;
                double contributions = contributionsByYear.getOrDefault(year, 0.0);
                int rebalanceCount = rebalancesByYear.getOrDefault(year, 0);
                double periodPnl = endEquity - startEquity - contributions;
                double yearlyReturnPct = startEquity == 0.0 ? 0.0
                        : (((endEquity - contributions) / startEquity) - 1.0) * 100.0;
                double elapsedYears = ChronoUnit.DAYS.between(inceptionDate, endDate) / 365.25;
                // Money-weighted annualized return (XIRR): the initial capital and every
                // monthly contribution are cash outflows on their own dates, with the
                // year-end equity as the closing inflow. A simple end/start ratio would
                // misread contributions as investment growth.
                double cagrPct;
                if (inceptionEquity <= 0.0 || elapsedYears <= 0.0) {
                    cagrPct = 0.0;
                } else {
                    List<LocalDate> cashFlowDates = new ArrayList<>();
                    List<Double> cashFlowAmounts = new ArrayList<>();
                    cashFlowDates.add(inceptionDate);
                    cashFlowAmounts.add(-inceptionEquity);
                    for (EquityRow r : allRows) {
                        if (!r.date.isAfter(endDate) && r.contribution != 0.0) {
                            cashFlowDates.add(r.date);
                            cashFlowAmounts.add(-r.contribution);
                        }
                    }
                    cashFlowDates.add(endDate);
                    cashFlowAmounts.add(endEquity);
                    cagrPct = xirrPct(cashFlowDates, cashFlowAmounts, inceptionDate);
                }
                w.write(String.join(",",
                        Integer.toString(year),
                        date(startDate),
                        date(endDate),
                        num(startEquity),
                        num(endEquity),
                        num(contributions),
                        num(periodPnl),
                        num(yearlyReturnPct),
                        num(cagrPct),
                        Integer.toString(rebalanceCount)));
                w.write("\n");
                startDate = endDate;
                startEquity = endEquity;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write " + path, e);
        }
    }

    /** Annualized money-weighted return (XIRR) in percent, solved by bisection. */
    private static double xirrPct(List<LocalDate> dates, List<Double> amounts, LocalDate base) {
        double lo = -0.999999;
        double hi = 1000.0;
        double fLo = npv(dates, amounts, base, lo);
        double fHi = npv(dates, amounts, base, hi);
        if (fLo * fHi > 0.0) {
            return 0.0; // no sign change in the bracket -> not solvable
        }
        for (int i = 0; i < 200; i++) {
            double mid = 0.5 * (lo + hi);
            double fMid = npv(dates, amounts, base, mid);
            if (fMid == 0.0) {
                return mid * 100.0;
            }
            if (fLo * fMid < 0.0) {
                hi = mid;
            } else {
                lo = mid;
                fLo = fMid;
            }
        }
        return 0.5 * (lo + hi) * 100.0;
    }

    private static double npv(List<LocalDate> dates, List<Double> amounts, LocalDate base, double rate) {
        double base1 = 1.0 + rate;
        double sum = 0.0;
        for (int i = 0; i < dates.size(); i++) {
            double years = ChronoUnit.DAYS.between(base, dates.get(i)) / 365.25;
            sum += amounts.get(i) / Math.pow(base1, years);
        }
        return sum;
    }

    private static String date(LocalDate date) {
        return date == null ? "" : date.toString();
    }

    private static String num(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private static String num(Double value) {
        return value == null ? "" : num(value.doubleValue());
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
