package com.rotation.engine;

import com.rotation.config.RotationConfig;
import com.rotation.model.BacktestResult;
import com.rotation.model.DailyBars;
import com.rotation.model.EntryDetail;
import com.rotation.model.EquityRow;
import com.rotation.model.ExitDetail;
import com.rotation.model.HoldingsRow;
import com.rotation.model.LookbackRow;
import com.rotation.model.PerformanceRow;
import com.rotation.model.RebalanceRecord;
import com.rotation.model.TradebookRow;
import com.rotation.model.YearEndEquity;
import com.rotation.report.RebalanceLogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Fixed-schedule top-N momentum rotation engine.
 *
 * <p>On each rebalance the universe is ranked by trailing lookback return using
 * the period-end close; positions are then taken at the <em>next</em> session's
 * open. A symbol is only eligible once it has at least {@code minHistoryDays}
 * (>= lookback) of tracked daily bars. Capital either compounds (reinvest the
 * latest portfolio value) or redeploys a fixed principal each period.
 */
public final class RotationEngine {

    private final RotationConfig config;
    private final RebalanceLogger logger = new RebalanceLogger();
    private final int maxPerSector;
    private final Map<String, String> sectorBySymbol;

    public RotationEngine(RotationConfig config) {
        this.config = config;
        this.maxPerSector = config.maxPerSector();
        this.sectorBySymbol = maxPerSector > 0
                ? loadSectorMap(config.resolveSectorFile())
                : Collections.emptyMap();
    }

