package com.rotation.strategy.breakout;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;

import com.rotation.model.BacktestResult;
import com.rotation.model.DailyBars;
import com.rotation.model.DailyMark;
import com.rotation.model.EquityRow;
import com.rotation.model.HoldingsRow;
import com.rotation.model.LedgerFill;
import com.rotation.model.RebalanceRecord;
import com.rotation.model.TradebookRow;
import com.rotation.model.YearEndEquity;
import com.rotation.strategy.Strategy;
import com.rotation.strategy.breakout.BreakoutExits.ExitReason;

/**
 * Self-contained execution engine for the 10 DMA breakout strategy. It shares none of
 * the rotation machinery: entries and exits come from {@link BreakoutSignals} and
 * {@link BreakoutExits}, and this class owns the accounting.
 *
 * <p>Timing (no look-ahead): signals are read on bar {@code t}'s close and filled at
 * {@code t + 1}'s open. The only intraday event is the initial hard stop (E), which
 * rests from entry and fills during the session — at the open on a gap-down, otherwise
 * at the stop price. Each session is marked to its close as {@code cash + Σ shares·close}
 * and that figure is the daily mark the reporting layer reconciles against, so the
 * transaction cost is baked into the fill price rather than tracked separately.
 *
 * <p>Sizing caps each new position at {@code maxWeightPct} of account equity (and at the
 * free cash), whole shares only, with at most {@code maxPositions} open at once; when more
 * entries fire than open slots, the highest relative-strength names are taken first.
 */
public final class BreakoutStrategy implements Strategy {

    private final BreakoutConfig cfg;
    private final LocalDate startDate;

    public BreakoutStrategy(BreakoutConfig cfg, LocalDate startDate) {
        this.cfg = cfg;
        this.startDate = startDate;
    }

    @Override
    public String name() {
        return "breakout";
    }

