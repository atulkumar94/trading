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
import com.rotation.report.RebalanceLogger;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
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

    public RotationEngine(RotationConfig config) {
        this.config = config;
    }

    public BacktestResult run(DailyBars bars) {
        int lookbackDays = config.lookbackDays();
        int topN = config.topN();
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

        Map<String, Double> holdings = new LinkedHashMap<>(); // symbol -> quantity
        Map<String, Double> entryPrices = new LinkedHashMap<>();
        double accountEquity = initialCapital;
        double deployedCapital = 0.0;
        double cash = 0.0; // undeployed cash carried between rebalances (e.g. whole-lot remainder)
        double cumulativeGrowth = 1.0; // compounded product of per-period holding returns
        LocalDate previousRebalanceDate = null;
        int previousExecutionIdx = -1;
        YearMonth lastContributionMonth = null;
        double pendingContribution = 0.0;

        for (int signalIdx : rebalancePoints) {
            int referenceIdx = signalIdx - (lookbackDays - 1);
            int executionIdx = signalIdx + 1;
            LocalDate signalDate = dates.get(signalIdx);
            LocalDate executionDate = dates.get(executionIdx);
            double openingCash = cash; // idle cash carried into this rebalance (before liquidation)

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

            // --- 1. Value existing holdings at the execution (next) open. ---
            double portfolioValueBefore;
            double periodPnl;
            double periodReturnPct;
            if (holdings.isEmpty()) {
                portfolioValueBefore = (deployedCapital > 0 ? deployedCapital : accountEquity) + cash;
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
                // Holdings value drives P&L; carried cash is added back so the
                // whole-lot remainder is re-invested next period instead of lost.
                periodPnl = value - deployedCapital;
                portfolioValueBefore = value + cash;
                periodReturnPct = deployedCapital == 0.0
                        ? 0.0
                        : ((value / deployedCapital) - 1.0) * 100.0;
                accountEquity += periodPnl;
            }
            cash = 0.0; // consumed into portfolioValueBefore / target capital below

            // Credit this month's contribution as fresh cash on this rebalance.
            accountEquity += contribution;

            // --- 2. Rank the eligible universe and pick the top N. ---
            List<Candidate> ranked = rankUniverse(bars, signalIdx, referenceIdx, minHistory);
            List<String> selected = new ArrayList<>();
            for (int i = 0; i < Math.min(topN, ranked.size()); i++) {
                selected.add(ranked.get(i).symbol);
            }
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
                    deployedCapital = 0.0;
                    portfolioValueAfter = 0.0;
                } else {
                    if (!compound) {
                        targetCapital = capitalPerStock * selectedPrices.size();
                    } else if (cameFromCash) {
                        // Deploy the account's actual equity (initial capital plus any
                        // accrued contributions already folded into accountEquity), not a
                        // phantom capitalPerStock * N that would conjure capital from nowhere.
                        pendingUsed = pendingContribution;
                        targetCapital = accountEquity;
                        pendingContribution = 0.0;
                        portfolioValueBefore = targetCapital;
                    } else {
                        pendingUsed = pendingContribution;
                        targetCapital = portfolioValueBefore + pendingContribution;
                        pendingContribution = 0.0;
                    }
                    double allocation = targetCapital / selectedPrices.size();
                    Map<String, Double> newHoldings = new LinkedHashMap<>();
                    double deployed = 0.0;
                    for (Map.Entry<String, Double> entry : selectedPrices.entrySet()) {
                        double qty = floorToLot(allocation / entry.getValue());
                        newHoldings.put(entry.getKey(), qty);
                        deployed += qty * entry.getValue();
                    }
                    holdings = newHoldings;
                    deployedCapital = deployed;
                    // Keep the undeployed whole-lot remainder as cash for next period.
                    cash = targetCapital - deployed;
                    portfolioValueAfter = deployed;
                }
            } else {
                holdings = new LinkedHashMap<>();
                deployedCapital = 0.0;
                portfolioValueAfter = 0.0;
            }

            for (String symbol : exited) {
                entryPrices.remove(symbol);
            }

            // --- 5b. Emit trades on a cash ledger: close positions before opening new. ---
            double runningCash = cameFromCash ? targetCapital : openingCash + pendingUsed;
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
        }

        // --- Final mark-to-market status row: value current holdings at the latest close. ---
        if (!holdings.isEmpty() && dateCount > 0) {
            int lastIdx = dateCount - 1;
            LocalDate markDate = dates.get(lastIdx);
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
            double markPeriodReturnPct = deployedCapital == 0.0
                    ? 0.0
                    : ((currentValue / deployedCapital) - 1.0) * 100.0;
            double markEquity = accountEquity + (currentValue - deployedCapital);
            double markCumulativeReturnPct =
                    (cumulativeGrowth * (1.0 + markPeriodReturnPct / 100.0) - 1.0) * 100.0;
            holdingsRows.add(new HoldingsRow(markDate, markDate, records.size(), holdings.size(),
                    markStr.toString(), round2(currentValue), round2(cash), round2(currentValue + cash),
                    round2(markEquity), round2(markPeriodReturnPct), round2(markCumulativeReturnPct)));
        }

        List<LookbackRow> lookbackRows = buildLookbackRows(bars, lookbackDays, minHistory, topN);
        return new BacktestResult(records, equityRows, performanceRows, tradebookRows, lookbackRows,
                holdingsRows, initialCapital);
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
