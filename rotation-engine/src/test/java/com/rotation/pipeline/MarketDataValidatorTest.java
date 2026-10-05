package com.rotation.pipeline;

import com.rotation.model.DailyBars;
import com.rotation.model.DailyCandle;
import com.rotation.model.SymbolDailyCandles;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketDataValidatorTest {

    @Test
    void reportsGapsJumpsAndRepeatedValidRuns() {
        DailyBars bars = suspectBars();

        List<String> findings = MarketDataValidator.inspect(bars);

        assertEquals(3, findings.size());
        assertTrue(findings.get(0).contains("missing or invalid"));
        assertTrue(findings.get(1).contains("jumps exceed 50.0%"));
        assertTrue(findings.get(2).contains("identical valid OHLC bars"));
    }

    @Test
    void warnModeContinuesAndFailModeRejectsTheSameFindings() {
        DailyBars bars = suspectBars();

        MarketDataValidator.validate(bars, "warn");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> MarketDataValidator.validate(bars, "fail"));
        assertTrue(error.getMessage().contains("Market data validation failed"));
    }
    private static DailyBars suspectBars() {
        LocalDate date = LocalDate.of(2024, 1, 2);
        List<DailyCandle> aaa = List.of(
                candle(date, 100.0),
                candle(date.plusDays(1), 200.0),
                candle(date.plusDays(3), 200.0),
                candle(date.plusDays(4), 200.0),
                candle(date.plusDays(5), 200.0));
        List<DailyCandle> bbb = List.of(candle(date.plusDays(2), 50.0));
        return DailyBars.build(List.of(new SymbolDailyCandles("AAA", aaa),
                new SymbolDailyCandles("BBB", bbb)), true);
    }

    private static DailyCandle candle(LocalDate date, double close) {
        return new DailyCandle(date, close, close, close, close, 100.0);
    }
}
