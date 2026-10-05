package com.rotation.execution;

import com.rotation.model.DailyBars;
import com.rotation.model.DailyCandle;
import com.rotation.model.SymbolDailyCandles;
import com.rotation.model.TradebookRow;
import com.rotation.portfolio.Fill;
import com.rotation.portfolio.Ledger;
import com.rotation.portfolio.Portfolio;
import com.rotation.strategy.Candidate;
import com.rotation.strategy.StopLossExitPolicy;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BacktestExecutionTest {

    @Test
    void gapThroughStopFillsAtNextOpenAndCreditsProceeds() {
        DailyBars bars = bars(100.0, 85.0, 60.0);
        Map<String, Double> holdings = new LinkedHashMap<>(Map.of("AAA", 10.0));
        Map<String, Double> stopBasis = new LinkedHashMap<>(Map.of("AAA", 100.0));
        Map<String, Double> peak = new LinkedHashMap<>(Map.of("AAA", 100.0));
        Map<String, Double> entry = new LinkedHashMap<>(Map.of("AAA", 100.0));
        List<TradebookRow> tradebook = new ArrayList<>();
        Ledger ledger = new Ledger();

        BacktestExecution.StopResult result = execution().checkProtectiveStops(
                bars, 1, 1, bars.dates(), holdings, stopBasis, peak, entry, 1,
                25.0, tradebook, ledger);

        assertEquals(60.0, tradebook.get(0).price);
        assertEquals(bars.dates().get(1), tradebook.get(0).signalDate);
        assertEquals(bars.dates().get(2), tradebook.get(0).tradeDate);
        assertEquals(625.0, result.cash);
        assertEquals(-400.0, result.realizedPnl);
        assertTrue(holdings.isEmpty());
        assertEquals(1, ledger.fills().size());
    }

    @Test
    void entryDayCloseCanTriggerButFillIsNeverSameSession() {
        DailyBars bars = bars(110.0, 80.0, 75.0);
        Map<String, Double> holdings = new LinkedHashMap<>(Map.of("AAA", 5.0));
        Map<String, Double> stopBasis = new LinkedHashMap<>(Map.of("AAA", 110.0));
        Map<String, Double> peak = new LinkedHashMap<>(Map.of("AAA", 110.0));
        Map<String, Double> entry = new LinkedHashMap<>(Map.of("AAA", 110.0));
        List<TradebookRow> tradebook = new ArrayList<>();

        execution().checkProtectiveStops(bars, 1, 1, bars.dates(), holdings,
                stopBasis, peak, entry, 1, 0.0, tradebook, new Ledger());

        assertEquals(1, tradebook.size());
        assertEquals(bars.dates().get(1), tradebook.get(0).signalDate);
        assertEquals(bars.dates().get(2), tradebook.get(0).tradeDate);
        assertTrue(tradebook.get(0).tradeDate.isAfter(tradebook.get(0).signalDate));
        assertFalse(holdings.containsKey("AAA"));
    }

    @Test
    void allocationRoundsDownAndKeepsCashWhenBudgetCannotBuyWholeShare() {
        Portfolio portfolio = new Portfolio(1000.0);

        double deployed = execution().allocateAtOpen(Map.of("AAA", 300.0), 1000.0, 1000.0, portfolio);

        assertEquals(900.0, deployed);
        assertEquals(3.0, portfolio.quantities().get("AAA"));
        assertEquals(100.0, portfolio.cash());

        deployed = execution().allocateAtOpen(Map.of("AAA", 300.0), 200.0, 200.0, portfolio);

        assertEquals(0.0, deployed);
        assertEquals(0.0, portfolio.quantities().get("AAA"));
        assertEquals(200.0, portfolio.cash());
    }

    @Test
    void missingExecutionOpenWritesOffPositionAsDrop() {
        LocalDate date = LocalDate.of(2024, 1, 2);
        DailyBars bars = DailyBars.build(List.of(new SymbolDailyCandles("AAA", List.of(
                new DailyCandle(date, 100.0, 100.0, 100.0, 100.0, 1.0),
                new DailyCandle(date.plusDays(1), Double.NaN, 100.0, 90.0, 90.0, 1.0)))), false);
        Portfolio portfolio = new Portfolio(500.0);
        portfolio.quantities().put("AAA", 5.0);
        Ledger ledger = new Ledger();

        double openValue = execution().valueAndDropAtOpen(date.plusDays(1), date, 1, 1,
                bars, portfolio, ledger);

        assertEquals(0.0, openValue);
        assertTrue(portfolio.quantities().isEmpty());
        assertEquals(Fill.DROP, ledger.fills().get(0).action);
        assertEquals(0.0, ledger.fills().get(0).price);
    }

    @Test
    void heldReweightsRecordAddAndTrimInTheExecutionLedger() {
        DailyBars bars = bars(100.0, 100.0, 100.0);
        Candidate candidate = new Candidate("AAA", 1.0, 90.0, 100.0, 10);
        List<TradebookRow> tradebook = new ArrayList<>();
        Ledger ledger = new Ledger();

        execution().executeAtOpen(bars.dates().get(1), bars.dates().get(0), 1, 1, bars,
                List.of(), List.of(), List.of("AAA"), Map.of("AAA", 10.0), Map.of("AAA", 15.0),
                new HashMap<>(), List.of(), List.of(candidate), Map.of("AAA", 1), Set.of("AAA"),
                1500.0, 1500.0, 0.0, false, 1, 1, new ArrayList<>(), tradebook, ledger);
        execution().executeAtOpen(bars.dates().get(2), bars.dates().get(1), 2, 2, bars,
                List.of(), List.of(), List.of("AAA"), Map.of("AAA", 15.0), Map.of("AAA", 8.0),
                new HashMap<>(), List.of(), List.of(candidate), Map.of("AAA", 1), Set.of("AAA"),
                800.0, 800.0, 0.0, false, 1, 1, new ArrayList<>(), tradebook, ledger);

        assertEquals(Fill.ADD, ledger.fills().get(0).action);
        assertEquals(5.0, ledger.fills().get(0).quantity);
        assertEquals(Fill.TRIM, ledger.fills().get(1).action);
        assertEquals(7.0, ledger.fills().get(1).quantity);
    }

    private static BacktestExecution execution() {
        return new BacktestExecution(new StopLossExitPolicy(10.0, 0.0));
    }

    private static DailyBars bars(double entryClose, double triggerClose, double nextOpen) {
        LocalDate date = LocalDate.of(2024, 1, 2);
        List<DailyCandle> candles = List.of(
                new DailyCandle(date, entryClose, entryClose, entryClose, entryClose, 1.0),
                new DailyCandle(date.plusDays(1), entryClose, entryClose, triggerClose, triggerClose, 1.0),
                new DailyCandle(date.plusDays(2), nextOpen, nextOpen, nextOpen, nextOpen, 1.0));
        return DailyBars.build(List.of(new SymbolDailyCandles("AAA", candles)), false);
    }
}