package com.rotation.strategy;

import com.rotation.config.RotationConfig;

/**
 * Builds the {@link RotationStrategy} named by the {@code strategy} config key.
 *
 * <p>This is the single plug point: to add a new strategy, implement
 * {@link RotationStrategy} and add a case here. Everything else (engine,
 * reporting, config) is unchanged. The default is {@code momentum}.
 */
public final class RotationStrategies {

    private RotationStrategies() {
    }

    public static RotationStrategy create(RotationConfig config) {
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
