package com.rotation.strategy;

import java.util.Locale;

/**
 * Hard stop-loss and/or trailing stop evaluated on the daily close, filled at the
 * next open by the engine. Either percentage {@code <= 0} disables that leg; when
 * both are disabled the policy is inactive and never fires. When both are set the
 * tighter (higher) level triggers first.
 *
 * <p>This is the exact stop logic that previously lived inside the engine, now a
 * reusable policy any strategy can return from {@link RotationStrategy#exitPolicy()}.
 */
public final class StopLossExitPolicy implements ExitPolicy {

    private final double stopLossPct;
    private final double trailingStopPct;

    public StopLossExitPolicy(double stopLossPct, double trailingStopPct) {
        this.stopLossPct = stopLossPct;
        this.trailingStopPct = trailingStopPct;
    }

    @Override
    public boolean active() {
        return stopLossPct > 0.0 || trailingStopPct > 0.0;
    }

    @Override
    public Trigger evaluate(double basis, double peakClose, double close) {
        double stopLevel = Double.NEGATIVE_INFINITY;
        double hardLevel = Double.NEGATIVE_INFINITY;
        if (stopLossPct > 0.0) {
            hardLevel = basis * (1.0 - stopLossPct / 100.0);
            stopLevel = Math.max(stopLevel, hardLevel);
        }
        if (trailingStopPct > 0.0) {
            stopLevel = Math.max(stopLevel, peakClose * (1.0 - trailingStopPct / 100.0));
        }
        if (stopLevel == Double.NEGATIVE_INFINITY || Double.isNaN(close) || close > stopLevel) {
            return null;
        }
        String rule = hardLevel >= stopLevel
                ? String.format(Locale.US, "Stop-loss %.2f%% below period entry %.2f", stopLossPct, basis)
                : String.format(Locale.US, "Trailing stop %.2f%% below peak close %.2f", trailingStopPct, peakClose);
        return new Trigger(stopLevel, rule);
    }
}
