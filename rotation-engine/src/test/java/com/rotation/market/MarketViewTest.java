package com.rotation.market;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.rotation.model.DailyBars;
import com.rotation.model.DailyCandle;
import com.rotation.model.SymbolDailyCandles;

class MarketViewTest {

    @Test
    void viewReadsTodayAndPastButRejectsFutureOffsets() {
        DailyBars bars = bars();
        MarketView view = new MarketData(bars).asOf(4);

        assertEquals(bars.dates().get(4), view.asOfDate());
        assertEquals(14.0, view.close("AAA", 0));
        assertEquals(12.0, view.close("AAA", 2));
        assertEquals(bars.dates().get(2), view.date(2));
        IllegalArgumentException closeError = assertThrows(
            IllegalArgumentException.class, () -> view.close("AAA", -1));
        IllegalArgumentException highError = assertThrows(
            IllegalArgumentException.class, () -> view.high("AAA", -1));
        assertTrue(closeError.getMessage().contains("future sessions"));
        assertTrue(highError.getMessage().contains("future sessions"));
    }

    private static DailyBars bars() {
        List<DailyCandle> candles = new ArrayList<>();
        LocalDate date = LocalDate.of(2024, 1, 2);
        for (int i = 0; i < 6; i++) {
            double close = 10.0 + i;
            candles.add(new DailyCandle(date.plusDays(i), close, close + 1.0,
                    close - 1.0, close, 1.0));
        }
        return DailyBars.build(List.of(new SymbolDailyCandles("AAA", candles)), false);
    }
}