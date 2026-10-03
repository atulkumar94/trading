package com.rotation.strategy;

import com.rotation.model.BacktestResult;
import com.rotation.model.DailyBars;

/**
 * The top-level plug-in point of the backtest. Once the pipeline has built the
 * daily candles ({@link DailyBars}), a {@code Strategy} takes over and produces a
 * {@link BacktestResult}. Each strategy is fully self-contained and owns its own
 * execution: the momentum rotation strategy owns the rotation engine and its
 * selection/exit rules, while another strategy (e.g. a breakout) shares none of
 * that machinery.
 *
 * <p>To add a new strategy, implement this interface, register it in
 * {@link Strategies}, and select it with the {@code strategy} config key. This is a
 * different seam from {@link RotationStrategy}, which is only the <em>selection</em>
 * layer <em>inside</em> the rotation strategy.
 */
public interface Strategy {

    /** Human-readable id, matched case-insensitively against the {@code strategy} config key. */
    String name();

    /** Run the full backtest over the supplied daily candles. */
    BacktestResult run(DailyBars bars);
}