    public BacktestResult run(DailyBars bars) {
        int lookbackDays = config.lookbackDays();
        int topN = config.topN();
        int exitN = config.effectiveExitN();
        int minHistory = config.effectiveMinHistoryDays();
        double capitalPerStock = config.capitalPerStock();
        double initialCapital = config.effectiveInitialCapital();
        String allocationMode = config.allocationMode();
        boolean compound = allocationMode.equals("compound");
        double monthlyContribution = config.monthlyContribution();
        boolean verbose = config.verbose();
        List<LocalDate> dates = bars.dates();
        int dateCount = dates.size();
        if (dateCount <= lookbackDays) {
            throw new IllegalArgumentException("Not enough history to run the requested lookback window.");
        }

        // Configurable calendar schedule (rebalance.mode): first trading day of
        // month, first trading day of week, or the 1st & 11th trading day of the
        // month. Each decision is ranked on the prior session's close and executed
        // at that day's open, so switches happen before the day trades.
        String scheduleLabel = scheduleLabel(config.rebalanceMode());
        List<Integer> rebalancePoints = computeRebalanceSignalIndices(dates, config.rebalanceMode());
        if (rebalancePoints.isEmpty()) {
            throw new IllegalArgumentException("Not enough observations to run the requested rebalance schedule.");
        }

        if (verbose) {
            logger.logRunHeader(bars.symbolCount(), dateCount, dates.get(0), dates.get(dateCount - 1),
                    topN, lookbackDays, scheduleLabel, minHistory, allocationMode, rebalancePoints.size());
        }

        List<RebalanceRecord> records = new ArrayList<>();
        List<EquityRow> equityRows = new ArrayList<>();
        List<PerformanceRow> performanceRows = new ArrayList<>();
        List<TradebookRow> tradebookRows = new ArrayList<>();
        List<HoldingsRow> holdingsRows = new ArrayList<>();
        // Post-rebalance portfolio state captured for each period so any calendar
        // date can later be marked to market by replaying stops onto a clone.
        List<PeriodSnapshot> periodSnapshots = new ArrayList<>();

        Map<String, Double> holdings = new LinkedHashMap<>(); // symbol -> quantity
        Map<String, Double> entryPrices = new LinkedHashMap<>();
        Map<String, Double> stopBasis = new LinkedHashMap<>();  // symbol -> period entry price (stop reference)
        Map<String, Double> peakPrices = new LinkedHashMap<>();  // symbol -> highest price since period entry
        int currentHoldingsRebalanceNumber = 0; // rebalance that established the current holdings
        double accountEquity = initialCapital;
        double deployedCapital = 0.0;
        double cash = 0.0; // undeployed cash carried between rebalances (e.g. whole-lot remainder)
        double cumulativeGrowth = 1.0; // compounded product of per-period holding returns
        LocalDate previousRebalanceDate = null;
        int previousExecutionIdx = -1;
        YearMonth lastContributionMonth = null;
        double pendingContribution = 0.0;

        HashSet<Integer> signalIndexSet = new HashSet<>(rebalancePoints);
        double periodRealizedStopPnl = 0.0; // stops booked since the current holdings were established
        double periodInvestedAtStart = 0.0; // deployed capital at the last rebalance (period P&L base)

        // Daily driver: walk every trading day in order. A scheduled rebalance fires
        // on its execution day (the session after a signal day) and stop-losses are
        // evaluated on every day's close, filled at the next session's open.
        for (int day = 0; day < dateCount; day++) {
            if (day >= 1 && signalIndexSet.contains(day - 1)) {
            int signalIdx = day - 1;
            int referenceIdx = signalIdx - (lookbackDays - 1);
            int executionIdx = day;
            LocalDate signalDate = dates.get(signalIdx);
            LocalDate executionDate = dates.get(executionIdx);
            double cashBeforeRebalance = cash; // remainder + stop proceeds accrued this period

            // Add a fixed monthly cash top-up on the first rebalance of each new
            // calendar month (SIP-style). The cash is credited to account equity
            // immediately (even during a cash period) and held as pending cash
            // until it can be deployed. It is tracked separately from trading P&L.
            double contribution = 0.0;
            YearMonth executionMonth = YearMonth.from(executionDate);
            if (compound && monthlyContribution > 0.0 && lastContributionMonth != null
                    && !executionMonth.equals(lastContributionMonth)) {
                contribution = monthlyContribution;
                pendingContribution += monthlyContribution;
            }
            lastContributionMonth = executionMonth;

            // --- 1. Value the book that survived intra-period stops at the execution
            //        (next) open. Stops were already applied day-by-day by the daily
            //        driver, so the realized P&L and cost-basis reduction are carried in
            //        the period accumulators; stopped-out names already sit in cash. ---
            double investedAtPeriodStart = periodInvestedAtStart;
            double realizedStopPnl = periodRealizedStopPnl;

            double portfolioValueBefore;
            double periodPnl;
            double periodReturnPct;
            if (investedAtPeriodStart == 0.0 && holdings.isEmpty()) {
                portfolioValueBefore = accountEquity + cash;
                periodPnl = 0.0;
                periodReturnPct = 0.0;
            } else {
                Map<String, Double> valued = new LinkedHashMap<>();
                double value = 0.0;
                for (Map.Entry<String, Double> entry : holdings.entrySet()) {
                    double price = openAt(bars, executionIdx, entry.getKey());
                    if (Double.isNaN(price)) {
                        continue; // no execution price -> position drops out
                    }
                    valued.put(entry.getKey(), entry.getValue());
                    value += entry.getValue() * price;
                }
                holdings = valued;
                // Total period P&L = unrealized move on surviving holdings plus any
                // realized gain/loss booked by intra-period stops. Carried cash (incl.
                // stop proceeds) is added back so it is re-deployed next period.
                periodPnl = (value - deployedCapital) + realizedStopPnl;
                portfolioValueBefore = value + cash;
                periodReturnPct = investedAtPeriodStart == 0.0
                        ? 0.0
                        : (periodPnl / investedAtPeriodStart) * 100.0;
                accountEquity += periodPnl;
            }
            cash = 0.0; // consumed into portfolioValueBefore / target capital below

            // Credit this month's contribution as fresh cash on this rebalance.
            accountEquity += contribution;

            // --- 2. Rank the eligible universe and pick the book. Always enter the
            //        top-N; when exit.n > top.n, retain currently-held names until they
            //        fall out of the top exit.n (the book may grow up to exit.n). ---
            List<Candidate> ranked = rankUniverse(bars, signalIdx, referenceIdx, minHistory);
            List<String> selected = selectHoldings(ranked, holdings.keySet(), topN, exitN);
            TreeSet<String> selectedSet = new TreeSet<>(selected);

            int rebalanceNumber = records.size() + 1;
            List<PerformanceRow> performanceTable = new ArrayList<>();
            LocalDate lookbackStart = referenceIdx >= 0 ? dates.get(referenceIdx) : null;
            for (int i = 0; i < ranked.size(); i++) {
                Candidate c = ranked.get(i);
                PerformanceRow row = new PerformanceRow(rebalanceNumber, signalDate, executionDate,
                        lookbackStart, i + 1, c.symbol, c.lookbackPrice, c.currentPrice,
                        c.returnPct, c.historyDays, selectedSet.contains(c.symbol));
                performanceTable.add(row);
                performanceRows.add(row);
            }

            // --- 3. Diff previous vs newly selected holdings. ---
            TreeSet<String> previousSymbols = new TreeSet<>(holdings.keySet());
            List<String> entered = new ArrayList<>();
            List<String> exited = new ArrayList<>();
            List<String> held = new ArrayList<>();
            for (String symbol : selectedSet) {
                if (!previousSymbols.contains(symbol)) {
                    entered.add(symbol);
                } else {
                    held.add(symbol);
                }
            }
            for (String symbol : previousSymbols) {
                if (!selectedSet.contains(symbol)) {
                    exited.add(symbol);
                }
            }

            Map<String, Double> priorHoldings = new LinkedHashMap<>(holdings);

            // --- 4. Build exit details before reassigning holdings. ---
            List<ExitDetail> exitDetails = new ArrayList<>();
            for (String symbol : exited) {
                double exitPrice = openAt(bars, executionIdx, symbol);
                Double qtyObj = priorHoldings.get(symbol);
                if (Double.isNaN(exitPrice) || qtyObj == null || qtyObj.isNaN()) {
                    continue;
                }
                double qty = qtyObj;
                Double entryPrice = entryPrices.get(symbol);
                double exitValue = qty * exitPrice;
                double realizedPnl = entryPrice != null ? (exitPrice - entryPrice) * qty : 0.0;
                exitDetails.add(new ExitDetail(symbol, entryPrice, exitPrice, qty, exitValue, realizedPnl));
            }

            // --- 5. Allocate capital to the selected symbols at the next open. ---
            List<EntryDetail> entryDetails = new ArrayList<>();
            double portfolioValueAfter;
            boolean cameFromCash = priorHoldings.isEmpty();
            double targetCapital = 0.0;
            double pendingUsed = 0.0;
            if (!selected.isEmpty()) {
                Map<String, Double> selectedPrices = new LinkedHashMap<>();
                for (String symbol : selected) {
                    double price = openAt(bars, executionIdx, symbol);
                    if (!Double.isNaN(price)) {
                        selectedPrices.put(symbol, price);
                    }
                }
                if (selectedPrices.isEmpty()) {
                    holdings = new LinkedHashMap<>();
                    stopBasis.clear();
                    peakPrices.clear();
                    deployedCapital = 0.0;
                    portfolioValueAfter = 0.0;
                } else {
                    // Positions are sized against the full book capacity (exit.n slots),
                    // not the number of names actually held this period, so capital is held
                    // in reserve for the buffer slots that fill as new top-N names rotate in
                    // while older names linger in the top exit.n. When exit.n == top.n the
                    // book is always full and no reserve cash is left (legacy behaviour).
                    double allocation;
                    if (!compound) {
                        targetCapital = capitalPerStock * selectedPrices.size();
                        allocation = capitalPerStock;
                    } else if (cameFromCash) {
                        // Deploy the account's actual equity (initial capital plus any
                        // accrued contributions already folded into accountEquity), not a
                        // phantom capitalPerStock * N that would conjure capital from nowhere.
                        pendingUsed = pendingContribution;
                        targetCapital = accountEquity;
                        pendingContribution = 0.0;
                        portfolioValueBefore = targetCapital;
                        allocation = targetCapital / exitN;
                    } else {
                        pendingUsed = pendingContribution;
                        targetCapital = portfolioValueBefore + pendingContribution;
                        pendingContribution = 0.0;
                        allocation = targetCapital / exitN;
                    }
                    Map<String, Double> newHoldings = new LinkedHashMap<>();
                    double deployed = 0.0;
                    for (Map.Entry<String, Double> entry : selectedPrices.entrySet()) {
                        double qty = floorToLot(allocation / entry.getValue());
                        newHoldings.put(entry.getKey(), qty);
                        deployed += qty * entry.getValue();
                    }
                    holdings = newHoldings;
                    // Reset the stop reference and trailing peak to this period's entry
                    // price for every held name (cost basis is re-struck each rebalance).
                    stopBasis.clear();
                    peakPrices.clear();
                    for (Map.Entry<String, Double> entry : selectedPrices.entrySet()) {
                        stopBasis.put(entry.getKey(), entry.getValue());
                        peakPrices.put(entry.getKey(), entry.getValue());
                    }
                    deployedCapital = deployed;
                    // Keep the undeployed whole-lot remainder as cash for next period.
                    cash = targetCapital - deployed;
                    portfolioValueAfter = deployed;
                }
            } else {
                holdings = new LinkedHashMap<>();
                stopBasis.clear();
                peakPrices.clear();
                deployedCapital = 0.0;
                portfolioValueAfter = 0.0;
            }

            for (String symbol : exited) {
                entryPrices.remove(symbol);
            }

            // --- 5b. Emit trades on a cash ledger: close positions before opening new. ---
            double runningCash = cameFromCash ? targetCapital : cashBeforeRebalance + pendingUsed;
            // (1) EXITs credit their proceeds to cash.
            for (ExitDetail detail : exitDetails) {
                double before = runningCash;
                runningCash += detail.exitValue;
                tradebookRows.add(new TradebookRow(executionDate, "EXIT", detail.symbol,
                        detail.quantity, detail.exitPrice, detail.exitValue, detail.entryPrice,
                        detail.exitPrice, detail.realizedPnl, round2(before), round2(runningCash),
                        signalDate, rebalanceNumber));
            }
            // (2) Re-weight held positions (no separate row): net cash impact only.
            double heldNetValue = 0.0;
            for (String symbol : held) {
                double price = openAt(bars, executionIdx, symbol);
                if (Double.isNaN(price)) {
                    continue;
                }
                Double oldQty = priorHoldings.get(symbol);
                Double newQty = holdings.get(symbol);
                heldNetValue += (newQty != null ? newQty : 0.0) * price;
                heldNetValue -= (oldQty != null ? oldQty : 0.0) * price;
            }
            runningCash -= heldNetValue;
            // (3) ENTRYs debit their cost from cash.
            for (String symbol : entered) {
                Double newQty = holdings.get(symbol);
                double price = openAt(bars, executionIdx, symbol);
                if (newQty == null || Double.isNaN(price)) {
                    continue;
                }
                double tradeValue = newQty * price;
                double before = runningCash;
                runningCash -= tradeValue;
                entryDetails.add(new EntryDetail(symbol, price, newQty, tradeValue));
                tradebookRows.add(new TradebookRow(executionDate, "ENTRY", symbol, newQty, price,
                        tradeValue, price, null, null, round2(before), round2(runningCash),
                        signalDate, rebalanceNumber));
                entryPrices.put(symbol, price);
            }

            // --- 6. Record and log. ---
            double capPerStock = selected.isEmpty() ? 0.0 : portfolioValueAfter / selected.size();
            records.add(new RebalanceRecord(executionDate, signalDate, round2(portfolioValueBefore),
                    round2(portfolioValueAfter), round2(accountEquity), round2(periodPnl), selected.size(),
                    round2(capPerStock), String.join(",", entered), String.join(",", exited),
                    String.join(",", held), round2(periodReturnPct)));
            equityRows.add(new EquityRow(executionDate, signalDate, round2(portfolioValueAfter),
                    round2(accountEquity), round2(periodPnl), round2(capPerStock), String.join(",", selected),
                    lookbackStart, previousRebalanceDate, round2(contribution)));

            periodSnapshots.add(new PeriodSnapshot(executionIdx, accountEquity, deployedCapital, cash,
                    new LinkedHashMap<>(holdings), new LinkedHashMap<>(stopBasis),
                    new LinkedHashMap<>(peakPrices), new LinkedHashMap<>(entryPrices), rebalanceNumber));

            // --- 6b. Portfolio snapshot for the holdings sheet. ---
            cumulativeGrowth *= (1.0 + periodReturnPct / 100.0);
            StringBuilder holdingsStr = new StringBuilder();
            for (Map.Entry<String, Double> h : holdings.entrySet()) {
                if (holdingsStr.length() > 0) {
                    holdingsStr.append('|');
                }
                holdingsStr.append(h.getKey()).append(':').append(String.format(Locale.US, "%.2f", h.getValue()));
            }
            double portfolioValue = deployedCapital + cash;
            double cumulativeReturnPct = (cumulativeGrowth - 1.0) * 100.0;
            holdingsRows.add(new HoldingsRow(executionDate, signalDate, rebalanceNumber, holdings.size(),
                    holdingsStr.toString(), round2(deployedCapital), round2(cash), round2(portfolioValue),
                    round2(accountEquity), round2(periodReturnPct), round2(cumulativeReturnPct)));

            if (verbose) {
                int sessionsSincePrevious = previousExecutionIdx < 0 ? 0 : executionIdx - previousExecutionIdx;
                logger.logRebalance(rebalanceNumber, signalDate, executionDate, lookbackStart, lookbackDays,
                        previousRebalanceDate, sessionsSincePrevious, performanceTable, entered, exited, held,
                        portfolioValueBefore, periodPnl, periodReturnPct, portfolioValueAfter, accountEquity,
                        contribution, entryDetails, exitDetails);
            }
            previousRebalanceDate = executionDate;
            previousExecutionIdx = executionIdx;
            currentHoldingsRebalanceNumber = rebalanceNumber;
            periodInvestedAtStart = deployedCapital;
            periodRealizedStopPnl = 0.0;
            }

            // Daily stop-loss / trailing-stop check on the current book: a breach on
            // this day's close exits at the next session's open, on any calendar day.
            if (!holdings.isEmpty()) {
                StopResult sr = applyStops(bars, day, day, dates, holdings, stopBasis, peakPrices,
                        entryPrices, currentHoldingsRebalanceNumber, cash, tradebookRows);
                cash = sr.cash;
                deployedCapital -= sr.basisRemoved;
                periodRealizedStopPnl += sr.realizedPnl;
            }
        }

        // --- Final mark-to-market status row: value current holdings at the latest close. ---
        if (!holdings.isEmpty() && dateCount > 0) {
            int lastIdx = dateCount - 1;
            LocalDate markDate = dates.get(lastIdx);
            // Stops were already applied day-by-day through the last date by the daily
            // driver, so the trailing window's realized P&L and cost-basis reduction are
            // already reflected in cash / deployedCapital and the period accumulators.
            double investedAtLastPeriodStart = periodInvestedAtStart;
            double lastRealizedStopPnl = periodRealizedStopPnl;
            double currentValue = 0.0;
            StringBuilder markStr = new StringBuilder();
            for (Map.Entry<String, Double> h : holdings.entrySet()) {
                double close = closeAt(bars, lastIdx, h.getKey());
                if (Double.isNaN(close)) {
                    continue;
                }
                currentValue += h.getValue() * close;
                if (markStr.length() > 0) {
                    markStr.append('|');
                }
                markStr.append(h.getKey()).append(':').append(String.format(Locale.US, "%.2f", h.getValue()));
            }
            double markPeriodPnl = (currentValue - deployedCapital) + lastRealizedStopPnl;
            double markPeriodReturnPct = investedAtLastPeriodStart == 0.0
                    ? 0.0
                    : (markPeriodPnl / investedAtLastPeriodStart) * 100.0;
            double markEquity = accountEquity + markPeriodPnl;
            double markCumulativeReturnPct =
                    (cumulativeGrowth * (1.0 + markPeriodReturnPct / 100.0) - 1.0) * 100.0;
            holdingsRows.add(new HoldingsRow(markDate, markDate, records.size(), holdings.size(),
                    markStr.toString(), round2(currentValue), round2(cash), round2(currentValue + cash),
                    round2(markEquity), round2(markPeriodReturnPct), round2(markCumulativeReturnPct)));
        }

        List<YearEndEquity> yearEndMarks = buildYearEndMarks(bars, dates, periodSnapshots, initialCapital);

        List<LookbackRow> lookbackRows = buildLookbackRows(bars, lookbackDays, minHistory, topN);
        return new BacktestResult(records, equityRows, performanceRows, tradebookRows, lookbackRows,
                holdingsRows, yearEndMarks, initialCapital);
    }

