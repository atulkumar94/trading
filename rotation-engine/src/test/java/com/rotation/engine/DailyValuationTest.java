package com.rotation.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.rotation.config.RotationConfig;
import com.rotation.model.BacktestResult;
import com.rotation.model.DailyBars;
import com.rotation.model.DailyCandle;
import com.rotation.model.DailyPortfolioRow;
import com.rotation.model.DailyPositionRow;
import com.rotation.model.DailyValuation;
import com.rotation.model.HoldingsRow;
import com.rotation.model.ReconciliationReport;
import com.rotation.model.SymbolDailyCandles;
import com.rotation.model.TradeLedgerRow;
import com.rotation.model.YearEndEquity;
import com.rotation.report.DailyValuationBuilder;
import com.rotation.report.ReportReconciler;

/** End-to-end daily valuation: engine run -> DailyValuationBuilder -> ReportReconciler. */
class DailyValuationTest {

    private static final double EPS = 1e-6;

    @Test
    void valuesEachSessionAtCloseAfterOpenExecution() throws IOException {
        // AAA: open 100+i, close 100.5+i (always ranks first); BBB falls.
        DailyBars bars = bars(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 3, 29),
                i -> candle(100.0 + i, 100.5 + i), i -> candle(200.0 - i, 199.5 - i));
        Run run = run(bars, "");
        List<LocalDate> dates = bars.dates();

        assertEquals(dates.size(), run.days.size(), "one row per session from the trade start");
        // Lookback 3: signal at index 2, first execution at index 3's open.
        for (int i = 0; i < 3; i++) {
            DailyPortfolioRow cashDay = run.days.get(i);
            assertEquals(0, cashDay.positions);
            assertEquals(10000.0, cashDay.accountEquity, EPS);
            assertEquals(10000.0, cashDay.cash, EPS);
        }
        DailyPortfolioRow first = run.days.get(3);
        // qty = floor(10000 / open 103) = 97, cost 9991, cash 9; valued at close 103.5.
        assertEquals(1, first.positions);
        assertEquals(9.0, first.cash, EPS);
        assertEquals(97 * 103.5, first.investedValue, EPS);
        assertEquals(9.0 + 97 * 103.5, first.accountEquity, EPS);
        assertEquals(((9.0 + 97 * 103.5) / 10000.0 - 1.0) * 100.0, first.dailyReturnPct, EPS);
        assertEquals(97 * 0.5, first.unrealizedPnl, EPS);
        assertEquals(0.0, first.realizedPnl, EPS);

