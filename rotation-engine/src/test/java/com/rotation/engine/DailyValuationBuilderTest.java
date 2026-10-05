package com.rotation.engine;

import com.rotation.model.BacktestResult;
import com.rotation.model.DailyBars;
import com.rotation.model.DailyCandle;
import com.rotation.model.DailyMark;
import com.rotation.model.DailyPortfolioRow;
import com.rotation.model.DailyPositionRow;
import com.rotation.model.DailyValuation;
import com.rotation.portfolio.Fill;
import com.rotation.model.SymbolDailyCandles;
import com.rotation.model.TradeLedgerRow;
import com.rotation.report.DailyValuationBuilder;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Average-cost accounting on a hand-built fill sequence with known answers. */
class DailyValuationBuilderTest {

    private static final LocalDate D1 = LocalDate.of(2024, 1, 2);
    private static final LocalDate D2 = LocalDate.of(2024, 1, 3);
    private static final LocalDate D3 = LocalDate.of(2024, 1, 4);
    private static final LocalDate D4 = LocalDate.of(2024, 1, 5);

    @Test
    void averageCostRealizedAndUnrealizedPnl() {
        // closes: 105, 112, 118, 90 (opens equal the fill prices below)
        DailyBars bars = bars(new double[] {100, 110, 120, 90}, new double[] {105, 112, 118, 90});
        List<Fill> fills = List.of(
            fill(D1, Fill.ENTRY, 10, 100),
            fill(D2, Fill.ADD, 5, 110),
            fill(D3, Fill.TRIM, 3, 120),
            fill(D4, Fill.EXIT, 12, 90));
        // cash: 10000 -> 9000 -> 8450 -> 8810 -> 9890
        List<DailyMark> marks = List.of(
                mark(D1, 9000 + 10 * 105, Map.of("AAA", 10.0)),
                mark(D2, 8450 + 15 * 112, Map.of("AAA", 15.0)),
                mark(D3, 8810 + 12 * 118, Map.of("AAA", 12.0)),
                mark(D4, 9890, Map.of()));

        DailyValuation v = new DailyValuationBuilder(Map.of("AAA", "Tech")).build(bars, result(fills, marks));

        double avg = (1000.0 + 550.0) / 15.0;
        List<TradeLedgerRow> ledger = v.ledgerRows();
        assertEquals(avg, ledger.get(1).averageCostAfter, 1e-9);
        assertEquals((120 - avg) * 3, ledger.get(2).realizedPnl, 1e-9);
        assertNull(ledger.get(2).pnlVsEntryPrice, "TRIM keeps the position open");
        assertEquals((90 - avg) * 12, ledger.get(3).realizedPnl, 1e-9);
        assertEquals((90 - 100.0) * 12, ledger.get(3).pnlVsEntryPrice, 1e-9, "tradebook convention");
        assertEquals(9890.0, ledger.get(3).cashAfter, 1e-9);

        DailyPositionRow d2 = v.positionRows().get(1);
        assertEquals(D2, d2.date);
        assertEquals(D1, d2.entryDate, "re-weights keep the original entry date");
        assertEquals(100.0, d2.entryPrice, 1e-9);
        assertEquals(15 * 112 - 1550.0, d2.unrealizedPnl, 1e-9);
        assertEquals("Tech", d2.sector);

        List<DailyPortfolioRow> days = v.portfolioRows();
        assertEquals((120 - avg) * 3, days.get(2).realizedPnl, 1e-9);
        assertEquals(12 * 118 - 12 * avg, days.get(2).unrealizedPnl, 1e-9);
        assertEquals(-110.0, days.get(3).realizedPnl, 1e-9, "50 - 160 over the round trip");
        assertEquals(-110.0, days.get(3).totalPnl, 1e-9);
        for (DailyPortfolioRow day : days) {
            assertEquals(0.0, day.reconciliationDiff, 1e-9);
        }
        assertEquals(0, v.positionRows().stream().filter(p -> p.date.equals(D4)).count());
    }

    @Test
    void failsLoudlyWhenReplayDivergesFromEngineBook() {
        DailyBars bars = bars(new double[] {100, 100, 100, 100}, new double[] {100, 100, 100, 100});
        List<Fill> fills = List.of(fill(D1, Fill.ENTRY, 10, 100));
        List<DailyMark> marks = List.of(mark(D1, 10000, Map.of("AAA", 11.0)));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new DailyValuationBuilder(Map.of()).build(bars, result(fills, marks)));
        assertTrue(e.getMessage().contains("AAA"), e.getMessage());
    }

    @Test
    void flagsMissingClosesAndValuesThemAtZero() {
        DailyBars bars = DailyBars.build(List.of(
                new SymbolDailyCandles("AAA", List.of(
                        new DailyCandle(D1, 100, 100, 100, 100, 0),
                        new DailyCandle(D3, 100, 100, 100, 100, 0))),
                new SymbolDailyCandles("BBB", List.of(
                        new DailyCandle(D1, 1, 1, 1, 1, 0),
                        new DailyCandle(D2, 1, 1, 1, 1, 0),
                        new DailyCandle(D3, 1, 1, 1, 1, 0)))), false);
        List<Fill> fills = List.of(fill(D1, Fill.ENTRY, 10, 100));
        List<DailyMark> marks = List.of(
                mark(D1, 10000, Map.of("AAA", 10.0)),
                mark(D2, 9000, Map.of("AAA", 10.0)),   // engine skips the missing close
                mark(D3, 10000, Map.of("AAA", 10.0)));

        DailyValuation v = new DailyValuationBuilder(Map.of()).build(bars, result(fills, marks));
        DailyPositionRow missing = v.positionRows().get(1);
        assertEquals(DailyPositionRow.PRICE_MISSING, missing.priceStatus);
        assertNull(missing.close);
        assertEquals(0.0, missing.marketValue, 1e-9);
        assertEquals(1, v.portfolioRows().get(1).missingPrices);
        assertEquals(0.0, v.portfolioRows().get(1).reconciliationDiff, 1e-9);
    }

    private static BacktestResult result(List<Fill> fills, List<DailyMark> marks) {
        return new BacktestResult(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                10000.0, fills, marks);
    }

    private static Fill fill(LocalDate date, String action, double qty, double price) {
        return new Fill(date, date.minusDays(1), 1, action, "AAA", qty, price, "test");
    }

    private static DailyMark mark(LocalDate date, double equity, Map<String, Double> holdings) {
        return new DailyMark(date, equity, 0.0, 1, holdings);
    }

    private static DailyBars bars(double[] opens, double[] closes) {
        LocalDate[] dates = {D1, D2, D3, D4};
        List<DailyCandle> candles = new ArrayList<>();
        for (int i = 0; i < dates.length; i++) {
            candles.add(new DailyCandle(dates[i], opens[i], Math.max(opens[i], closes[i]),
                    Math.min(opens[i], closes[i]), closes[i], 0));
        }
        return DailyBars.build(List.of(new SymbolDailyCandles("AAA", candles)), false);
    }
}