    /**
     * Mark the account to market at the last trading day of every calendar year the
     * backtest spans (for the still-running final year this is the latest session).
     * Each year end usually falls between two rebalances, so the holdings established
     * at the last rebalance on/before that day are revalued at its close — with any
     * intra-period stops replayed on a clone so real state is untouched. This lets the
     * yearly report strike returns at the true year boundary instead of at whichever
     * rebalance date sat nearest to it.
     */
    private List<YearEndEquity> buildYearEndMarks(DailyBars bars, List<LocalDate> dates,
                                                  List<PeriodSnapshot> snapshots, double initialCapital) {
        List<YearEndEquity> marks = new ArrayList<>();
        if (dates.isEmpty()) {
            return marks;
        }
        Map<Integer, Integer> lastIdxByYear = new LinkedHashMap<>();
        for (int i = 0; i < dates.size(); i++) {
            lastIdxByYear.put(dates.get(i).getYear(), i); // last write per year wins
        }
        for (Map.Entry<Integer, Integer> entry : lastIdxByYear.entrySet()) {
            int year = entry.getKey();
            int yearEndIdx = entry.getValue();
            PeriodSnapshot active = null;
            for (PeriodSnapshot snapshot : snapshots) {
                if (snapshot.executionIdx <= yearEndIdx) {
                    active = snapshot; // snapshots are in ascending execution order
                } else {
                    break;
                }
            }
            double equity = active == null
                    ? initialCapital // year end before the first investment
                    : markToMarket(bars, dates, active, yearEndIdx);
            marks.add(new YearEndEquity(year, dates.get(yearEndIdx), round2(equity)));
        }
        return marks;
    }