    @Override
    public BacktestResult run(DailyBars bars) {
        int symbolCount = bars.symbolCount();
        int dateCount = bars.dateCount();
        List<String> symbols = bars.symbols();
        List<LocalDate> dates = bars.dates();

        double[][] open = new double[symbolCount][dateCount];
        double[][] high = new double[symbolCount][dateCount];
        double[][] low = new double[symbolCount][dateCount];
        double[][] close = new double[symbolCount][dateCount];
        double[][] volume = new double[symbolCount][dateCount];
        for (int s = 0; s < symbolCount; s++) {
            for (int d = 0; d < dateCount; d++) {
                open[s][d] = bars.openAt(d, s);
                high[s][d] = bars.highAt(d, s);
                low[s][d] = bars.lowAt(d, s);
                close[s][d] = bars.closeAt(d, s);
                volume[s][d] = bars.volumeAt(d, s);
            }
        }

        BreakoutSignals[] signals = new BreakoutSignals[symbolCount];
        BreakoutExits[] exits = new BreakoutExits[symbolCount];
        for (int s = 0; s < symbolCount; s++) {
            signals[s] = new BreakoutSignals(high[s], low[s], close[s], volume[s], cfg);
            exits[s] = new BreakoutExits(high[s], low[s], close[s], cfg);
        }

        double costRate = cfg.costBps() / 10_000.0;

        double cash = cfg.capital();
        Map<Integer, Position> book = new LinkedHashMap<>();
        List<LedgerFill> fills = new ArrayList<>();
        List<TradebookRow> tradebook = new ArrayList<>();
        List<DailyMark> marks = new ArrayList<>();

        // Reporting (parallel to momentum's reports). A "rebalance event" is any session
        // on which the book changed, i.e. at least one fill executed at the open.
        List<RebalanceRecord> rebalances = new ArrayList<>();
        List<EquityRow> equityRows = new ArrayList<>();
        List<HoldingsRow> holdingsRows = new ArrayList<>();
        double prevEventEquity = cfg.capital();
        LocalDate prevEventDate = null;

        List<PendingOrder> pendingOrders = new ArrayList<>();
        List<EntryOrder> pendingEntries = new ArrayList<>();

        int startIdx = tradeStartIndex(dates, startDate);

        for (int i = startIdx; i < dateCount; i++) {
            LocalDate date = dates.get(i);
            int fillsAtDayStart = fills.size();
            Map<Integer, Double> bookAtStart = new LinkedHashMap<>();
            for (Map.Entry<Integer, Position> held : book.entrySet()) {
                bookAtStart.put(held.getKey(), held.getValue().shares);
            }

            // 1. Full exits (A–D) signalled on the prior close, filled at today's open.
            for (PendingOrder order : pendingOrders) {
                if (order.type != OrderType.EXIT) {
                    continue;
                }
                Position pos = book.get(order.sym);
                if (pos == null) {
                    continue;
                }
                double px = sellPrice(open[order.sym][i], costRate);
                if (Double.isNaN(px)) {
                    continue; // no open to trade on; hold until a priced session
                }
                cash += pos.shares * px;
                emitSell(fills, tradebook, LedgerFill.EXIT, symbols.get(order.sym), pos, pos.shares, px,
                        date, order.signalDate, cash, order.reason);
                book.remove(order.sym);
            }

            // 2. Partial profit-taking (TRIM) signalled on the prior close.
            for (PendingOrder order : pendingOrders) {
                if (order.type != OrderType.TRIM) {
                    continue;
                }
                Position pos = book.get(order.sym);
                if (pos == null) {
                    continue;
                }
                double px = sellPrice(open[order.sym][i], costRate);
                if (Double.isNaN(px)) {
                    continue;
                }
                double qty = Math.floor(pos.shares * cfg.partialFraction());
                if (qty < 1) {
                    continue;
                }
                cash += qty * px;
                fills.add(new LedgerFill(date, order.signalDate, 0, LedgerFill.TRIM, symbols.get(order.sym),
                        qty, px, order.reason));
                pos.shares -= qty;
            }

            // 3. New entries signalled on the prior close, filled at today's open.
            for (EntryOrder entry : pendingEntries) {
                if (book.size() >= cfg.maxPositions()) {
                    break;
                }
                if (book.containsKey(entry.sym)) {
                    continue;
                }
                double px = buyPrice(open[entry.sym][i], costRate);
                if (Double.isNaN(px) || px <= 0.0) {
                    continue;
                }
                double equityOpen = cash + marketValue(book, open, i);
                double capValue = Math.min(cfg.maxWeightPct() / 100.0 * equityOpen, cash);
                double shares = Math.floor(capValue / px);
                if (shares < 1) {
                    continue;
                }
                cash -= shares * px;
                Position pos = new Position(i, px, shares, date);
                pos.hardStop = exits[entry.sym].hardStopLevel(i, px);
                pos.profitTarget = exits[entry.sym].profitTargetLevel(px, pos.hardStop);
                book.put(entry.sym, pos);
                emitBuy(fills, tradebook, symbols.get(entry.sym), pos, shares, px, date, entry.signalDate, cash);
            }

            // 4. Intraday hard stop (E) for every open position, including today's entries.
            List<Integer> stopped = new ArrayList<>();
            for (Map.Entry<Integer, Position> held : book.entrySet()) {
                int sym = held.getKey();
                Position pos = held.getValue();
                OptionalDouble fill = exits[sym].hardStopFill(pos.hardStop, open[sym][i], low[sym][i]);
                if (fill.isEmpty()) {
                    continue;
                }
                double px = sellPrice(fill.getAsDouble(), costRate);
                cash += pos.shares * px;
                LocalDate signalDate = i > 0 ? dates.get(i - 1) : date;
                emitSell(fills, tradebook, LedgerFill.STOP, symbols.get(sym), pos, pos.shares, px,
                        date, signalDate, cash, "hard stop");
                stopped.add(sym);
            }
            for (int sym : stopped) {
                book.remove(sym);
            }

            pendingOrders = new ArrayList<>();
            pendingEntries = new ArrayList<>();

            // 5. Close of day: update position state and read next day's signals.
            for (Map.Entry<Integer, Position> held : book.entrySet()) {
                int sym = held.getKey();
                Position pos = held.getValue();
                double c = close[sym][i];
                if (!Double.isNaN(c) && c > pos.highestClose) {
                    pos.highestClose = c;
                }
                pos.consecutiveBelow = exits[sym].isBelowFast(i) ? pos.consecutiveBelow + 1 : 0;

                ExitReason reason = exits[sym].closeExit(i, pos.highestClose, pos.consecutiveBelow);
                if (reason != null) {
                    pendingOrders.add(new PendingOrder(sym, OrderType.EXIT, reason.name(), date));
                } else if (!pos.partialTaken && exits[sym].reachedProfitTarget(i, pos.profitTarget)) {
                    pendingOrders.add(new PendingOrder(sym, OrderType.TRIM, "PARTIAL_TARGET", date));
                    pos.partialTaken = true;
                }
            }

            List<EntryOrder> candidates = new ArrayList<>();
            for (int s = 0; s < symbolCount; s++) {
                if (book.containsKey(s)) {
                    continue;
                }
                if (signals[s].isEntry(i)) {
                    candidates.add(new EntryOrder(s, date, signals[s].rankScore(i)));
                }
            }
            candidates.sort(Comparator.comparingDouble((EntryOrder e) -> rankKey(e.score)).reversed());
            pendingEntries = candidates;

            // 6. Mark to market at the close.
            double invested = marketValue(book, close, i);
            double equity = cash + invested;
            marks.add(new DailyMark(date, equity, 0.0, 0, holdings(book, symbols)));

            // 7. Report the day as a rebalance event if the book changed. Figures are struck
            // at this session's open (matching the reconciler's open-mark identity) from the
            // same end-of-day cash and book that back the daily valuation.
            if (fills.size() > fillsAtDayStart) {
                LocalDate signalDate = i > 0 ? dates.get(i - 1) : date;
                double afterOpen = valueAt(book, open, i);
                double beforeOpen = valueOfShares(bookAtStart, open, i);
                double equityOpen = cash + afterOpen;
                List<String> entered = new ArrayList<>();
                List<String> exited = new ArrayList<>();
                for (int f = fillsAtDayStart; f < fills.size(); f++) {
                    LedgerFill fill = fills.get(f);
                    if (fill.action.equals(LedgerFill.ENTRY)) {
                        entered.add(fill.symbol);
                    } else if (fill.action.equals(LedgerFill.EXIT) || fill.action.equals(LedgerFill.STOP)) {
                        exited.add(fill.symbol);
                    }
                }
                String heldStr = String.join(",", symbolNames(book, symbols));
                double periodPnl = equityOpen - prevEventEquity;
                double periodReturnPct = prevEventEquity != 0.0 ? periodPnl / prevEventEquity * 100.0 : 0.0;
                double capPerStock = book.isEmpty() ? 0.0 : afterOpen / book.size();
                rebalances.add(new RebalanceRecord(date, signalDate, round2(beforeOpen), round2(afterOpen),
                        round2(equityOpen), round2(periodPnl), book.size(), round2(capPerStock),
                        String.join(",", entered), String.join(",", exited), heldStr, round2(periodReturnPct)));
                equityRows.add(new EquityRow(date, signalDate, round2(afterOpen), round2(equityOpen),
                        round2(periodPnl), round2(capPerStock), heldStr, null, prevEventDate, 0.0));
                double cumulativeReturnPct = (equity / cfg.capital() - 1.0) * 100.0;
                double periodReturnClosePct = prevEventEquity != 0.0 ? (equity / prevEventEquity - 1.0) * 100.0 : 0.0;
                holdingsRows.add(new HoldingsRow(date, signalDate, 0, book.size(), holdingsLabel(book, symbols),
                        round2(invested), round2(cash), round2(equity), round2(equity),
                        round2(periodReturnClosePct), round2(cumulativeReturnPct)));
                prevEventEquity = equityOpen;
                prevEventDate = date;
            }
        }

        // Final mark-to-market holdings row so the last holdings snapshot lines up with the
        // last daily session (the reconciler checks the final holdings row against it).
        if (!book.isEmpty() && dateCount > startIdx) {
            int last = dateCount - 1;
            double invested = marketValue(book, close, last);
            double equity = cash + invested;
            holdingsRows.add(new HoldingsRow(dates.get(last), dates.get(last), 0, book.size(),
                    holdingsLabel(book, symbols), round2(invested), round2(cash), round2(equity),
                    round2(equity), 0.0, round2((equity / cfg.capital() - 1.0) * 100.0)));
        }

        List<YearEndEquity> yearEndMarks = buildYearEndMarks(dates, marks, startIdx);

        return new BacktestResult(rebalances, equityRows, List.of(), tradebook, List.of(), holdingsRows,
                yearEndMarks, cfg.capital(), fills, marks);
    }

