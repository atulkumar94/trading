package com.rotation.execution;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.rotation.market.MarketView;
import com.rotation.model.DailyBars;
import com.rotation.model.EntryDetail;
import com.rotation.model.ExitDetail;
import com.rotation.model.TradebookRow;
import com.rotation.portfolio.Fill;
import com.rotation.portfolio.Ledger;
import com.rotation.portfolio.Portfolio;
import com.rotation.strategy.Candidate;
import com.rotation.strategy.ExitPolicy;
import com.rotation.strategy.OrderIntent;
import com.rotation.strategy.PortfolioView;
import com.rotation.strategy.Strategy;

/** Executes protective exits using close signals and next-session open prices. */
public final class BacktestExecution implements ExecutionModel {

    private final ExitPolicy exitPolicy;

    public BacktestExecution(ExitPolicy exitPolicy) {
        this.exitPolicy = exitPolicy;
    }

    /** Ask the strategy for close-time intents and queue them for the next open. */
    public List<OrderIntent> collectCloseIntents(Strategy strategy, MarketView market,
                                                  Portfolio portfolio) {
        return List.copyOf(strategy.onClose(market, new PortfolioView(portfolio)));
    }

    @Override
    public ExecutionResult executeAtOpen(LocalDate session, PendingRebalance pending,
                                         Portfolio portfolio, DailyBars market) {
        List<EntryDetail> entries = new ArrayList<>();
        pending.exited.forEach(symbol -> portfolio.entryPrices().remove(symbol));
        executeAtOpen(session, pending.signalDate, pending.rebalanceNumber, pending.executionIdx,
            market, pending.entered, pending.exited,
                pending.held, pending.priorHoldings, pending.targetHoldings,
                portfolio.entryPrices(), pending.exits, pending.ranked, pending.rankBySymbol,
                pending.topNSelection, pending.targetCapital, pending.cashBeforeRebalance,
                pending.pendingContributionUsed, pending.cameFromCash, pending.topN, pending.exitN,
                entries, portfolio.ledger().executionTradebookRows(), portfolio.ledger());
        portfolio.setRebalanceNumber(pending.rebalanceNumber);
        return new ExecutionResult(entries);
    }

    @Override
    public StopExecutionResult checkProtectiveStops(LocalDate session, Portfolio portfolio, DailyBars market) {
        int sessionIdx = market.indexOfDate(session);
        if (sessionIdx < 0) {
            throw new IllegalArgumentException("Stop-check session is not present in market data: " + session);
        }
        StopResult result = checkProtectiveStops(market, sessionIdx, sessionIdx, market.dates(),
                portfolio.quantities(), portfolio.stopBases(), portfolio.peakCloses(),
                portfolio.entryPrices(), portfolio.rebalanceNumber(), portfolio.cash(),
                portfolio.ledger().executionTradebookRows(), portfolio.ledger());
        portfolio.setCash(result.cash);
        return new StopExecutionResult(result.cash, result.realizedPnl, result.basisRemoved);
    }

    /** Replay stops on an isolated portfolio through the close before a target mark. */
    public StopExecutionResult replayProtectiveStops(int fromIdx, int toIdxInclusive,
                                                     Portfolio portfolio, DailyBars market) {
        StopResult result = checkProtectiveStops(market, fromIdx, toIdxInclusive, market.dates(),
                portfolio.quantities(), portfolio.stopBases(), portfolio.peakCloses(),
                portfolio.entryPrices(), portfolio.rebalanceNumber(), portfolio.cash(),
                portfolio.ledger().executionTradebookRows(), portfolio.ledger());
        portfolio.setCash(result.cash);
        return new StopExecutionResult(result.cash, result.realizedPnl, result.basisRemoved);
    }