    /** Account equity if the given period's holdings are valued at {@code targetIdx}'s close. */
    private double markToMarket(DailyBars bars, List<LocalDate> dates, PeriodSnapshot snapshot, int targetIdx) {
        if (snapshot.holdings.isEmpty()) {
            return snapshot.accountEquity; // cash period: equity is fully tracked already
        }
        Map<String, Double> holdings = new LinkedHashMap<>(snapshot.holdings);
        Map<String, Double> stopBasis = new LinkedHashMap<>(snapshot.stopBasis);
        Map<String, Double> peakPrices = new LinkedHashMap<>(snapshot.peakPrices);
        Map<String, Double> entryPrices = new LinkedHashMap<>(snapshot.entryPrices);
        double deployedCapital = snapshot.deployedCapital;
        StopResult sr = applyStops(bars, snapshot.executionIdx, targetIdx, dates, holdings, stopBasis,
                peakPrices, entryPrices, snapshot.rebalanceNumber, snapshot.cash, new ArrayList<>());
        deployedCapital -= sr.basisRemoved;
        double value = 0.0;
        for (Map.Entry<String, Double> h : holdings.entrySet()) {
            double close = closeAt(bars, targetIdx, h.getKey());
            if (Double.isNaN(close)) {
                continue;
            }
            value += h.getValue() * close;
        }
        double markPeriodPnl = (value - deployedCapital) + sr.realizedPnl;
        return snapshot.accountEquity + markPeriodPnl;
    }

