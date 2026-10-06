package com.rotation.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DailyBarsTest {

    @Test
    void filtersDataFromRequestedStartDate() {
        List<DailyCandle> candlesA = List.of(
                new DailyCandle(LocalDate.of(2022, 12, 30), 100.0, 110.0, 95.0, 105.0, 1000),
                new DailyCandle(LocalDate.of(2023, 01, 02), 105.0, 115.0, 100.0, 112.0, 1200),
                new DailyCandle(LocalDate.of(2023, 01, 03), 112.0, 120.0, 108.0, 118.0, 1300)
        );
        List<DailyCandle> candlesB = List.of(
                new DailyCandle(LocalDate.of(2022, 12, 30), 50.0, 55.0, 48.0, 52.0, 800),
                new DailyCandle(LocalDate.of(2023, 01, 02), 52.0, 58.0, 50.0, 55.0, 900),
                new DailyCandle(LocalDate.of(2023, 01, 03), 55.0, 60.0, 53.0, 59.0, 950)
        );

        DailyBars bars = DailyBars.build(List.of(
                new SymbolDailyCandles("AAA", candlesA),
                new SymbolDailyCandles("BBB", candlesB)
        ), false);

        DailyBars sliced = bars.filterFrom(LocalDate.of(2023, 1, 2));

        assertEquals(List.of(LocalDate.of(2023, 1, 2), LocalDate.of(2023, 1, 3)), sliced.dates());
        assertEquals(2, sliced.dateCount());
        assertEquals(112.0, sliced.closeAt(0, 0), 1e-9);
        assertEquals(55.0, sliced.closeAt(0, 1), 1e-9);
    }

    private static List<DailyCandle> closes(LocalDate start, double... closes) {
        List<DailyCandle> candles = new java.util.ArrayList<>();
        for (int i = 0; i < closes.length; i++) {
            double c = closes[i];
            candles.add(Double.isNaN(c) ? null
                    : new DailyCandle(start.plusDays(i), c, c, c, c, 100));
        }
        candles.removeIf(java.util.Objects::isNull);
        return candles;
    }

    @Test
    void dmaIsNanUntilWindowFullThenAveragesAdjustedCloses() {
        DailyBars bars = DailyBars.build(List.of(new SymbolDailyCandles("AAA",
                closes(LocalDate.of(2024, 1, 1), 1, 2, 3, 4, 5, 6))), false, List.of(3, 5));

        assertEquals(List.of(3, 5), bars.dmaPeriods());
        assertTrue(Double.isNaN(bars.dmaAt(3, 1, 0)));
        assertEquals(2.0, bars.dmaAt(3, 2, 0), 1e-9);
        assertEquals(5.0, bars.dmaAt(3, 5, 0), 1e-9);
        assertTrue(Double.isNaN(bars.dmaAt(5, 3, 0)));
        assertEquals(3.0, bars.dmaAt(5, 4, 0), 1e-9);
        assertEquals(4.0, bars.dmaAt(5, 5, 0), 1e-9);
    }

    @Test
    void dmaWindowRestartsAfterMissingSession() {
        LocalDate d = LocalDate.of(2024, 1, 1);
        // AAA is absent on day 2; BBB fixes the calendar.
        DailyBars bars = DailyBars.build(List.of(
                new SymbolDailyCandles("AAA", List.of(
                        new DailyCandle(d, 10, 10, 10, 10, 1),
                        new DailyCandle(d.plusDays(1), 20, 20, 20, 20, 1),
                        new DailyCandle(d.plusDays(3), 40, 40, 40, 40, 1),
                        new DailyCandle(d.plusDays(4), 50, 50, 50, 50, 1))),
                new SymbolDailyCandles("BBB", closes(d, 1, 1, 1, 1, 1))), false, List.of(2));

        assertEquals(15.0, bars.dmaAt(2, 1, 0), 1e-9);
        assertTrue(Double.isNaN(bars.dmaAt(2, 2, 0)));
        assertTrue(Double.isNaN(bars.dmaAt(2, 3, 0)));
        assertEquals(45.0, bars.dmaAt(2, 4, 0), 1e-9);
    }

    @Test
    void filterFromKeepsWarmupDmaAndRestrictToRecomputes() {
        DailyBars bars = DailyBars.build(List.of(
                new SymbolDailyCandles("AAA", closes(LocalDate.of(2024, 1, 1), 1, 2, 3, 4)),
                new SymbolDailyCandles("BBB", closes(LocalDate.of(2024, 1, 1), 10, 20, 30, 40))),
                false, List.of(3));

        DailyBars sliced = bars.filterFrom(LocalDate.of(2024, 1, 3));
        assertEquals(2.0, sliced.dmaAt(3, 0, 0), 1e-9);
        assertEquals(30.0, sliced.dmaAt(3, 1, 1), 1e-9);

        DailyBars restricted = bars.restrictTo(List.of("BBB"));
        assertEquals(List.of(3), restricted.dmaPeriods());
        assertEquals(30.0, restricted.dmaAt(3, 3, 0), 1e-9);

        DailyBars truncated = bars.filterTo(LocalDate.of(2024, 1, 3));
        assertEquals(2.0, truncated.dmaAt(3, 2, 0), 1e-9);
    }

    @Test
    void defaultsToStandardPeriodsAndRejectsUnconfiguredOrInvalidPeriods() {
        List<SymbolDailyCandles> series = List.of(new SymbolDailyCandles("AAA",
                closes(LocalDate.of(2024, 1, 1), 1, 2, 3)));
        DailyBars bars = DailyBars.build(series, false);

        assertEquals(List.of(10, 20, 50, 100, 200), bars.dmaPeriods());
        assertThrows(IllegalArgumentException.class,
                () -> DailyBars.build(series, false, List.of(3)).dmaAt(7, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> DailyBars.build(series, false, List.of(0)));
        assertThrows(IllegalArgumentException.class, () -> DailyBars.build(series, false, List.of(5, 5)));
        assertTrue(DailyBars.build(series, false, List.of()).dmaPeriods().isEmpty());
    }

        @Test
        void endDateFilterPreservesBarMetadata() {
                LocalDate firstDate = LocalDate.of(2024, 1, 2);
                DailyBars bars = DailyBars.build(List.of(new SymbolDailyCandles("AAA", List.of(
                                new DailyCandle(firstDate, 50.0, 55.0, 45.0, 52.5,
                                                1234.0, 105.0, 0.5, true),
                                new DailyCandle(firstDate.plusDays(1), 52.5, 56.0, 50.0, 55.0,
                                                1400.0, 110.0, 0.5, false)))), false);

                DailyBars filtered = bars.filterTo(firstDate);

                assertEquals(1234.0, filtered.volumeAt(0, 0));
                assertEquals(105.0, filtered.rawCloseAt(0, 0));
                assertEquals(0.5, filtered.adjustmentFactorAt(0, 0));
                assertTrue(filtered.validBarAt(0, 0));
        }
}
