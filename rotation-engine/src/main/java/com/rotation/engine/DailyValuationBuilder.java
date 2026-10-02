package com.rotation.engine;

import com.rotation.model.BacktestResult;
import com.rotation.model.DailyBars;
import com.rotation.model.DailyMark;
import com.rotation.model.DailyPortfolioRow;
import com.rotation.model.DailyPositionRow;
import com.rotation.model.DailyValuation;
import com.rotation.model.LedgerFill;
import com.rotation.model.TradeLedgerRow;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the engine's fills and end-of-day marks into daily accounting reports.
 *
 * <p>The fills are replayed on an independent cash ledger (capital + contributions
 * - buys + sells) with average-cost position accounting, and every session is valued
 * at its adjusted close. Account equity is the engine's own mark-to-market figure, so
 * {@code equity - (cash + market value)} is a genuine reconciliation, not a tautology.
 * The replayed share counts must match the engine's book every day; any divergence
 * is a bug and fails loudly.
 *
 * <p>Timing: a row for session D is the end-of-day state after every execution at D's
 * open (stop fills triggered by D-1's close, then the rebalance), valued at D's close.
 * Stops triggered by D's close fill at D+1's open and appear in D+1's row.
 */
public final class DailyValuationBuilder {

    private static final double QTY_TOLERANCE = 1e-9;

    private final Map<String, String> sectorBySymbol;

    public DailyValuationBuilder(Map<String, String> sectorBySymbol) {
        this.sectorBySymbol = sectorBySymbol == null ? Map.of() : sectorBySymbol;
    }

    public DailyValuation build(DailyBars bars, BacktestResult result) {
        double initialCapital = result.initialCapital();
        List<LocalDate> dates = bars.dates();
        Map<LocalDate, Integer> dateIndex = new HashMap<>();
        for (int i = 0; i < dates.size(); i++) {
            dateIndex.put(dates.get(i), i);
        }
        Map<String, Integer> symbolIndex = new HashMap<>();
        for (int i = 0; i < bars.symbols().size(); i++) {
            symbolIndex.put(bars.symbols().get(i), i);
        }

        List<DailyPortfolioRow> portfolioRows = new ArrayList<>();
        List<DailyPositionRow> positionRows = new ArrayList<>();
        List<TradeLedgerRow> ledgerRows = new ArrayList<>();

        Map<String, Position> book = new LinkedHashMap<>();
        List<LedgerFill> fills = result.fills();
        int nextFill = 0;
        double cash = initialCapital;
        double realizedCumulative = 0.0;
        double cumulativeContributions = 0.0;
        double previousEquity = initialCapital; // inception: capital before the first session
        double twrIndex = 1.0;
        double twrPeak = 1.0;

        for (DailyMark mark : result.dailyMarks()) {
            Integer dayIdx = dateIndex.get(mark.date);
            if (dayIdx == null) {
                throw new IllegalStateException("Daily mark date not in bars: " + mark.date);
            }
            // External cash arrives at the open, before that session's trades.
            cash += mark.contribution;
            cumulativeContributions += mark.contribution;

            while (nextFill < fills.size() && !fills.get(nextFill).date.isAfter(mark.date)) {
                LedgerFill fill = fills.get(nextFill++);
                if (fill.date.isBefore(mark.date)) {
                    throw new IllegalStateException("Fill on " + fill.date + " precedes the session being valued ("
                            + mark.date + "); fills must be dated on trading sessions in order");
                }
                Position position = book.get(fill.symbol);
                double avgBefore = position == null ? 0.0 : position.averageCost();
                Double realized = null;
                double value = fill.quantity * fill.price;
                if (fill.isBuy()) {
                    if (position == null) {
                        if (!fill.action.equals(LedgerFill.ENTRY)) {
                            throw new IllegalStateException(fill.action + " for " + fill.symbol + " on "
                                    + fill.date + " without an open position");
                        }
                        position = new Position(fill.date, fill.price);
                        book.put(fill.symbol, position);
                    } else if (fill.action.equals(LedgerFill.ENTRY)) {
                        throw new IllegalStateException("ENTRY for already-held " + fill.symbol + " on " + fill.date);
                    }
                    position.quantity += fill.quantity;
                    position.cost += value;
                    cash -= value;
                } else {
                    if (position == null || fill.quantity > position.quantity + QTY_TOLERANCE) {
                        throw new IllegalStateException(fill.action + " of " + fill.quantity + " " + fill.symbol
                                + " on " + fill.date + " exceeds the held quantity");
                    }
                    realized = (fill.price - avgBefore) * fill.quantity;
                    realizedCumulative += realized;
                    position.cost -= avgBefore * fill.quantity;
                    position.quantity -= fill.quantity;
                    cash += value;
                    boolean closes = !fill.action.equals(LedgerFill.TRIM);
                    if (closes && Math.abs(position.quantity) > QTY_TOLERANCE) {
                        throw new IllegalStateException(fill.action + " for " + fill.symbol + " on " + fill.date
                                + " left " + position.quantity + " shares open");
                    }
                }
                double positionAfter = position.quantity;
                double avgAfter = positionAfter > QTY_TOLERANCE ? position.averageCost() : 0.0;
                Double pnlVsEntry = fill.action.equals(LedgerFill.EXIT) || fill.action.equals(LedgerFill.STOP)
                        ? (fill.price - position.entryPrice) * fill.quantity
                        : null;
                ledgerRows.add(new TradeLedgerRow(fill.date, fill.signalDate, fill.rebalanceNumber, fill.action,
                        fill.symbol, fill.quantity, fill.price, value, position.entryDate, position.entryPrice,
                        avgBefore, avgAfter, positionAfter, realized, pnlVsEntry, cash, fill.reason));
                // Only closing actions remove a name: the engine can hold a zero-share
                // position (whole-lot sizing below one share) and keeps it in its book.
                if (!fill.isBuy() && !fill.action.equals(LedgerFill.TRIM)) {
                    book.remove(fill.symbol);
                }
            }

            requireBookMatches(mark, book);

            double invested = 0.0;
            double unrealized = 0.0;
            int missing = 0;
            List<PendingPosition> pending = new ArrayList<>();
            for (Map.Entry<String, Position> entry : book.entrySet()) {
                String symbol = entry.getKey();
                Position position = entry.getValue();
                int col = symbolIndex.getOrDefault(symbol, -1);
                double close = col < 0 ? Double.NaN : bars.closeAt(dayIdx, col);
                String status;
                double marketValue;
                if (Double.isNaN(close)) {
                    // Matches RotationEngine's mark-to-market, which skips (zero-values) a
                    // holding without a close. Flagged so the report never hides it.
                    status = DailyPositionRow.PRICE_MISSING;
                    marketValue = 0.0;
                    missing++;
                } else {
                    status = unchangedBar(bars, dayIdx, col) ? DailyPositionRow.PRICE_UNCHANGED
                            : DailyPositionRow.PRICE_OK;
                    marketValue = position.quantity * close;
                }
                invested += marketValue;
                unrealized += marketValue - position.cost;
                pending.add(new PendingPosition(symbol, position, Double.isNaN(close) ? null : close,
                        marketValue, status));
            }

            double equity = mark.accountEquity;
            for (PendingPosition p : pending) {
                double upnl = p.marketValue - p.position.cost;
                positionRows.add(new DailyPositionRow(mark.date, p.symbol, sectorBySymbol.get(p.symbol),
                        p.position.entryDate, mark.rebalanceNumber, p.position.quantity, p.position.entryPrice,
                        p.position.averageCost(), p.position.cost, p.close, p.marketValue, upnl,
                        p.position.cost == 0.0 ? 0.0 : upnl / p.position.cost * 100.0,
                        equity == 0.0 ? 0.0 : p.marketValue / equity * 100.0, p.status));
            }

            // Contribution-adjusted (time-weighted) daily return: the contribution is
            // invested from the open, so it joins the starting capital of the session.
            double base = previousEquity + mark.contribution;
            Double dailyReturnPct = base > 0.0 ? (equity / base - 1.0) * 100.0 : null;
            if (dailyReturnPct != null) {
                twrIndex *= 1.0 + dailyReturnPct / 100.0;
            }
            twrPeak = Math.max(twrPeak, twrIndex);
            double drawdownPct = (twrIndex / twrPeak - 1.0) * 100.0;
            double netCapital = initialCapital + cumulativeContributions;

            portfolioRows.add(new DailyPortfolioRow(mark.date, mark.rebalanceNumber, book.size(), cash, invested,
                    equity, mark.contribution, cumulativeContributions, netCapital,
                    equity - previousEquity - mark.contribution, dailyReturnPct, twrIndex, drawdownPct,
                    realizedCumulative, unrealized, equity - netCapital, missing, equity - (cash + invested)));
            previousEquity = equity;
        }

        if (nextFill < fills.size()) {
            LedgerFill orphan = fills.get(nextFill);
            throw new IllegalStateException("Fill on " + orphan.date + " (" + orphan.action + " " + orphan.symbol
                    + ") falls after the last valued session");
        }
        return new DailyValuation(portfolioRows, positionRows, ledgerRows, initialCapital);
    }

    private static void requireBookMatches(DailyMark mark, Map<String, Position> book) {
        if (!mark.holdings.keySet().equals(book.keySet())) {
            throw new IllegalStateException("Ledger replay diverged from engine holdings on " + mark.date
                    + ": engine " + mark.holdings.keySet() + " vs ledger " + book.keySet());
        }
        for (Map.Entry<String, Double> entry : mark.holdings.entrySet()) {
            double ledgerQty = book.get(entry.getKey()).quantity;
            if (Math.abs(ledgerQty - entry.getValue()) > QTY_TOLERANCE) {
                throw new IllegalStateException("Ledger quantity for " + entry.getKey() + " on " + mark.date
                        + " is " + ledgerQty + " but the engine holds " + entry.getValue());
            }
        }
    }

    /** True when the session's OHLC equals the prior session's (a likely forward-filled bar). */
    private static boolean unchangedBar(DailyBars bars, int dayIdx, int col) {
        if (dayIdx == 0) {
            return false;
        }
        return bars.openAt(dayIdx, col) == bars.openAt(dayIdx - 1, col)
                && bars.highAt(dayIdx, col) == bars.highAt(dayIdx - 1, col)
                && bars.lowAt(dayIdx, col) == bars.lowAt(dayIdx - 1, col)
                && bars.closeAt(dayIdx, col) == bars.closeAt(dayIdx - 1, col);
    }

    /** Mutable replay state for one open position (internal to the builder). */
    private static final class Position {
        final LocalDate entryDate;
        final double entryPrice;
        double quantity;
        double cost;

        Position(LocalDate entryDate, double entryPrice) {
            this.entryDate = entryDate;
            this.entryPrice = entryPrice;
        }

        double averageCost() {
            return quantity > QTY_TOLERANCE ? cost / quantity : 0.0;
        }
    }

    private static final class PendingPosition {
        final String symbol;
        final Position position;
        final Double close;
        final double marketValue;
        final String status;

        PendingPosition(String symbol, Position position, Double close, double marketValue, String status) {
            this.symbol = symbol;
            this.position = position;
            this.close = close;
            this.marketValue = marketValue;
            this.status = status;
        }
    }
}
