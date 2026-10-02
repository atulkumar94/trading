package com.rotation.engine;

import com.rotation.model.DailyPortfolioRow;
import com.rotation.model.RangeMetrics;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Range metrics on a synthetic series where every session returns exactly +1%. */
class PerformanceCalculatorTest {

    private static final double CAPITAL = 1000.0;

    @Test
    void lastThirtySessionsCompoundsThirtyDailyReturns() {
        List<DailyPortfolioRow> rows = series(LocalDate.of(2024, 1, 1), 60, -1, 0.0);
        RangeMetrics m = preset(rows, rows.get(59).date, PerformanceCalculator.LAST_30_SESSIONS);

        assertEquals(30, m.sessions);
        assertEquals(rows.get(29).date, m.baseDate);
        assertEquals((Math.pow(1.01, 30) - 1.0) * 100.0, m.returnPct, 1e-9);
        assertEquals(rows.get(59).accountEquity - rows.get(29).accountEquity, m.pnl, 1e-9);
        assertEquals(0.0, m.maxDrawdownPct, 1e-12);
        assertFalse(m.truncated);
        assertNull(m.annualizedReturnPct, "under a year is never annualized");
    }

    @Test
    void lastThirtyCalendarDaysResolvesWeekendAsOfToFriday() {
        // Weekday sessions from Mon 2024-01-01; ask for Sunday 2024-03-10 -> Friday 2024-03-08.
        List<DailyPortfolioRow> rows = series(LocalDate.of(2024, 1, 1), 60, -1, 0.0);
        LocalDate sunday = LocalDate.of(2024, 3, 10);
        RangeMetrics m = preset(rows, sunday, PerformanceCalculator.LAST_30_DAYS);

        assertEquals(LocalDate.of(2024, 3, 8), m.asOf);
        // 30 calendar days ending Fri 03-08 start Thu 02-08: 22 weekday sessions,
        // measured from the close of Wed 02-07.
        assertEquals(LocalDate.of(2024, 2, 8), m.firstSession);
        assertEquals(LocalDate.of(2024, 2, 7), m.baseDate);
        assertEquals(22, m.sessions);
        assertEquals((Math.pow(1.01, 22) - 1.0) * 100.0, m.returnPct, 1e-9);
    }

    @Test
    void customRangeExcludesContributionsFromPnlAndReturn() {
        // A 500 contribution on session 10; returns stay +1% because the series is
        // built time-weighted: equity_t = (equity_{t-1} + contribution_t) * 1.01.
        List<DailyPortfolioRow> rows = series(LocalDate.of(2024, 1, 1), 20, 10, 500.0);
        PerformanceCalculator calc = new PerformanceCalculator(rows, dates(rows), CAPITAL);
        RangeMetrics m = calc.range("custom", rows.get(5).date, rows.get(15).date);

        assertEquals(11, m.sessions);
        assertEquals(500.0, m.contributions, 1e-9);
        assertEquals((Math.pow(1.01, 11) - 1.0) * 100.0, m.returnPct, 1e-9);
        assertEquals(rows.get(15).accountEquity - rows.get(4).accountEquity - 500.0, m.pnl, 1e-9);
    }

    @Test
    void flagsWindowsLongerThanTheBacktestHistory() {
        List<DailyPortfolioRow> rows = series(LocalDate.of(2024, 1, 1), 10, -1, 0.0);
        RangeMetrics sessions = preset(rows, rows.get(9).date, PerformanceCalculator.LAST_30_SESSIONS);
        assertTrue(sessions.truncated);
        assertEquals(10, sessions.sessions);
        assertNull(sessions.baseDate, "falls back to inception");
        assertEquals((Math.pow(1.01, 10) - 1.0) * 100.0, sessions.returnPct, 1e-9);

        RangeMetrics days = preset(rows, rows.get(9).date, PerformanceCalculator.LAST_30_DAYS);
        assertTrue(days.truncated);
        RangeMetrics all = preset(rows, rows.get(9).date, PerformanceCalculator.ALL);
        assertFalse(all.truncated);
        assertEquals(CAPITAL, all.startEquity, 1e-9);
    }

