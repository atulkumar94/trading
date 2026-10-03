package com.rotation.strategy;

import java.util.Locale;

import com.rotation.config.RotationConfig;
import com.rotation.engine.RotationEngine;
import com.rotation.strategy.breakout.BreakoutConfig;
import com.rotation.strategy.breakout.BreakoutStrategy;

/**
 * Builds the top-level {@link Strategy} named by the {@code strategy} config key.
 *
 * <p>This is the single plug point the pipeline uses after the candles are built:
 * to add a new strategy, implement {@link Strategy} and add a case here. The
 * default {@code momentum} maps to the self-contained rotation strategy (the
 * {@link RotationEngine} plus its {@link RotationStrategy} selection layer);
 * {@code breakout} maps to the standalone {@link BreakoutStrategy}.
 */
public final class Strategies {

    private Strategies() {
    }

    public static Strategy create(RotationConfig config) {
        String name = config.strategy();
        switch (name.toLowerCase(Locale.ROOT)) {
            case "momentum":
                return new RotationEngine(config);
            case "breakout":
                return new BreakoutStrategy(BreakoutConfig.load(config.sourceFile()), config.startDate());
            default:
                throw new IllegalArgumentException("Unknown strategy: '" + name
                        + "'. Known strategies: momentum, breakout.");
        }
    }
}
