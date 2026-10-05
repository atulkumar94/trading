package com.rotation.engine;

import com.rotation.config.RotationConfig;
import com.rotation.model.BacktestResult;
import com.rotation.model.DailyBars;
import com.rotation.model.DailyCandle;
import com.rotation.model.SymbolDailyCandles;
import com.rotation.model.TradebookRow;
import com.rotation.model.YearEndEquity;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RotationEngineTest {

    private static final int LOOKBACK = 10;

    @Test
    void usesPreStartHistoryForLookbackAndTradesFromStartDate() throws IOException {
        // Weekday sessions from 2023-11-01 through 2024-03-29; trading starts mid-month
        // well after the lookback window is warm, so the first entry lands on the start date.
        DailyBars bars = bars(LocalDate.of(2023, 11, 1), LocalDate.of(2024, 3, 29));
        LocalDate startDate = LocalDate.of(2024, 1, 17);

        BacktestResult result = new RotationEngine(config("start.date=2024-01-17\n")).run(bars);

        assertFalse(result.rebalances().isEmpty());
        assertEquals(startDate, result.rebalances().get(0).date);
        assertEquals("AAA", result.rebalances().get(0).entered);
        TradebookRow firstTrade = result.tradebookRows().get(0);
        assertEquals("ENTRY", firstTrade.action);
        assertEquals(startDate, firstTrade.tradeDate);
        assertTrue(firstTrade.tradeDate.isAfter(firstTrade.signalDate));
        for (TradebookRow row : result.tradebookRows()) {
            assertFalse(row.tradeDate.isBefore(startDate), "trade before start date: " + row.tradeDate);
        }
        // Subsequent rebalances follow every 20 trading sessions (monthly).
        assertEquals(sessionAfter(bars, startDate, 20), result.rebalances().get(1).date);
        // Pre-start years are warm-up only and do not appear in the yearly marks.
        for (YearEndEquity mark : result.yearEndMarks()) {
            assertTrue(mark.year >= 2024, "warm-up year reported: " + mark.year);
        }
    }

    @Test
    void waitsForLookbackWhenStartDateIsAtHistoryStart() throws IOException {
        DailyBars bars = bars(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 3, 29));

        BacktestResult result = new RotationEngine(config("start.date=2024-01-01\n")).run(bars);

        // No pre-start history: the first rebalance executes on the session right after
        // the lookback window completes, not at the next calendar boundary.
        LocalDate firstRebalance = result.rebalances().get(0).date;
        assertEquals(bars.dates().get(LOOKBACK), firstRebalance);
        TradebookRow firstEntry = result.tradebookRows().get(0);
        assertEquals("ENTRY", firstEntry.action);
        assertEquals(firstRebalance, firstEntry.tradeDate);
        assertTrue(firstEntry.tradeDate.isAfter(firstEntry.signalDate));
    }

    @Test
    void rebalancesEveryFixedNumberOfSessionsPerMode() throws IOException {
        DailyBars bars = bars(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 6, 28));
        List<LocalDate> dates = bars.dates();

        assertSessionGap(bars, dates, "weekly", 5);
        assertSessionGap(bars, dates, "monthly_twice", 10);
        assertSessionGap(bars, dates, "monthly", 20);
    }

    private static void assertSessionGap(DailyBars bars, List<LocalDate> dates, String mode, int gap)
            throws IOException {
        BacktestResult result = new RotationEngine(config("rebalance.mode=" + mode + "\n")).run(bars);

        assertEquals(dates.get(LOOKBACK), result.rebalances().get(0).date, mode);
        assertTrue(result.rebalances().size() > 2, mode);
        for (int i = 1; i < result.rebalances().size(); i++) {
            int prev = dates.indexOf(result.rebalances().get(i - 1).date);
            int curr = dates.indexOf(result.rebalances().get(i).date);
            assertEquals(gap, curr - prev, mode + " gap at rebalance " + (i + 1));
        }
    }

    private static LocalDate sessionAfter(DailyBars bars, LocalDate date, int sessions) {
        return bars.dates().get(bars.dates().indexOf(date) + sessions);
    }

    private static RotationConfig config(String extra) throws IOException {
        Path dir = Files.createTempDirectory("rotation-engine-test");
        Path file = dir.resolve("rotation.properties");
        Files.writeString(file, "lookback.days=" + LOOKBACK + "\n"
                + "top.n=1\n"
                + "min.history.days=1\n"
                + "rebalance.mode=monthly\n"
                + "capital.per.stock=10000\n"
                + "allocation.mode=compound\n"
                + "verbose=false\n"
                + extra);
        return RotationConfig.load(file, dir);
    }

    /** AAA rises steadily, BBB falls steadily, so AAA always ranks first. */
    private static DailyBars bars(LocalDate from, LocalDate to) {
        List<DailyCandle> up = new ArrayList<>();
        List<DailyCandle> down = new ArrayList<>();
        int i = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            double a = 100.0 + i;
            double b = 200.0 - i;
            up.add(new DailyCandle(d, a, a, a, a, 1000));
            down.add(new DailyCandle(d, b, b, b, b, 1000));
            i++;
        }
        return DailyBars.build(List.of(new SymbolDailyCandles("AAA", up),
                new SymbolDailyCandles("BBB", down)), false);
    }
}