    @Test
    void yearToDateIsTruncatedOnlyWhenHistoryIsMissing() {
        // Backtest starts Tue 2024-01-02 after warm-up data ending Fri 2023-12-29:
        // no January session is missing, so YTD is complete and measured from inception.
        List<DailyPortfolioRow> rows = series(LocalDate.of(2024, 1, 2), 30, -1, 0.0);
        LocalDate asOf = rows.get(29).date;
        List<LocalDate> warmUp = new ArrayList<>(dates(rows));
        warmUp.add(0, LocalDate.of(2023, 12, 29));
        RangeMetrics complete = new PerformanceCalculator(rows, warmUp, CAPITAL)
                .range("YTD", LocalDate.of(2024, 1, 1), asOf);
        assertFalse(complete.truncated);
        assertNull(complete.baseDate);

        // A warm-up session on 2024-01-01 falls inside the window but before the backtest.
        List<LocalDate> missedSession = new ArrayList<>(warmUp);
        missedSession.add(1, LocalDate.of(2024, 1, 1));
        assertTrue(new PerformanceCalculator(rows, missedSession, CAPITAL)
                .range("YTD", LocalDate.of(2024, 1, 1), asOf).truncated);

        // No data at all before the window start: whether 2024-01-01 traded is unknown.
        assertTrue(new PerformanceCalculator(rows, dates(rows), CAPITAL)
                .range("YTD", LocalDate.of(2024, 1, 1), asOf).truncated);
    }

    @Test
    void maxDrawdownWithinRangeResetsPeakAtBase() {
        List<DailyPortfolioRow> rows = new ArrayList<>();
        double[] equity = {1000, 1100, 990, 1045, 1200};
        LocalDate d = LocalDate.of(2024, 1, 1);
        for (int i = 0; i < equity.length; i++) {
            rows.add(row(d.plusDays(i), equity[i], equity[i] / CAPITAL, 0.0));
        }
        PerformanceCalculator calc = new PerformanceCalculator(rows, dates(rows), CAPITAL);
        assertEquals(-10.0, calc.range("x", rows.get(0).date, rows.get(4).date).maxDrawdownPct, 1e-9);
        // Starting after the peak: the base (1100 close) is the first peak.
        assertEquals(-10.0, calc.range("x", rows.get(2).date, rows.get(3).date).maxDrawdownPct, 1e-9);
        assertNull(calc.range("x", rows.get(4).date.plusDays(1), rows.get(4).date), "empty range");
        assertNull(calc.range("x", rows.get(0).date, rows.get(0).date.minusDays(1)), "as-of before data");
    }

    private static RangeMetrics preset(List<DailyPortfolioRow> rows, LocalDate asOf, String label) {
        return new PerformanceCalculator(rows, dates(rows), CAPITAL).presets(asOf).stream()
                .filter(m -> m.label.equals(label)).findFirst().orElseThrow();
    }

    private static List<LocalDate> dates(List<DailyPortfolioRow> rows) {
        List<LocalDate> out = new ArrayList<>();
        rows.forEach(r -> out.add(r.date));
        return out;
    }

    /** {@code n} weekday sessions, each +1% time-weighted, with an optional contribution. */
    private static List<DailyPortfolioRow> series(LocalDate from, int n, int contributionIdx, double contribution) {
        List<DailyPortfolioRow> rows = new ArrayList<>();
        double equity = CAPITAL;
        double twr = 1.0;
        LocalDate d = from;
        while (rows.size() < n) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                double c = rows.size() == contributionIdx ? contribution : 0.0;
                equity = (equity + c) * 1.01;
                twr *= 1.01;
                rows.add(row(d, equity, twr, c));
            }
            d = d.plusDays(1);
        }
        return rows;
    }

    private static DailyPortfolioRow row(LocalDate date, double equity, double twr, double contribution) {
        return new DailyPortfolioRow(date, 0, 0, equity, 0.0, equity, contribution, 0.0, CAPITAL, 0.0, 1.0,
                twr, 0.0, 0.0, 0.0, 0.0, 0, 0.0);
    }
}
