package com.rotation.strategy;

import com.rotation.config.RotationConfig;

/**
 * Builds the {@link Strategy} named by the {@code strategy} config key.
 * To add a strategy, implement the signal-only contract and register it here.
 */
public final class RotationStrategies {

    private RotationStrategies() {
    }

    public static Strategy create(RotationConfig config) {
        String name = config.strategy();
        switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "momentum":
                return new MomentumRotationStrategy(config);
            default:
                throw new IllegalArgumentException("Unknown strategy: '" + name
                        + "'. Known strategies: momentum.");
        }
    }
}
