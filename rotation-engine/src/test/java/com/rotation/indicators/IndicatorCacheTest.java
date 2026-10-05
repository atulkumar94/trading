package com.rotation.indicators;

import com.rotation.market.MarketData;
import com.rotation.market.MarketView;
import com.rotation.model.DailyBars;
import com.rotation.model.DailyCandle;
import com.rotation.model.SymbolDailyCandles;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndicatorCacheTest {

    private static final double EPS = 1e-9;

    @Test
    void cachesPointInTimeIndicatorsBySymbolPeriodAndSession() {
        MarketView market = new MarketData(bars()).asOf(5);
        IndicatorCache cache = new IndicatorCache();

        assertEquals(14.0, cache.sma(market, "AAA", 3), EPS);
        assertEquals(2.0, cache.atr(market, "AAA", 2), EPS);
        assertEquals(100.0, cache.adx(market, "AAA", 2), EPS);
        assertEquals(15.0 / 13.0 - 1.0, cache.returns(market, "AAA", 2), EPS);
        assertEquals(12.0, cache.rollingLow(market, "AAA", 3), EPS);
        long calculations = cache.calculationCount();

        assertEquals(14.0, cache.sma(market, "AAA", 3), EPS);
        assertEquals(2.0, cache.atr(market, "AAA", 2), EPS);
        assertEquals(100.0, cache.adx(market, "AAA", 2), EPS);
        assertEquals(calculations, cache.calculationCount());
    }

    @Test
    void indicatorValuesAreAsOfAndInsufficientHistoryIsMissing() {
        MarketData data = new MarketData(bars());
        MarketView earlier = data.asOf(4);
        MarketView later = data.asOf(5);

        assertEquals(13.0, earlier.sma("AAA", 3), EPS);
        assertEquals(14.0, later.sma("AAA", 3), EPS);
        assertTrue(Double.isNaN(data.asOf(1).atr("AAA", 2)));
        assertTrue(Double.isNaN(data.asOf(2).adx("AAA", 2)));
    }

    @Test
    void wilderIndicatorsWarmUpFromSymbolFirstValidBar() {
        List<DailyCandle> lateCandles = new ArrayList<>();
        LocalDate date = LocalDate.of(2024, 1, 4);
        for (int i = 0; i < 4; i++) {
            double close = 20.0 + i;
            lateCandles.add(new DailyCandle(date.plusDays(i), close, close + 1.0,
                    close - 1.0, close, 1.0));
        }
        List<DailyCandle> earlyCandles = List.of(
                new DailyCandle(date.minusDays(2), 10.0, 11.0, 9.0, 10.0, 1.0),
                new DailyCandle(date.minusDays(1), 11.0, 12.0, 10.0, 11.0, 1.0));
        DailyBars aligned = DailyBars.build(List.of(
                new SymbolDailyCandles("EARLY", earlyCandles),
                new SymbolDailyCandles("LATE", lateCandles)), false);
        MarketView latest = new MarketData(aligned).asOf(aligned.dateCount() - 1);

        assertEquals(2.0, latest.atr("LATE", 2), EPS);
        assertEquals(100.0, latest.adx("LATE", 2), EPS);
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