    /**
     * Apply a selected book at its execution open. Sells, held-name reweights,
     * then buys are recorded in the legacy report order and in ledger order.
     */
    public void executeAtOpen(LocalDate executionDate, LocalDate signalDate, int rebalanceNumber,
                              int executionIdx, DailyBars bars, List<String> entered,
                              List<String> exited, List<String> held,
                              Map<String, Double> priorHoldings, Map<String, Double> newHoldings,
                              Map<String, Double> entryPrices, List<ExitDetail> exitDetails,
                              List<Candidate> ranked, Map<String, Integer> rankBySymbol,
                              Set<String> topNSelection, double targetCapital,
                              double cashBeforeRebalance, double pendingUsed, boolean cameFromCash,
                              int topN, int exitN, List<EntryDetail> entryDetails,
                              List<TradebookRow> tradebookRows, Ledger ledger) {
        double runningCash = cameFromCash ? targetCapital : cashBeforeRebalance + pendingUsed;
        for (ExitDetail detail : exitDetails) {
            double before = runningCash;
            runningCash += detail.exitValue;
            tradebookRows.add(new TradebookRow(executionDate, "EXIT", detail.symbol,
                    detail.quantity, detail.exitPrice, detail.exitValue, detail.entryPrice,
                    detail.exitPrice, detail.realizedPnl, round2(before), round2(runningCash),
                    signalDate, rebalanceNumber));
            ledger.recordFill(new Fill(executionDate, signalDate, rebalanceNumber, Fill.EXIT,
                    detail.symbol, detail.quantity, detail.exitPrice,
                    exitReason(rankBySymbol.get(detail.symbol), ranked.size(), exitN)));
        }

        double heldNetValue = 0.0;
        for (String symbol : held) {
            double price = openAt(bars, executionIdx, symbol);
            if (Double.isNaN(price)) {
                continue;
            }
            Double oldQty = priorHoldings.get(symbol);
            Double newQty = newHoldings.get(symbol);
            heldNetValue += (newQty != null ? newQty : 0.0) * price;
            heldNetValue -= (oldQty != null ? oldQty : 0.0) * price;
            double delta = (newQty != null ? newQty : 0.0) - (oldQty != null ? oldQty : 0.0);
            if (delta != 0.0) {
                Integer rank = rankBySymbol.get(symbol);
                ledger.recordFill(new Fill(executionDate, signalDate, rebalanceNumber,
                        delta > 0 ? Fill.ADD : Fill.TRIM, symbol, Math.abs(delta), price,
                        "Re-sized to target allocation (rank " + rank + " of " + ranked.size()
                                + (topNSelection.contains(symbol) ? ", in top.n selection)"
                                        : ", retained in exit buffer)")));
            }
        }
        runningCash -= heldNetValue;

        for (String symbol : entered) {
            Double newQty = newHoldings.get(symbol);
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
            Integer rank = rankBySymbol.get(symbol);
            ledger.recordFill(new Fill(executionDate, signalDate, rebalanceNumber, Fill.ENTRY,
                    symbol, newQty, price,
                    "Entered: rank " + rank + " of " + ranked.size() + " (top.n=" + topN
                            + (rank > topN ? ", higher-ranked names skipped by sector cap)" : ")")));
            entryPrices.put(symbol, price);
        }
    }

    /** Record a position written off because its execution-session open is absent. */
    public void recordDrop(LocalDate executionDate, LocalDate signalDate, int rebalanceNumber,
                           String symbol, double quantity, Ledger ledger) {
        ledger.recordFill(new Fill(executionDate, signalDate, rebalanceNumber, Fill.DROP,
                symbol, quantity, 0.0, "No open price on execution day; position written off at zero"));
    }

    /** Value currently held shares at the open and write off names without an open. */
    public double valueAndDropAtOpen(LocalDate executionDate, LocalDate signalDate,
                                     int rebalanceNumber, int executionIdx, DailyBars bars,
                                     Portfolio portfolio, Ledger ledger) {
        Map<String, Double> pricedHoldings = new LinkedHashMap<>();
        double value = 0.0;
        for (Map.Entry<String, Double> holding : new ArrayList<>(portfolio.quantities().entrySet())) {
            double price = openPrice(bars, executionIdx, holding.getKey());
            if (Double.isNaN(price)) {
                recordDrop(executionDate, signalDate, rebalanceNumber,
                        holding.getKey(), holding.getValue(), ledger);
                continue;
            }
            pricedHoldings.put(holding.getKey(), holding.getValue());
            value += holding.getValue() * price;
        }
        portfolio.replaceQuantities(pricedHoldings);
        return value;
    }

