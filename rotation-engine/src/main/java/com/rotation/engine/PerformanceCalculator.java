package com.rotation.engine;

import com.rotation.model.DailyPortfolioRow;
import com.rotation.model.RangeMetrics;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Range performance from the daily portfolio (time-weighted, contribution-adjusted).
 * The HTML portal applies the same rules in the browser for arbitrary ranges; the
 * preset values computed here are embedded in the run manifest as a cross-check.
 *
 * <p>Rules: the as-of date resolves to the last session on/before it. A range
 * {@code [from, asOf]} contains the sessions dated in it and is measured from the
 * close of the prior session (or the initial capital at inception). A range is
 * {@code truncated} when it asks for history the backtest does not have: it starts
 * before the first backtest session and a trading session (from the full data
 * calendar, incl. warm-up) exists in the missing part, or it starts before all data.
 */
public final class PerformanceCalculator {

    public static final String LAST_30_DAYS = "30D";
    public static final String LAST_30_SESSIONS = "30S";
    public static final String MONTH_TO_DATE = "MTD";
    public static final String YEAR_TO_DATE = "YTD";
    public static final String ALL = "ALL";

    private final List<DailyPortfolioRow> rows;
    private final List<LocalDate> calendar;
    private final double initialCapital;

    /**
     * @param rows           daily portfolio rows in ascending date order
     * @param calendar       every session in the loaded data (incl. pre-start warm-up), ascending
     * @param initialCapital capital at inception (the base before the first session)
     */
    public PerformanceCalculator(List<DailyPortfolioRow> rows, List<LocalDate> calendar, double initialCapital) {
        this.rows = rows;
        this.calendar = calendar;
        this.initialCapital = initialCapital;
    }

    /** Index of the last row on/before {@code date}; -1 when none. */
    public int resolveAsOf(LocalDate date) {
        int found = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).date.isAfter(date)) {
                break;
            }
            found = i;
        }
        return found;
    }

    /** Custom range; null when no session exists on/before {@code asOf} or the range is empty. */
    public RangeMetrics range(String label, LocalDate from, LocalDate asOf) {
        int end = resolveAsOf(asOf);
        if (end < 0 || from.isAfter(rows.get(end).date)) {
            return null;
        }
        int start = 0;
        while (start < end && rows.get(start).date.isBefore(from)) {
            start++;
        }
        return compute(label, from, start, end, missingHistory(from));
    }

    /** The five toolbar presets as of {@code asOf}; empty when no session exists on/before it. */
    public List<RangeMetrics> presets(LocalDate asOf) {
        List<RangeMetrics> out = new ArrayList<>();
        int end = resolveAsOf(asOf);
        if (end < 0) {
            return out;
        }
        LocalDate effective = rows.get(end).date;
        out.add(range(LAST_30_DAYS, effective.minusDays(29), effective));
        int sessionStart = end - 29;
        if (sessionStart >= 0) {
            out.add(compute(LAST_30_SESSIONS, rows.get(sessionStart).date, sessionStart, end, false));
        } else {
            out.add(compute(LAST_30_SESSIONS, rows.get(0).date, 0, end, true));
        }
        out.add(range(MONTH_TO_DATE, effective.withDayOfMonth(1), effective));
        out.add(range(YEAR_TO_DATE, effective.withDayOfYear(1), effective));
        out.add(range(ALL, rows.get(0).date, effective));
        return out;
    }

    private boolean missingHistory(LocalDate from) {
        LocalDate first = rows.get(0).date;
        if (!from.isBefore(first)) {
            return false;
        }
        if (calendar.isEmpty() || from.isBefore(calendar.get(0))) {
            return true;
        }
        for (LocalDate session : calendar) {
            if (!session.isBefore(first)) {
                break;
            }
            if (!session.isBefore(from)) {
                return true;
            }
        }
        return false;
    }

    private RangeMetrics compute(String label, LocalDate requestedFrom, int start, int end, boolean truncated) {
        boolean fromInception = start == 0;
        DailyPortfolioRow base = fromInception ? null : rows.get(start - 1);
        double baseIndex = fromInception ? 1.0 : base.twrIndex;
        double startEquity = fromInception ? initialCapital : base.accountEquity;
        DailyPortfolioRow last = rows.get(end);
        double contributions = 0.0;
        double peak = baseIndex;
        double maxDrawdown = 0.0;
        for (int i = start; i <= end; i++) {
            DailyPortfolioRow row = rows.get(i);
            contributions += row.contribution;
            peak = Math.max(peak, row.twrIndex);
            maxDrawdown = Math.min(maxDrawdown, row.twrIndex / peak - 1.0);
        }
        double growth = last.twrIndex / baseIndex;
        LocalDate measuredFrom = fromInception ? rows.get(0).date : base.date;
        long days = ChronoUnit.DAYS.between(measuredFrom, last.date);
        Double annualized = days >= 365 && growth > 0.0
                ? (Math.pow(growth, 365.25 / days) - 1.0) * 100.0
                : null;
        return new RangeMetrics(label, requestedFrom, last.date, rows.get(start).date,
                fromInception ? null : base.date, end - start + 1, startEquity, last.accountEquity, contributions,
                last.accountEquity - startEquity - contributions, (growth - 1.0) * 100.0, maxDrawdown * 100.0,
                annualized, truncated);
    }
}
