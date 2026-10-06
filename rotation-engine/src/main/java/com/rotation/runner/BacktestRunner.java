package com.rotation.runner;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.rotation.config.RotationConfig;
import com.rotation.execution.BacktestExecution;
import com.rotation.execution.ExecutionModel;
import com.rotation.execution.ExecutionResult;
import com.rotation.execution.PendingRebalance;
import com.rotation.execution.StopExecutionResult;
import com.rotation.market.MarketData;
import com.rotation.market.MarketView;
import com.rotation.model.BacktestResult;
import com.rotation.model.DailyBars;
import com.rotation.model.DailyMark;
import com.rotation.model.EntryDetail;
import com.rotation.model.ExitDetail;
import com.rotation.model.LookbackRow;
import com.rotation.model.PerformanceRow;
import com.rotation.model.TradebookRow;
import com.rotation.model.YearEndEquity;
import com.rotation.portfolio.FinalPortfolioMark;
import com.rotation.portfolio.Ledger;
import com.rotation.portfolio.Portfolio;
import com.rotation.portfolio.RebalanceEvent;
import com.rotation.report.BacktestReportBuilder;
import com.rotation.report.BacktestReports;
import com.rotation.report.RebalanceLogger;
import com.rotation.strategy.Candidate;
import com.rotation.strategy.Diagnostic;
import com.rotation.strategy.OrderIntent;
import com.rotation.strategy.RotationStrategies;
import com.rotation.strategy.Strategy;
import com.rotation.strategy.StrategyContext;

/**
 * Fixed-schedule top-N momentum rotation engine.
 *
 * <p>On each rebalance the universe is ranked by trailing lookback return using
 * the period-end close; positions are then taken at the <em>next</em> session's
 * open. A symbol is only eligible once it has at least {@code minHistoryDays}
 * (>= lookback) of tracked daily bars. Capital either compounds (reinvest the
 * latest portfolio value) or redeploys a fixed principal each period.
 */
public final class BacktestRunner {

    private final RotationConfig config;
    private final RebalanceLogger logger = new RebalanceLogger();
    private final Strategy strategy;
    private final BacktestExecution execution;
    private final ExecutionModel executionModel;
    private int diagnosticsReadIndex;

    public BacktestRunner(RotationConfig config) {
        this(config, RotationStrategies.create(config));
    }