    private static final int FIRST_TRADING_DAY = 1;
    private static final int SECOND_REBALANCE_DAY_OF_MONTH = 11;

    static final String MODE_MONTHLY = "monthly";
    static final String MODE_WEEKLY = "weekly";
    static final String MODE_MONTHLY_TWICE = "monthly_twice";

    private static String scheduleLabel(String mode) {
        switch (mode) {
            case MODE_MONTHLY:
                return "monthly@trading-day-1";
            case MODE_WEEKLY:
                return "weekly@trading-day-1";
            case MODE_MONTHLY_TWICE:
            default:
                return "monthly@trading-day-1&11";
        }
    }

    /**
     * Signal indices for the configured schedule. Each signal is the session
     * immediately BEFORE a target trading day: the first trading day of the
     * period (week or month) and, for {@code monthly_twice}, also the 11th
     * trading day of the month. Ranking uses that prior close and the engine
     * executes at the target day's open (index {@code signalIdx + 1}), so a
     * switch is placed before the day trades.
     */
    private static List<Integer> computeRebalanceSignalIndices(List<LocalDate> dates, String mode) {
        TreeSet<Integer> signalIndices = new TreeSet<>();
        if (mode.equals(MODE_WEEKLY)) {
            Map<LocalDate, List<Integer>> byWeek = new LinkedHashMap<>();
            for (int i = 0; i < dates.size(); i++) {
                LocalDate day = dates.get(i);
                LocalDate weekStart = day.minusDays(day.getDayOfWeek().getValue() - 1L);
                byWeek.computeIfAbsent(weekStart, ignored -> new ArrayList<>()).add(i);
            }
            for (List<Integer> weekDays : byWeek.values()) {
                addRebalanceSignal(signalIndices, weekDays, FIRST_TRADING_DAY);
            }
        } else {
            Map<YearMonth, List<Integer>> byMonth = new LinkedHashMap<>();
            for (int i = 0; i < dates.size(); i++) {
                byMonth.computeIfAbsent(YearMonth.from(dates.get(i)), ignored -> new ArrayList<>()).add(i);
            }
            for (List<Integer> monthDays : byMonth.values()) {
                addRebalanceSignal(signalIndices, monthDays, FIRST_TRADING_DAY);
                if (mode.equals(MODE_MONTHLY_TWICE)) {
                    addRebalanceSignal(signalIndices, monthDays, SECOND_REBALANCE_DAY_OF_MONTH);
                }
            }
        }
        return new ArrayList<>(signalIndices);
    }