    /** Last-session equity of every calendar year the trading window spans (from the daily marks). */
    private static List<YearEndEquity> buildYearEndMarks(List<LocalDate> dates, List<DailyMark> marks, int startIdx) {
        Map<Integer, Integer> lastIdxByYear = new LinkedHashMap<>();
        for (int i = startIdx; i < dates.size(); i++) {
            lastIdxByYear.put(dates.get(i).getYear(), i); // last write per year wins
        }
        List<YearEndEquity> yearEnd = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : lastIdxByYear.entrySet()) {
            int yearEndIdx = entry.getValue();
            double equity = marks.get(yearEndIdx - startIdx).accountEquity;
            yearEnd.add(new YearEndEquity(entry.getKey(), dates.get(yearEndIdx), round2(equity)));
        }
        return yearEnd;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double valueAt(Map<Integer, Position> book, double[][] prices, int dateIdx) {
        double total = 0.0;
        for (Map.Entry<Integer, Position> held : book.entrySet()) {
            double price = prices[held.getKey()][dateIdx];
            if (!Double.isNaN(price)) {
                total += held.getValue().shares * price;
            }
        }
        return total;
    }

    private static double valueOfShares(Map<Integer, Double> shares, double[][] prices, int dateIdx) {
        double total = 0.0;
        for (Map.Entry<Integer, Double> held : shares.entrySet()) {
            double price = prices[held.getKey()][dateIdx];
            if (!Double.isNaN(price)) {
                total += held.getValue() * price;
            }
        }
        return total;
    }

