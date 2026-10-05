package com.rotation.engine;

import com.rotation.config.RotationConfig;
import com.rotation.model.BacktestResult;
import com.rotation.model.DailyBars;
import com.rotation.strategy.Strategy;
import com.rotation.strategy.RotationStrategies;
import com.rotation.runner.BacktestRunner;

/** Compatibility facade that delegates backtest execution to the single runner. */
public final class RotationEngine {

    private final BacktestRunner runner;

    public RotationEngine(RotationConfig config) {
        this(config, RotationStrategies.create(config));
    }

    /** Run with an explicitly supplied strategy, primarily for focused tests. */
    public RotationEngine(RotationConfig config, Strategy strategy) {
        this.runner = new BacktestRunner(config, strategy);
    }

    public BacktestResult run(DailyBars bars) {
        return runner.run(bars);
    }
}