    private static void addRebalanceSignal(TreeSet<Integer> out, List<Integer> periodDays, int tradingDayOfPeriod) {
        int position = tradingDayOfPeriod - 1;
        if (position < 0 || position >= periodDays.size()) {
            return; // period has fewer trading days than requested position
        }
        int executionIdx = periodDays.get(position);
        int signalIdx = executionIdx - 1;
        if (signalIdx >= 0) {
            out.add(signalIdx);
        }
    }

    /**
     * Build the book for a rebalance. The top-N names (sector-capped) are always entered.
     * When {@code exitN > topN} the book additionally retains any currently-held name that
     * still ranks within the top {@code exitN} by momentum, so a holding is only dropped once
     * it falls out of the top {@code exitN} (never merely because it left the top-N). The book
     * is capped at {@code exitN} names and the sector cap is honoured when retaining buffers,
     * so two same-sector names never coexist. When {@code exitN == topN} this reduces to a
     * plain sector-capped top-N cut (legacy behaviour).
     */
    private List<String> selectHoldings(List<Candidate> ranked, java.util.Set<String> currentHoldings,
                                        int topN, int exitN) {
        List<String> selected = selectTopN(ranked, topN);
        if (exitN <= topN) {
            return selected;
        }
        selected = new ArrayList<>(selected);
        Map<String, Integer> perSector = new HashMap<>();
        for (String symbol : selected) {
            String sector = sectorBySymbol.get(symbol);
            if (sector != null) {
                perSector.merge(sector, 1, Integer::sum);
            }
        }
        int limit = Math.min(exitN, ranked.size());
        for (int i = 0; i < limit && selected.size() < exitN; i++) {
            Candidate c = ranked.get(i);
            if (!currentHoldings.contains(c.symbol) || selected.contains(c.symbol)) {
                continue; // only previously-held names may fill the buffer beyond the top-N
            }
            String sector = sectorBySymbol.get(c.symbol);
            if (maxPerSector > 0 && sector != null && perSector.getOrDefault(sector, 0) >= maxPerSector) {
                continue;
            }
            selected.add(c.symbol);
            if (sector != null) {
                perSector.merge(sector, 1, Integer::sum);
            }
        }
        return selected;
    }

    /**
     * Pick the top {@code topN} symbols from the ranked list, optionally capping how many
     * may come from a single sector. The cap keeps two highly-correlated same-theme names
     * out of a concentrated book: it takes the highest-ranked eligible symbol per sector
     * first and, only if that starves us of names, backfills with the next best regardless
     * of sector so a slot is never left empty. Symbols with no sector mapping are never
     * capped. When the cap is disabled (or no map is loaded) this is a plain top-N cut.
     */
    private List<String> selectTopN(List<Candidate> ranked, int topN) {
        if (maxPerSector <= 0 || sectorBySymbol.isEmpty()) {
            List<String> plain = new ArrayList<>();
            for (int i = 0; i < Math.min(topN, ranked.size()); i++) {
                plain.add(ranked.get(i).symbol);
            }
            return plain;
        }
        List<String> selected = new ArrayList<>();
        Map<String, Integer> perSector = new HashMap<>();
        for (Candidate c : ranked) {
            if (selected.size() >= topN) {
                break;
            }
            String sector = sectorBySymbol.get(c.symbol);
            if (sector != null && perSector.getOrDefault(sector, 0) >= maxPerSector) {
                continue;
            }
            selected.add(c.symbol);
            if (sector != null) {
                perSector.merge(sector, 1, Integer::sum);
            }
        }
        if (selected.size() < topN) {
            for (Candidate c : ranked) {
                if (selected.size() >= topN) {
                    break;
                }
                if (!selected.contains(c.symbol)) {
                    selected.add(c.symbol);
                }
            }
        }
        return selected;
    }