    /** Run with an explicitly supplied strategy (bypasses the {@code strategy} config key). */
    public BacktestRunner(RotationConfig config, Strategy strategy) {
        this.config = config;
        this.strategy = strategy;
        this.execution = new BacktestExecution(strategy.exitPolicy());
        this.executionModel = execution;
        this.diagnosticsReadIndex = 0;
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
        MarketData market = new MarketData(bars);
        int dateCount = dates.size();
        if (dateCount <= lookbackDays) {
            throw new IllegalArgumentException("Not enough history to run the requested lookback window.");
        }

        // The bars carry the full history; sessions before start.date are warm-up
        // only (lookback ranking + min-history eligibility) and are never traded.
        int tradeStartIdx = tradeStartIndex(dates, config.startDate());

        // Fixed trading-session cadence (rebalance.mode), anchored on the first session
        // with a complete lookback. Each decision is ranked on the prior session's close
        // and executed at the next open, so switches happen before the day trades.
        int firstSignal = Math.max(tradeStartIdx - 1, strategy.warmupSessions() - 1);
        int lastSignal = dateCount - 2;

        if (verbose) {
            logger.logRunHeader(bars.symbolCount(), dateCount - tradeStartIdx, dates.get(tradeStartIdx),
                    dates.get(dateCount - 1), topN, lookbackDays, strategy.name(), minHistory, allocationMode);
        }

        List<PerformanceRow> performanceRows = new ArrayList<>();
        Ledger ledger = new Ledger();
        List<TradebookRow> tradebookRows = ledger.executionTradebookRows();
        // Post-rebalance portfolio state captured for each period so any calendar
        // date can later be marked to market by replaying stops onto a clone.
        List<PeriodSnapshot> periodSnapshots = new ArrayList<>();
        // Every position change (incl. held-name re-weights) and the end-of-day book,
        // so the daily valuation report can replay the run without re-deriving it.
        Portfolio portfolio = new Portfolio(initialCapital, ledger);
        strategy.init(new StrategyContext(tradeStartIdx, firstSignal, lastSignal, minHistory));
        diagnosticsReadIndex = 0;
        Map<String, Double> holdings = portfolio.quantities();
        int currentHoldingsRebalanceNumber = 0; // rebalance that established the current holdings
        double cumulativeGrowth = 1.0; // compounded product of per-period holding returns
        LocalDate previousRebalanceDate = null;
        int previousExecutionIdx = -1;
        YearMonth lastContributionMonth = null;
        double pendingContribution = 0.0;

        Map<Integer, PendingClose> pendingBySignal = new HashMap<>();
        int reportWarmupStart = Math.max(strategy.warmupSessions() - 1, dateCount - 30);
        int preTradeSignalStart = Math.max(0, Math.min(reportWarmupStart, firstSignal));
        for (int signal = preTradeSignalStart; signal < tradeStartIdx; signal++) {
            PendingClose close = evaluateClose(market.asOf(signal), portfolio);
            if (close.rebalanceEvent) {
                pendingBySignal.put(signal, close);
            }
        }
        double periodRealizedStopPnl = 0.0; // stops booked since the current holdings were established
        double periodInvestedAtStart = 0.0; // deployed capital at the last rebalance (period P&L base)

        // Daily driver: walk every trading day in order. A scheduled rebalance fires
        // on its execution day (the session after a signal day) and stop-losses are
        // evaluated on every day's close, filled at the next session's open.
        for (int day = tradeStartIdx; day < dateCount; day++) {
            double contributionToday = 0.0;
            if (day >= 1 && pendingBySignal.containsKey(day - 1)) {
            PendingClose pendingClose = pendingBySignal.remove(day - 1);
            int signalIdx = day - 1;
            int referenceIdx = signalIdx - (lookbackDays - 1);
            int executionIdx = day;
            LocalDate signalDate = dates.get(signalIdx);
            LocalDate executionDate = dates.get(executionIdx);
            double cashBeforeRebalance = portfolio.cash(); // remainder + stop proceeds accrued this period

            // Add a fixed monthly cash top-up on the first rebalance of each new
            // calendar month (SIP-style). The cash is credited to account equity
            // immediately (even during a cash period) and held as pending cash
            // until it can be deployed. It is tracked separately from trading P&L.
            // A session-count cadence can skip a short month, so every calendar
            // month elapsed since the last rebalance is credited.
            double contribution = 0.0;
            YearMonth executionMonth = YearMonth.from(executionDate);
            if (compound && monthlyContribution > 0.0 && lastContributionMonth != null
                    && !executionMonth.equals(lastContributionMonth)) {
                contribution = monthlyContribution * lastContributionMonth.until(executionMonth, ChronoUnit.MONTHS);
                pendingContribution += contribution;
            }
            lastContributionMonth = executionMonth;
            contributionToday = contribution;
            int rebalanceNumber = ledger.rebalances().size() + 1;

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
                portfolioValueBefore = portfolio.accountEquity() + portfolio.cash();
                periodPnl = 0.0;
                periodReturnPct = 0.0;
            } else {
                double value = execution.valueAndDropAtOpen(executionDate, signalDate,
                        rebalanceNumber, executionIdx, bars, portfolio, ledger);
                // Total period P&L = unrealized move on surviving holdings plus any
                // realized gain/loss booked by intra-period stops. Carried cash (incl.
                // stop proceeds) is added back so it is re-deployed next period.
                periodPnl = (value - portfolio.deployedCapital()) + realizedStopPnl;
                portfolioValueBefore = value + portfolio.cash();
                periodReturnPct = investedAtPeriodStart == 0.0
                        ? 0.0
                        : (periodPnl / investedAtPeriodStart) * 100.0;
                portfolio.setAccountEquity(portfolio.accountEquity() + periodPnl);
            }
            portfolio.setCash(0.0); // consumed into portfolioValueBefore / target capital below

            // Credit this month's contribution as fresh cash on this rebalance.
            portfolio.setAccountEquity(portfolio.accountEquity() + contribution);

            // --- 2. Rank the eligible universe and pick the book. Always enter the
            //        top-N; when exit.n > top.n, retain currently-held names until they
            //        fall out of the top exit.n (the book may grow up to exit.n). ---
            List<Candidate> ranked = candidatesFrom(pendingClose.diagnostics);
            List<String> selected = selectedFrom(pendingClose.intents);
            TreeSet<String> selectedSet = new TreeSet<>(selected);
            Map<String, Integer> rankBySymbol = ranksFrom(pendingClose.diagnostics);
            // The sector-capped top-N cut (always entered); anything else selected is an exit buffer hold.
            HashSet<String> topNSelection = coreFrom(pendingClose.diagnostics);

            List<PerformanceRow> performanceTable = new ArrayList<>();
            LocalDate lookbackStart = referenceIdx >= 0 ? dates.get(referenceIdx) : null;
            for (Diagnostic diagnostic : pendingClose.diagnostics) {
                if (diagnostic.kind != Diagnostic.Kind.REBALANCE || diagnostic.rank <= 0) {
                    continue;
                }
                Candidate c = new Candidate(diagnostic.symbol, diagnostic.score,
                        diagnostic.referencePrice, diagnostic.currentPrice, diagnostic.historyDays);
                PerformanceRow row = new PerformanceRow(rebalanceNumber, signalDate, executionDate,
                        lookbackStart, diagnostic.rank, c.symbol, c.referencePrice, c.currentPrice,
                        c.score, c.historyDays, diagnostic.selected);
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
            List<ExitDetail> exitDetails = execution.exitDetailsAtOpen(
                    executionIdx, bars, exited, priorHoldings, portfolio);

            // --- 5. Allocate capital to the selected symbols at the next open. ---
            double portfolioValueAfter;
            boolean cameFromCash = priorHoldings.isEmpty();
            double targetCapital = 0.0;
            double pendingUsed = 0.0;
            if (!selected.isEmpty()) {
                Map<String, Double> selectedPrices = execution.selectedOpenPrices(selected, executionIdx, bars);
                if (selectedPrices.isEmpty()) {
                    execution.allocateAtOpen(selectedPrices, 0.0, 0.0, portfolio);
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
                        targetCapital = portfolio.accountEquity();
                        pendingContribution = 0.0;
                        portfolioValueBefore = targetCapital;
                        allocation = targetCapital / exitN;
                    } else {
                        pendingUsed = pendingContribution;
                        targetCapital = portfolioValueBefore + pendingContribution;
                        pendingContribution = 0.0;
                        allocation = targetCapital / exitN;
                    }
                    portfolioValueAfter = execution.allocateAtOpen(
                            selectedPrices, allocation, targetCapital, portfolio);
                }
            } else {
                execution.allocateAtOpen(Map.of(), 0.0, 0.0, portfolio);
                portfolioValueAfter = 0.0;
            }

                    PendingRebalance pending = new PendingRebalance(signalDate, executionIdx, rebalanceNumber,
                    entered, exited, held, priorHoldings, new LinkedHashMap<>(holdings), exitDetails, ranked,
                    rankBySymbol, topNSelection, targetCapital, cashBeforeRebalance, pendingUsed,
                    cameFromCash, topN, exitN);
                ExecutionResult executionResult = executionModel.executeAtOpen(
                    executionDate, pending, portfolio, bars);
                List<EntryDetail> entryDetails = executionResult.entries();

            // --- 6. Record and log. ---
            double capPerStock = selected.isEmpty() ? 0.0 : portfolioValueAfter / selected.size();
                periodSnapshots.add(new PeriodSnapshot(executionIdx, portfolio.copy()));

            // --- 6b. Portfolio snapshot for the holdings sheet. ---
            cumulativeGrowth *= (1.0 + periodReturnPct / 100.0);
            double portfolioValue = portfolio.deployedCapital() + portfolio.cash();
            double cumulativeReturnPct = (cumulativeGrowth - 1.0) * 100.0;
            ledger.recordRebalance(new RebalanceEvent(executionDate, signalDate, lookbackStart,
                    previousRebalanceDate, selected, String.join(",", entered),
                    String.join(",", exited), String.join(",", held), selected.size(),
                    portfolioValueBefore, portfolioValueAfter, portfolio.accountEquity(), periodPnl,
                    capPerStock, periodReturnPct, contribution, holdings,
                    portfolio.deployedCapital(), portfolio.cash(), portfolioValue, cumulativeReturnPct));

            if (verbose) {
                int sessionsSincePrevious = previousExecutionIdx < 0 ? 0 : executionIdx - previousExecutionIdx;
                logger.logRebalance(rebalanceNumber, signalDate, executionDate, lookbackStart, lookbackDays,
                        previousRebalanceDate, sessionsSincePrevious, performanceTable, entered, exited, held,
                        portfolioValueBefore, periodPnl, periodReturnPct, portfolioValueAfter, portfolio.accountEquity(),
                        contribution, entryDetails, exitDetails);
            }
            previousRebalanceDate = executionDate;
            previousExecutionIdx = executionIdx;
            currentHoldingsRebalanceNumber = rebalanceNumber;
            portfolio.setRebalanceNumber(rebalanceNumber);
            periodInvestedAtStart = portfolio.deployedCapital();
            periodRealizedStopPnl = 0.0;
            }

            // End-of-day mark: the book after this session's open executions (rebalance
            // and stops filled at this open), valued at this close with the engine's
            // mark-to-market formula. Stops triggered by this close fill at the next
            // open, so they are applied only after the mark.
            double markValue = 0.0;
            for (Map.Entry<String, Double> h : holdings.entrySet()) {
                double close = closeAt(bars, day, h.getKey());
                if (!Double.isNaN(close)) {
                    markValue += h.getValue() * close;
                }
            }
                ledger.recordMark(new DailyMark(dates.get(day),
                    portfolio.accountEquity() + (markValue - portfolio.deployedCapital()) + periodRealizedStopPnl,
                    contributionToday, currentHoldingsRebalanceNumber, holdings));

            // Daily stop-loss / trailing-stop check on the current book: a breach on
            // this day's close exits at the next session's open, on any calendar day.
            if (!holdings.isEmpty()) {
                StopExecutionResult sr = executionModel.checkProtectiveStops(dates.get(day), portfolio, bars);
                portfolio.setDeployedCapital(portfolio.deployedCapital() - sr.basisRemoved);
                periodRealizedStopPnl += sr.realizedPnl;
            }

            if (day >= firstSignal) {
                PendingClose close = evaluateClose(market.asOf(day), portfolio);
                if (close.rebalanceEvent) {
                    pendingBySignal.put(day, close);
                }
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
            for (Map.Entry<String, Double> h : holdings.entrySet()) {
                double close = closeAt(bars, lastIdx, h.getKey());
                if (Double.isNaN(close)) {
                    continue;
                }
                currentValue += h.getValue() * close;
            }
            double markPeriodPnl = (currentValue - portfolio.deployedCapital()) + lastRealizedStopPnl;
            double markPeriodReturnPct = investedAtLastPeriodStart == 0.0
                    ? 0.0
                    : (markPeriodPnl / investedAtLastPeriodStart) * 100.0;
            double markEquity = portfolio.accountEquity() + markPeriodPnl;
            double markCumulativeReturnPct =
                    (cumulativeGrowth * (1.0 + markPeriodReturnPct / 100.0) - 1.0) * 100.0;
            ledger.recordFinalMark(new FinalPortfolioMark(markDate, ledger.rebalances().size(),
                    holdings, currentValue, portfolio.cash(), currentValue + portfolio.cash(),
                    markEquity, markPeriodReturnPct, markCumulativeReturnPct));
        }

        List<YearEndEquity> yearEndMarks = buildYearEndMarks(bars, dates, tradeStartIdx, periodSnapshots,
                initialCapital);
        yearEndMarks.forEach(ledger::recordYearEndMark);

        List<LookbackRow> lookbackRows = buildLookbackRows(market, strategy.diagnostics());
        BacktestReports reports = new BacktestReportBuilder().build(ledger);
        return new BacktestResult(reports.rebalances(), reports.equityRows(), performanceRows,
                tradebookRows, lookbackRows, reports.holdingsRows(), reports.yearEndMarks(),
                initialCapital, ledger);
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
    private List<YearEndEquity> buildYearEndMarks(DailyBars bars, List<LocalDate> dates, int tradeStartIdx,
                                                  List<PeriodSnapshot> snapshots, double initialCapital) {
        List<YearEndEquity> marks = new ArrayList<>();
        if (dates.isEmpty()) {
            return marks;
        }
        Map<Integer, Integer> lastIdxByYear = new LinkedHashMap<>();
        for (int i = tradeStartIdx; i < dates.size(); i++) { // warm-up years are not reported
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
                    : markToMarket(bars, active, yearEndIdx);
            marks.add(new YearEndEquity(year, dates.get(yearEndIdx), round2(equity)));
        }
        return marks;
    }

    /** Account equity if the given period's holdings are valued at {@code targetIdx}'s close. */
    private double markToMarket(DailyBars bars, PeriodSnapshot snapshot, int targetIdx) {
        Portfolio replay = snapshot.portfolio.copy();
        if (replay.positions().isEmpty()) {
            return replay.accountEquity(); // cash period: equity is fully tracked already
        }
        // Replay stops triggered by closes BEFORE the target session only: those fill at
        // or before the target open. A stop triggered by the target close fills at the
        // next open, so the position is still held (and valued) at the target close;
        // replaying it here would book a future open price into this mark.
        StopExecutionResult sr = execution.replayProtectiveStops(
            snapshot.executionIdx, targetIdx - 1, replay, bars);
        double deployedCapital = replay.deployedCapital() - sr.basisRemoved;
        double value = 0.0;
        for (Map.Entry<String, Double> h : replay.quantities().entrySet()) {
            double close = closeAt(bars, targetIdx, h.getKey());
            if (Double.isNaN(close)) {
                continue;
            }
            value += h.getValue() * close;
        }
        double markPeriodPnl = (value - deployedCapital) + sr.realizedPnl;
        return replay.accountEquity() + markPeriodPnl;
    }

    /**
     * Index of the first session on/after {@code startDate} (0 when unset). Earlier
     * sessions only feed the lookback window and eligibility counts.
     */
    private static int tradeStartIndex(List<LocalDate> dates, LocalDate startDate) {
        if (startDate == null) {
            return 0;
        }
        for (int i = 0; i < dates.size(); i++) {
            if (!dates.get(i).isBefore(startDate)) {
                return i;
            }
        }
        throw new IllegalArgumentException("No data available on/after start date: " + startDate);
    }

    private PendingClose evaluateClose(MarketView market, Portfolio portfolio) {
        List<OrderIntent> intents = execution.collectCloseIntents(strategy, market,
                portfolio);
        List<Diagnostic> allDiagnostics = strategy.diagnostics();
        List<Diagnostic> emitted = List.copyOf(allDiagnostics.subList(diagnosticsReadIndex,
                allDiagnostics.size()));
        diagnosticsReadIndex = allDiagnostics.size();
        boolean rebalanceEvent = emitted.stream().anyMatch(diagnostic ->
                diagnostic.kind == Diagnostic.Kind.REBALANCE && diagnostic.rebalanceEvent);
        return new PendingClose(intents, emitted, rebalanceEvent);
    }

    private static List<String> selectedFrom(List<OrderIntent> intents) {
        List<String> selected = new ArrayList<>();
        for (OrderIntent intent : intents) {
            if (intent.kind == OrderIntent.Kind.ENTER
                    || intent.kind == OrderIntent.Kind.TARGET_ALLOCATION) {
                selected.add(intent.symbol);
            }
        }
        return selected;
    }

    private static List<Candidate> candidatesFrom(List<Diagnostic> diagnostics) {
        List<Candidate> ranked = new ArrayList<>();
        diagnostics.stream()
                .filter(diagnostic -> diagnostic.kind == Diagnostic.Kind.REBALANCE && diagnostic.rank > 0)
                .sorted(java.util.Comparator.comparingInt(diagnostic -> diagnostic.rank))
                .forEach(diagnostic -> ranked.add(new Candidate(diagnostic.symbol, diagnostic.score,
                        diagnostic.referencePrice, diagnostic.currentPrice, diagnostic.historyDays)));
        return ranked;
    }

    private static Map<String, Integer> ranksFrom(List<Diagnostic> diagnostics) {
        Map<String, Integer> ranks = new HashMap<>();
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic.kind == Diagnostic.Kind.REBALANCE && diagnostic.rank > 0) {
                ranks.put(diagnostic.symbol, diagnostic.rank);
            }
        }
        return ranks;
    }

    private static HashSet<String> coreFrom(List<Diagnostic> diagnostics) {
        HashSet<String> core = new HashSet<>();
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic.kind == Diagnostic.Kind.REBALANCE && diagnostic.coreSelected) {
                core.add(diagnostic.symbol);
            }
        }
        return core;
    }

    private static List<LookbackRow> buildLookbackRows(MarketData market,
                                                       List<Diagnostic> diagnostics) {
        List<LookbackRow> rows = new ArrayList<>();
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic.kind != Diagnostic.Kind.LOOKBACK) {
                continue;
            }
            LocalDate executionDate = diagnostic.signalIndex + 1 < market.sessionCount()
                    ? market.sessionDate(diagnostic.signalIndex + 1) : null;
            rows.add(new LookbackRow(diagnostic.signalDate, executionDate,
                    diagnostic.lookbackStart,
                    diagnostic.rank, diagnostic.symbol, diagnostic.referencePrice,
                    diagnostic.currentPrice, diagnostic.score, diagnostic.historyDays,
                    diagnostic.selected));
        }
        return rows;
    }

    private static final class PendingClose {
        final List<OrderIntent> intents;
        final List<Diagnostic> diagnostics;
        final boolean rebalanceEvent;

        PendingClose(List<OrderIntent> intents, List<Diagnostic> diagnostics, boolean rebalanceEvent) {
            this.intents = intents;
            this.diagnostics = diagnostics;
            this.rebalanceEvent = rebalanceEvent;
        }
    }

    private static double closeAt(DailyBars bars, int dateIdx, String symbol) {
        int idx = bars.indexOfSymbol(symbol);
        if (idx < 0) {
            return Double.NaN;
        }
        return bars.closeAt(dateIdx, idx);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** Immutable post-rebalance portfolio state used to mark the book at any later date. */
    private static final class PeriodSnapshot {
        final int executionIdx;
        final Portfolio portfolio;

        PeriodSnapshot(int executionIdx, Portfolio portfolio) {
            this.executionIdx = executionIdx;
            this.portfolio = portfolio;
        }
    }
}