    private static List<String> symbolNames(Map<Integer, Position> book, List<String> symbols) {
        List<String> names = new ArrayList<>();
        for (int sym : book.keySet()) {
            names.add(symbols.get(sym));
        }
        return names;
    }

    private static String holdingsLabel(Map<Integer, Position> book, List<String> symbols) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Position> held : book.entrySet()) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(symbols.get(held.getKey())).append(':')
                    .append(String.format(Locale.US, "%.2f", held.getValue().shares));
        }
        return sb.toString();
    }

    private static double rankKey(double score) {
        return Double.isNaN(score) ? Double.NEGATIVE_INFINITY : score;
    }

    private static int tradeStartIndex(List<LocalDate> dates, LocalDate startDate) {
        if (startDate == null) {
            return 0;
        }
        int idx = 0;
        while (idx < dates.size() && dates.get(idx).isBefore(startDate)) {
            idx++;
        }
        if (idx >= dates.size()) {
            throw new IllegalArgumentException("No trading sessions on/after start date: " + startDate);
        }
        return idx;
    }

    private static double marketValue(Map<Integer, Position> book, double[][] prices, int dateIdx) {
        double total = 0.0;
        for (Map.Entry<Integer, Position> held : book.entrySet()) {
            double price = prices[held.getKey()][dateIdx];
            if (!Double.isNaN(price)) {
                total += held.getValue().shares * price;
            }
        }
        return total;
    }

    private static Map<String, Double> holdings(Map<Integer, Position> book, List<String> symbols) {
        Map<String, Double> map = new LinkedHashMap<>();
        for (Map.Entry<Integer, Position> held : book.entrySet()) {
            map.put(symbols.get(held.getKey()), held.getValue().shares);
        }
        return map;
    }

    private static double buyPrice(double open, double costRate) {
        return Double.isNaN(open) ? Double.NaN : open * (1.0 + costRate);
    }

    private static double sellPrice(double open, double costRate) {
        return Double.isNaN(open) ? Double.NaN : open * (1.0 - costRate);
    }

    private static void emitBuy(List<LedgerFill> fills, List<TradebookRow> tradebook, String symbol,
                                Position pos, double shares, double price, LocalDate date,
                                LocalDate signalDate, double cashAfter) {
        fills.add(new LedgerFill(date, signalDate, 0, LedgerFill.ENTRY, symbol, shares, price, "breakout entry"));
        tradebook.add(new TradebookRow(date, LedgerFill.ENTRY, symbol, shares, price, shares * price,
                pos.entryPrice, null, null, null, cashAfter, signalDate, 0));
    }

    private static void emitSell(List<LedgerFill> fills, List<TradebookRow> tradebook, String action,
                                 String symbol, Position pos, double shares, double price, LocalDate date,
                                 LocalDate signalDate, double cashAfter, String reason) {
        fills.add(new LedgerFill(date, signalDate, 0, action, symbol, shares, price, reason));
        double realized = (price - pos.entryPrice) * shares;
        tradebook.add(new TradebookRow(date, action, symbol, shares, price, shares * price,
                pos.entryPrice, price, realized, null, cashAfter, signalDate, 0));
    }

    private enum OrderType {
        EXIT, TRIM
    }

    private static final class PendingOrder {
        final int sym;
        final OrderType type;
        final String reason;
        final LocalDate signalDate;

        PendingOrder(int sym, OrderType type, String reason, LocalDate signalDate) {
            this.sym = sym;
            this.type = type;
            this.reason = reason;
            this.signalDate = signalDate;
        }
    }

    private static final class EntryOrder {
        final int sym;
        final LocalDate signalDate;
        final double score;

        EntryOrder(int sym, LocalDate signalDate, double score) {
            this.sym = sym;
            this.signalDate = signalDate;
            this.score = score;
        }
    }

    private static final class Position {
        final int entryIndex;
        final double entryPrice;
        final LocalDate entryDate;
        double shares;
        double highestClose = Double.NEGATIVE_INFINITY;
        int consecutiveBelow;
        double hardStop = Double.NaN;
        double profitTarget = Double.NaN;
        boolean partialTaken;

        Position(int entryIndex, double entryPrice, double shares, LocalDate entryDate) {
            this.entryIndex = entryIndex;
            this.entryPrice = entryPrice;
            this.shares = shares;
            this.entryDate = entryDate;
        }
    }
}
