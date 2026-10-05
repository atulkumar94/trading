package com.rotation.strategy;

import java.util.List;
import java.util.Set;

import com.rotation.market.MarketView;

/**
 * The pluggable decision layer of the backtest: it decides <em>when</em> to
 * rebalance, <em>which</em> symbols the book should hold, and <em>when</em> to exit
 * a holding intra-period. The {@link com.rotation.engine.RotationEngine} owns the
 * mechanics — next-open execution, capital accounting and all reporting — and asks
 * the strategy for these signals.
 *
 * <p>To experiment with a new idea, implement this interface, register it in
 * {@link RotationStrategies}, and select it with the {@code strategy} config key.
 * The current momentum rotation lives in {@link MomentumRotationStrategy}.
 *
 * <p>A strategy holds its own tunables (top.n, exit.n, sector cap, lookback,
 * rebalance cadence, stops, …) from the {@link com.rotation.config.RotationConfig}
 * it is constructed with, so these methods only take the per-signal market context.
 */
public interface RotationStrategy {

    /** Human-readable id, matched case-insensitively against the {@code strategy} config key. */
    String name();

    /** Minimum warm-up sessions required before the strategy can rank a signal. */
    int warmupSessions();

    /**
     * Trading-session indices at which the strategy makes decisions. The engine
     * supplies the first signal with eligible candidates after evaluating each
     * session through its own point-in-time MarketView.
     */
    List<Integer> rebalanceSignals(int sessionCount, int firstEligibleSignalIdx);

    /** Short label describing the rebalance schedule, used in verbose run logs. */
    default String scheduleLabel() {
        return name() + "-schedule";
    }

    /**
     * Score and rank the eligible universe at a signal date, best first. The engine
    * supplies a view ending at the signal session's close. The view cannot expose
    * later sessions; the strategy owns its scoring window and eligibility rules.
     */
    List<Candidate> rank(MarketView market, int minHistory);

    /**
     * The core names that are always entered this period (the sector-capped top-N).
     * Used by the engine to label which selected names are core vs. exit-buffer holds.
     */
    List<String> selectCore(List<Candidate> ranked);

    /**
     * The full book to hold this period, given the names currently held. This is the
     * core selection plus any exit-buffer retention the strategy applies (e.g. keep a
     * held name until it drops out of the top exit.n). Order is not significant.
     */
    List<String> select(List<Candidate> ranked, Set<String> currentHoldings);

    /**
     * Optional intra-period exit rule evaluated by the engine on each day's close.
     * The default is {@link ExitPolicy#NONE} — no strategy-level exit, so holdings are
     * only left through rotation. A strategy that wants stop-losses, trailing stops or
     * any other price/time exit returns its own {@link ExitPolicy}; the engine still
     * performs the actual sell at the next open and the accounting.
     */
    default ExitPolicy exitPolicy() {
        return ExitPolicy.NONE;
    }
}
