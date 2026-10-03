package com.rotation.strategy;

/**
 * Optional intra-period exit rule. The <em>strategy</em> owns the decision of
 * <em>when</em> to exit a held name (the trigger); the
 * {@link com.rotation.engine.RotationEngine} still performs the actual sell at the
 * next session's open and all the accounting. A strategy that does not define its
 * own exit rule returns {@link #NONE}, so positions are only ever left via rotation
 * (i.e. when the strategy's selection stops including them).
 */
public interface ExitPolicy {

    /** No strategy-level exit: holdings are only left through rotation. */
    ExitPolicy NONE = new ExitPolicy() {
        @Override
        public Trigger evaluate(double basis, double peakClose, double close) {
            return null;
        }

        @Override
        public boolean active() {
            return false;
        }
    };

    /**
     * Evaluate one held name on a given day's close. Returns {@code null} to keep
     * holding, or a {@link Trigger} to exit the position at the next session's open.
     * {@code basis} is the period entry price (the stop reference) and
     * {@code peakClose} the highest close since entry (for trailing rules).
     */
    Trigger evaluate(double basis, double peakClose, double close);

    /** False when the policy can never fire, letting the engine skip the daily scan. */
    default boolean active() {
        return true;
    }

    /** A fired exit: the breached stop level and a human-readable rule description. */
    final class Trigger {
        public final double stopLevel;
        public final String rule;

        public Trigger(double stopLevel, String rule) {
            this.stopLevel = stopLevel;
            this.rule = rule;
        }
    }
}