    /** Resolve executable prices once, retaining the selected symbol order. */
    public Map<String, Double> selectedOpenPrices(List<String> selected, int executionIdx,
                                                   DailyBars bars) {
        Map<String, Double> prices = new LinkedHashMap<>();
        for (String symbol : selected) {
            double price = openPrice(bars, executionIdx, symbol);
            if (!Double.isNaN(price)) {
                prices.put(symbol, price);
            }
        }
        return prices;
    }

    /** Size selected positions and update the portfolio's invested and cash balances. */
    public double allocateAtOpen(Map<String, Double> selectedPrices, double allocation,
                                 double targetCapital, Portfolio portfolio) {
        if (selectedPrices.isEmpty()) {
            portfolio.clearPositions();
            portfolio.stopBases().clear();
            portfolio.peakCloses().clear();
            portfolio.setDeployedCapital(0.0);
            portfolio.setCash(0.0);
            return 0.0;
        }

        Map<String, Double> quantities = new LinkedHashMap<>();
        double deployed = 0.0;
        for (Map.Entry<String, Double> selected : selectedPrices.entrySet()) {
            double quantity = wholeShares(allocation, selected.getValue());
            quantities.put(selected.getKey(), quantity);
            deployed += quantity * selected.getValue();
        }
        portfolio.replaceQuantities(quantities);
        portfolio.stopBases().clear();
        portfolio.peakCloses().clear();
        selectedPrices.forEach((symbol, price) -> {
            portfolio.stopBases().put(symbol, price);
            portfolio.peakCloses().put(symbol, price);
        });
        portfolio.setDeployedCapital(deployed);
        portfolio.setCash(targetCapital - deployed);
        return deployed;
    }

    /** Build legacy exit details using the execution-session open and prior book. */
    public List<ExitDetail> exitDetailsAtOpen(int executionIdx, DailyBars bars, List<String> exited,
                                               Map<String, Double> priorHoldings,
                                               Portfolio portfolio) {
        List<ExitDetail> details = new ArrayList<>();
        for (String symbol : exited) {
            double exitPrice = openPrice(bars, executionIdx, symbol);
            Double quantity = priorHoldings.get(symbol);
            if (Double.isNaN(exitPrice) || quantity == null || quantity.isNaN()) {
                continue;
            }
            Double entryPrice = portfolio.entryPrices().get(symbol);
            double exitValue = quantity * exitPrice;
            double realizedPnl = entryPrice != null ? (exitPrice - entryPrice) * quantity : 0.0;
            details.add(new ExitDetail(symbol, entryPrice, exitPrice, quantity, exitValue, realizedPnl));
        }
        return details;
    }

    public double openPrice(DailyBars bars, int dateIdx, String symbol) {
        return openAt(bars, dateIdx, symbol);
    }

    /** Round a tradable quantity down to the nearest whole share. */
    public double wholeShares(double budget, double openPrice) {
        return Math.floor(budget / openPrice);
    }