    /** Load a {@code symbol -> sector} map from a CSV with 'symbol' and 'sector' columns. */
    private static Map<String, String> loadSectorMap(Path file) {
        Map<String, String> map = new HashMap<>();
        if (file == null || !Files.exists(file)) {
            return map;
        }
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String header = reader.readLine();
            if (header == null) {
                return map;
            }
            String[] cols = header.split(",");
            int symCol = -1;
            int secCol = -1;
            for (int i = 0; i < cols.length; i++) {
                String h = cols[i].trim();
                if (h.equalsIgnoreCase("symbol")) {
                    symCol = i;
                } else if (h.equalsIgnoreCase("sector")) {
                    secCol = i;
                }
            }
            if (symCol < 0 || secCol < 0) {
                throw new IllegalArgumentException(
                        "Sector file must include 'symbol' and 'sector' columns: " + file);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",", -1);
                if (parts.length > Math.max(symCol, secCol)) {
                    String symbol = parts[symCol].trim();
                    String sector = parts[secCol].trim();
                    if (!symbol.isEmpty() && !sector.isEmpty()) {
                        map.put(symbol, sector);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read sector file: " + file, e);
        }
        return map;
    }

    /** Rank all eligible symbols by trailing lookback return (desc), ties by symbol (asc). */
    private List<Candidate> rankUniverse(DailyBars bars, int signalIdx, int referenceIdx, int minHistory) {
        List<Candidate> candidates = new ArrayList<>();
        if (referenceIdx < 0) {
            return candidates; // insufficient history for everyone (guaranteed cash period)
        }
        List<String> symbols = bars.symbols();
        for (int s = 0; s < symbols.size(); s++) {
            double current = bars.closeAt(signalIdx, s);
            double lookback = bars.closeAt(referenceIdx, s);
            int history = bars.eligibilityAt(signalIdx, s);
            boolean eligible = !Double.isNaN(current) && !Double.isNaN(lookback)
                    && lookback > 0 && history >= minHistory;
            if (!eligible) {
                continue;
            }
            double returnPct = ((current / lookback) - 1.0) * 100.0;
            candidates.add(new Candidate(symbols.get(s), returnPct, lookback, current, history));
        }
        candidates.sort(Comparator
                .comparingDouble((Candidate c) -> c.returnPct).reversed()
                .thenComparing(c -> c.symbol));
        return candidates;
    }

    private List<LookbackRow> buildLookbackRows(DailyBars bars, int lookbackDays, int minHistory, int topN) {
        List<LookbackRow> rows = new ArrayList<>();
        List<LocalDate> dates = bars.dates();
        if (dates.isEmpty()) {
            return rows;
        }

        int startSignalIdx = Math.max(lookbackDays - 1, dates.size() - 30);
        for (int signalIdx = startSignalIdx; signalIdx < dates.size(); signalIdx++) {
            int referenceIdx = signalIdx - (lookbackDays - 1);
            List<Candidate> ranked = rankUniverse(bars, signalIdx, referenceIdx, minHistory);
            LocalDate signalDate = dates.get(signalIdx);
            LocalDate lookbackStart = referenceIdx >= 0 ? dates.get(referenceIdx) : null;
            LocalDate executionDate = signalIdx + 1 < dates.size() ? dates.get(signalIdx + 1) : null;

            for (int i = 0; i < ranked.size(); i++) {
                Candidate candidate = ranked.get(i);
                rows.add(new LookbackRow(signalDate, executionDate, lookbackStart, i + 1,
                        candidate.symbol, candidate.lookbackPrice, candidate.currentPrice,
                        candidate.returnPct, candidate.historyDays, i < topN));
            }
        }
        return rows;
    }

    /**
     * Apply the configured stop-loss / trailing-stop rules to the current holdings
     * for each trading day in {@code [fromIdx, toIdxInclusive]}. A position is flagged
     * the first day its <em>close</em> breaches the stop level and is then sold at the
     * <em>next</em> session's open (never same-day), so the rule reacts to confirmed
     * end-of-day weakness rather than intraday noise. Proceeds are credited to cash and
     * a {@code STOP} tradebook row emitted. The passed maps are mutated in place; the
     * returned {@link StopResult} carries the updated cash, the realized P&L (measured
     * from the period entry basis) and the cost basis removed from {@code deployedCapital}.
     */
    private StopResult applyStops(DailyBars bars, int fromIdx, int toIdxInclusive, List<LocalDate> dates,
                                  Map<String, Double> holdings, Map<String, Double> stopBasis,
                                  Map<String, Double> peakPrices, Map<String, Double> entryPrices,
                                  int owningRebalanceNumber, double startingCash,
                                  List<TradebookRow> tradebookRows) {
        StopResult result = new StopResult();
        result.cash = startingCash;
        double stopLossPct = config.stopLossPct();
        double trailingStopPct = config.trailingStopPct();
        if ((stopLossPct <= 0.0 && trailingStopPct <= 0.0) || holdings.isEmpty()) {
            return result;
        }
        List<String> symbols = bars.symbols();
        int lastIdx = dates.size() - 1;
        for (int d = fromIdx; d <= toIdxInclusive; d++) {
            if (holdings.isEmpty()) {
                break;
            }
            int fillIdx = d + 1;
            boolean canFill = fillIdx <= lastIdx; // need a next session to exit at its open
            for (String symbol : new ArrayList<>(holdings.keySet())) {
                int col = symbols.indexOf(symbol);
                if (col < 0) {
                    continue;
                }
                double basis = stopBasis.getOrDefault(symbol, entryPrices.getOrDefault(symbol, Double.NaN));
                if (Double.isNaN(basis)) {
                    continue;
                }
                // Trailing peak tracks the highest CLOSE through the prior session; the
                // stop is evaluated against today's close, so nothing exits on its own bar.
                double peak = peakPrices.getOrDefault(symbol, basis);
                double stopLevel = Double.NEGATIVE_INFINITY;
                if (stopLossPct > 0.0) {
                    stopLevel = Math.max(stopLevel, basis * (1.0 - stopLossPct / 100.0));
                }
                if (trailingStopPct > 0.0) {
                    stopLevel = Math.max(stopLevel, peak * (1.0 - trailingStopPct / 100.0));
                }
                double close = bars.closeAt(d, col);
                boolean breached = stopLevel != Double.NEGATIVE_INFINITY
                        && !Double.isNaN(close) && close <= stopLevel;
                double fillOpen = canFill ? bars.openAt(fillIdx, col) : Double.NaN;
                if (!breached || !canFill || Double.isNaN(fillOpen)) {
                    if (!Double.isNaN(close) && close > peak) {
                        peakPrices.put(symbol, close); // roll the peak forward on today's close
                    }
                    continue;
                }
                double qty = holdings.get(symbol);
                double proceeds = qty * fillOpen;
                double before = result.cash;
                result.cash += proceeds;
                result.basisRemoved += qty * basis;
                result.realizedPnl += proceeds - qty * basis;
                Double entry = entryPrices.get(symbol);
                double reportedPnl = entry != null ? (fillOpen - entry) * qty : 0.0;
                tradebookRows.add(new TradebookRow(dates.get(fillIdx), "STOP", symbol, qty, fillOpen,
                        round2(proceeds), entry, fillOpen, round2(reportedPnl), round2(before),
                        round2(result.cash), dates.get(d), owningRebalanceNumber));
                holdings.remove(symbol);
                stopBasis.remove(symbol);
                peakPrices.remove(symbol);
                entryPrices.remove(symbol);
            }
        }
        return result;
    }

    private static double openAt(DailyBars bars, int dateIdx, String symbol) {
        int idx = bars.symbols().indexOf(symbol);
        if (idx < 0) {
            return Double.NaN;
        }
        return bars.openAt(dateIdx, idx);
    }

    private static double closeAt(DailyBars bars, int dateIdx, String symbol) {
        int idx = bars.symbols().indexOf(symbol);
        if (idx < 0) {
            return Double.NaN;
        }
        return bars.closeAt(dateIdx, idx);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** Round a tradable quantity down to the nearest whole share (e.g. 65.5 -> 65). */
    private static double floorToLot(double quantity) {
        return Math.floor(quantity);
    }

    /** Mutable outcome of an intra-period stop scan over one holding window. */
    private static final class StopResult {
        double cash;          // running cash after crediting stop proceeds
        double realizedPnl;   // realized P&L booked by stops (from period entry basis)
        double basisRemoved;  // cost basis removed from deployedCapital
    }

    /** Immutable post-rebalance portfolio state used to mark the book at any later date. */
    private static final class PeriodSnapshot {
        final int executionIdx;
        final double accountEquity;
        final double deployedCapital;
        final double cash;
        final Map<String, Double> holdings;
        final Map<String, Double> stopBasis;
        final Map<String, Double> peakPrices;
        final Map<String, Double> entryPrices;
        final int rebalanceNumber;

        PeriodSnapshot(int executionIdx, double accountEquity, double deployedCapital, double cash,
                       Map<String, Double> holdings, Map<String, Double> stopBasis,
                       Map<String, Double> peakPrices, Map<String, Double> entryPrices, int rebalanceNumber) {
            this.executionIdx = executionIdx;
            this.accountEquity = accountEquity;
            this.deployedCapital = deployedCapital;
            this.cash = cash;
            this.holdings = holdings;
            this.stopBasis = stopBasis;
            this.peakPrices = peakPrices;
            this.entryPrices = entryPrices;
            this.rebalanceNumber = rebalanceNumber;
        }
    }

    private static final class Candidate {
        final String symbol;
        final double returnPct;
        final double lookbackPrice;
        final double currentPrice;
        final int historyDays;

        Candidate(String symbol, double returnPct, double lookbackPrice, double currentPrice, int historyDays) {
            this.symbol = symbol;
            this.returnPct = returnPct;
            this.lookbackPrice = lookbackPrice;
            this.currentPrice = currentPrice;
            this.historyDays = historyDays;
        }
    }
}