        for (DailyPortfolioRow day : run.days) {
            assertEquals(day.accountEquity, day.cash + day.investedValue, 0.005, "reconciles on " + day.date);
            assertEquals(day.totalPnl, day.realizedPnl + day.unrealizedPnl, 0.005, "P&L splits on " + day.date);
        }
        assertTrue(run.report.passed(), () -> failures(run.report));
    }

    @Test
    void tradesExecuteAtTheNextOpenAfterTheirSignal() throws IOException {
        DailyBars bars = bars(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 6, 28),
                i -> candle(100.0 + i, 100.5 + i), i -> candle(200.0 - i, 199.5 - i));
        Run run = run(bars, "");
        List<LocalDate> dates = bars.dates();

        assertFalse(run.valuation.ledgerRows().isEmpty());
        for (TradeLedgerRow fill : run.valuation.ledgerRows()) {
            int signalIdx = dates.indexOf(fill.signalDate);
            assertEquals(dates.get(signalIdx + 1), fill.date, "fill executes the session after its signal");
            double open = bars.openAt(dates.indexOf(fill.date), bars.symbols().indexOf(fill.symbol));
            assertEquals(open, fill.price, EPS, "fills at the execution session's open");
        }
        // Nothing is held on the signal session itself: the book changes at the next open.
        LocalDate firstSignal = run.valuation.ledgerRows().get(0).signalDate;
        assertEquals(0, row(run, firstSignal).positions);
        for (DailyPositionRow p : run.valuation.positionRows()) {
            assertFalse(p.date.isBefore(p.entryDate), "position valued before its entry: " + p.symbol + "@" + p.date);
        }
    }

    @Test
    void stopExitsFillNextOpenAndYearEndMarkHasNoLookAhead() throws IOException {
        // AAA rises, then its 2023-12-29 close (the last 2023 session) falls 50% and the
        // next session gaps lower again. A 10% stop triggers on that close.
        LocalDate from = LocalDate.of(2023, 12, 1);
        LocalDate crash = LocalDate.of(2023, 12, 29);
        LocalDate nextSession = LocalDate.of(2024, 1, 1);
        List<LocalDate> sessions = weekdays(from, LocalDate.of(2024, 1, 31));
        int crashIdx = sessions.indexOf(crash);
        DailyBars bars = bars(from, LocalDate.of(2024, 1, 31), i -> {
            if (i < crashIdx) {
                return candle(100.0 + i, 100.5 + i);
            }
            if (i == crashIdx) {
                return candle(120.0, 60.0);
            }
            return candle(48.0 + (i - crashIdx - 1), 48.5 + (i - crashIdx - 1));
        }, i -> candle(200.0 - i, 199.5 - i));
        Run run = run(bars, "stop.loss.pct=10\n");

        List<TradeLedgerRow> stops = run.valuation.ledgerRows().stream()
                .filter(r -> r.action.equals("STOP")).collect(Collectors.toList());
        assertEquals(1, stops.size(), "exactly one stop exit");
        TradeLedgerRow stop = stops.get(0);
        assertEquals("AAA", stop.symbol);
        assertEquals(crash, stop.signalDate, "trigger close is kept as the signal date");
        assertEquals(nextSession, stop.date, "stop fills at the next session's open");
        assertEquals(48.0, stop.price, EPS);
        assertEquals((48.0 - stop.averageCostBefore) * stop.quantity, stop.realizedPnl, EPS);
        assertTrue(stop.reason.startsWith("Stop-loss"), stop.reason);

        // Still held (and valued at the crash close) at the end of the trigger session...
        DailyPositionRow held = positions(run, crash).get("AAA");
        assertNotNull(held, "position is still held at the trigger close");
        assertEquals(60.0, held.close, EPS);
        // ...and gone, with its proceeds in cash, at the end of the fill session.
        assertNull(positions(run, nextSession).get("AAA"));
        DailyPortfolioRow before = row(run, crash);
        DailyPortfolioRow after = row(run, nextSession);
        assertEquals(before.cash + stop.tradeValue, after.cash, EPS);
        assertEquals(before.realizedPnl + stop.realizedPnl, after.realizedPnl, EPS);

        // The yearly report's 2023 mark is the trigger-day close, not the next open's proceeds.
        YearEndEquity mark2023 = run.result.yearEndMarks().stream().filter(m -> m.year == 2023).findFirst()
                .orElseThrow();
        assertEquals(crash, mark2023.date);
        assertEquals(before.accountEquity, mark2023.equity, 0.006);
        assertTrue(run.report.passed(), () -> failures(run.report));
    }

    @Test
    void contributionsAreCapitalNotReturns() throws IOException {
        // Flat prices: any change in equity can only come from contributions.
        DailyBars bars = bars(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 6, 28),
                i -> candle(100.0, 100.0), i -> candle(50.0, 50.0));
        Run run = run(bars, "monthly.contribution=1000\nrebalance.mode=monthly\n");

        double contributed = run.days.stream().mapToDouble(d -> d.contribution).sum();
        assertTrue(contributed >= 4000.0, "several months of contributions: " + contributed);
        assertEquals(run.result.equityCurve().stream().mapToDouble(r -> r.contribution).sum(), contributed, EPS);
        for (DailyPortfolioRow day : run.days) {
            assertEquals(0.0, day.dailyReturnPct, 1e-9, "return on " + day.date);
            assertEquals(1.0, day.twrIndex, 1e-12);
            assertEquals(0.0, day.drawdownPct, 1e-9);
            assertEquals(0.0, day.totalPnl, 0.005);
            assertEquals(10000.0 + day.cumulativeContributions, day.accountEquity, 0.005);
            assertEquals(10000.0 + day.cumulativeContributions, day.netCapital, EPS);
            if (day.contribution > 0.0) {
                assertTrue(run.result.rebalances().stream().anyMatch(r -> r.date.equals(day.date)),
                        "contributions are credited on rebalance days only: " + day.date);
            }
        }
        // Contributions are invested at the next rebalance (ADD fills at an unchanged cost).
        assertTrue(run.valuation.ledgerRows().stream().anyMatch(r -> r.action.equals("ADD")));
        for (DailyPositionRow p : run.valuation.positionRows()) {
            assertEquals(100.0, p.averageCost, EPS);
        }
        assertTrue(run.report.passed(), () -> failures(run.report));
    }

    @Test
    void contributionAdjustedReturnUsesSameDayContributionAsCapital() throws IOException {
        DailyBars bars = bars(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 6, 28),
                i -> candle(100.0 + i, 100.5 + i), i -> candle(200.0 - i, 199.5 - i));
        Run run = run(bars, "monthly.contribution=1000\nrebalance.mode=monthly\n");
        int checked = 0;
        for (int i = 1; i < run.days.size(); i++) {
            DailyPortfolioRow day = run.days.get(i);
            DailyPortfolioRow prev = run.days.get(i - 1);
            double expected = (day.accountEquity / (prev.accountEquity + day.contribution) - 1.0) * 100.0;
            assertEquals(expected, day.dailyReturnPct, 1e-9);
            assertEquals(day.accountEquity - prev.accountEquity - day.contribution, day.dailyPnl, 1e-6);
            if (day.contribution > 0.0) {
                checked++;
            }
        }
        assertTrue(checked > 0);
        assertTrue(run.report.passed(), () -> failures(run.report));
    }

    @Test
    void historicalHoldingsMatchTheLastRebalanceBook() throws IOException {
        DailyBars bars = bars(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 6, 28),
                i -> candle(100.0 + i, 100.5 + i), i -> candle(200.0 - i, 199.5 - i));
        Run run = run(bars, "");
        List<LocalDate> dates = bars.dates();
        LocalDate firstEntry = run.result.rebalances().get(0).date;

        for (int idx = dates.indexOf(firstEntry); idx < dates.size(); idx += 7) {
            LocalDate date = dates.get(idx);
            HoldingsRow book = null;
            for (HoldingsRow h : run.result.holdingsRows()) {
                if (!h.date.isAfter(date) && h.rebalanceNumber > 0 && !h.date.equals(h.signalDate)) {
                    book = h; // latest rebalance row on/before the date (skip the final mark row)
                }
            }
            assertNotNull(book);
            Map<String, DailyPositionRow> held = positions(run, date);
            for (String part : book.holdings.split("\\|")) {
                String[] kv = part.split(":");
                DailyPositionRow p = held.get(kv[0]);
                assertNotNull(p, kv[0] + " held on " + date);
                assertEquals(Double.parseDouble(kv[1]), p.quantity, EPS);
                assertEquals(p.quantity * bars.closeAt(idx, bars.symbols().indexOf(p.symbol)), p.marketValue, EPS);
                assertEquals(book.rebalanceNumber, p.rebalanceNumber);
            }
            assertEquals(book.holdingsCount, held.size());
        }
        // AAA is held throughout: its entry date stays at the first entry while re-sizes roll on.
        for (DailyPositionRow p : run.valuation.positionRows()) {
            assertEquals("AAA", p.symbol);
            assertEquals(firstEntry, p.entryDate);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static final class Run {
        BacktestResult result;
        DailyValuation valuation;
        List<DailyPortfolioRow> days;
        ReconciliationReport report;
    }

    private static Run run(DailyBars bars, String extraConfig) throws IOException {
        RotationConfig config = config(extraConfig);
        Run run = new Run();
        run.result = new RotationEngine(config).run(bars);
        run.valuation = new DailyValuationBuilder(Map.of()).build(bars, run.result);
        run.days = run.valuation.portfolioRows();
        run.report = new ReportReconciler().check(bars, run.result, run.valuation, config.startDate());
        return run;
    }

    private static DailyPortfolioRow row(Run run, LocalDate date) {
        return run.days.stream().filter(d -> d.date.equals(date)).findFirst().orElseThrow();
    }

    private static Map<String, DailyPositionRow> positions(Run run, LocalDate date) {
        return run.valuation.positionRows().stream().filter(p -> p.date.equals(date))
                .collect(Collectors.toMap(p -> p.symbol, p -> p));
    }

    private static String failures(ReconciliationReport report) {
        return report.checks().stream().filter(c -> !c.passed).map(c -> c.name + ": " + c.detail)
                .collect(Collectors.joining("; "));
    }

    private static RotationConfig config(String extra) throws IOException {
        Path dir = Files.createTempDirectory("daily-valuation-test");
        Path file = dir.resolve("rotation.properties");
        Files.writeString(file, "lookback.days=3\n"
                + "top.n=1\n"
                + "min.history.days=1\n"
                + "rebalance.mode=weekly\n"
                + "capital.per.stock=10000\n"
                + "allocation.mode=compound\n"
                + "verbose=false\n"
                + extra);
        return RotationConfig.load(file, dir);
    }

    private static double[] candle(double open, double close) {
        return new double[] {open, close};
    }

    private static List<LocalDate> weekdays(LocalDate from, LocalDate to) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                out.add(d);
            }
        }
        return out;
    }

    /** Two symbols on weekday sessions; each function maps a session index to {open, close}. */
    private static DailyBars bars(LocalDate from, LocalDate to, IntFunction<double[]> aaa, IntFunction<double[]> bbb) {
        List<DailyCandle> a = new ArrayList<>();
        List<DailyCandle> b = new ArrayList<>();
        List<LocalDate> sessions = weekdays(from, to);
        for (int i = 0; i < sessions.size(); i++) {
            a.add(toCandle(sessions.get(i), aaa.apply(i)));
            b.add(toCandle(sessions.get(i), bbb.apply(i)));
        }
        return DailyBars.build(List.of(new SymbolDailyCandles("AAA", a), new SymbolDailyCandles("BBB", b)), false);
    }

    private static DailyCandle toCandle(LocalDate date, double[] oc) {
        double high = Math.max(oc[0], oc[1]) + 0.5;
        double low = Math.min(oc[0], oc[1]) - 0.5;
        return new DailyCandle(date, oc[0], high, low, oc[1], 1000);
    }
}