    /**
     * Scan a holding interval for close-triggered stops. Each valid trigger is
     * filled at the next open, updates cash and basis, and is recorded in order.
     */
    public StopResult checkProtectiveStops(DailyBars bars, int fromIdx, int toIdxInclusive,
                                           List<LocalDate> dates, Map<String, Double> holdings,
                                           Map<String, Double> stopBasis, Map<String, Double> peakPrices,
                                           Map<String, Double> entryPrices, int owningRebalanceNumber,
                                           double startingCash, List<TradebookRow> tradebookRows,
                                           Ledger ledger) {
        StopResult result = new StopResult();
        result.cash = startingCash;
        if (!exitPolicy.active() || holdings.isEmpty()) {
            return result;
        }
        int lastIdx = dates.size() - 1;
        for (int day = fromIdx; day <= toIdxInclusive; day++) {
            if (holdings.isEmpty()) {
                break;
            }
            int fillIdx = day + 1;
            boolean canFill = fillIdx <= lastIdx;
            for (String symbol : new ArrayList<>(holdings.keySet())) {
                int col = bars.indexOfSymbol(symbol);
                if (col < 0) {
                    continue;
                }
                double basis = stopBasis.getOrDefault(symbol, entryPrices.getOrDefault(symbol, Double.NaN));
                if (Double.isNaN(basis)) {
                    continue;
                }
                double peak = peakPrices.getOrDefault(symbol, basis);
                double close = bars.closeAt(day, col);
                ExitPolicy.Trigger trigger = exitPolicy.evaluate(basis, peak, close);
                double fillOpen = canFill ? bars.openAt(fillIdx, col) : Double.NaN;
                if (trigger == null || !canFill || Double.isNaN(fillOpen)) {
                    if (!Double.isNaN(close) && close > peak) {
                        peakPrices.put(symbol, close);
                    }
                    continue;
                }
                double quantity = holdings.get(symbol);
                double proceeds = quantity * fillOpen;
                double before = result.cash;
                result.cash += proceeds;
                result.basisRemoved += quantity * basis;
                result.realizedPnl += proceeds - quantity * basis;
                Double entry = entryPrices.get(symbol);
                double reportedPnl = entry != null ? (fillOpen - entry) * quantity : 0.0;
                tradebookRows.add(new TradebookRow(dates.get(fillIdx), "STOP", symbol, quantity, fillOpen,
                        round2(proceeds), entry, fillOpen, round2(reportedPnl), round2(before),
                        round2(result.cash), dates.get(day), owningRebalanceNumber));
                ledger.recordFill(new Fill(dates.get(fillIdx), dates.get(day), owningRebalanceNumber,
                        Fill.STOP, symbol, quantity, fillOpen,
                        String.format(Locale.US, "%s: close %.2f <= stop %.2f on %s; filled next open",
                                trigger.rule, close, trigger.stopLevel, dates.get(day))));
                holdings.remove(symbol);
                stopBasis.remove(symbol);
                peakPrices.remove(symbol);
                entryPrices.remove(symbol);
            }
        }
        return result;
    }

    /** Apply close-triggered stops directly to live or copied portfolio state. */
    public StopResult checkProtectiveStops(DailyBars bars, int fromIdx, int toIdxInclusive,
                                           Portfolio portfolio, int owningRebalanceNumber,
                                           List<TradebookRow> tradebookRows, Ledger ledger) {
        StopResult result = checkProtectiveStops(bars, fromIdx, toIdxInclusive, bars.dates(),
                portfolio.quantities(), portfolio.stopBases(), portfolio.peakCloses(),
                portfolio.entryPrices(), owningRebalanceNumber, portfolio.cash(), tradebookRows, ledger);
        portfolio.setCash(result.cash);
        return result;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double openAt(DailyBars bars, int dateIdx, String symbol) {
        int idx = bars.indexOfSymbol(symbol);
        return idx < 0 ? Double.NaN : bars.openAt(dateIdx, idx);
    }

    private static String exitReason(Integer rank, int rankedCount, int exitN) {
        if (rank == null) {
            return "Ineligible at signal (missing close or insufficient history)";
        }
        if (rank > exitN) {
            return "Rank " + rank + " of " + rankedCount + ", below exit rank " + exitN;
        }
        return "Rank " + rank + " of " + rankedCount + ", within exit rank " + exitN
                + " but not reselected (sector cap or book capacity)";
    }

    /** Accounting effects of stop fills applied during one scan. */
    public static final class StopResult {
        public double cash;
        public double realizedPnl;
        public double basisRemoved;
    }
}