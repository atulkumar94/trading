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
}
