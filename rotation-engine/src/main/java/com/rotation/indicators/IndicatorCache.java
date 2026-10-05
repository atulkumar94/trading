package com.rotation.indicators;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.rotation.market.MarketView;

/**
 * Point-in-time indicator calculations cached by symbol, parameters, and the
 * exact as-of session. ATR and ADX use Wilder smoothing from available history.
 */
public final class IndicatorCache {

    private final Map<Key, Double> values = new HashMap<>();
    private long calculationCount;

    public double sma(MarketView market, String symbol, int period) {
        requirePeriod(period);
        return cached(market, symbol, "sma", period, () -> {
            double sum = 0.0;
            for (int back = 0; back < period; back++) {
                double close = market.close(symbol, back);
                if (Double.isNaN(close)) {
                    return Double.NaN;
                }
                sum += close;
            }
            return sum / period;
        });
    }

    public double returns(MarketView market, String symbol, int period) {
        requirePeriod(period);
        return cached(market, symbol, "returns", period, () -> {
            double current = market.close(symbol, 0);
            double previous = market.close(symbol, period);
            return Double.isNaN(current) || Double.isNaN(previous) || previous == 0.0
                    ? Double.NaN : current / previous - 1.0;
        });
    }

    public double rollingLow(MarketView market, String symbol, int period) {
        requirePeriod(period);
        return cached(market, symbol, "rolling-low", period, () -> {
            double lowest = Double.POSITIVE_INFINITY;
            for (int back = 0; back < period; back++) {
                double low = market.low(symbol, back);
                if (Double.isNaN(low)) {
                    return Double.NaN;
                }
                lowest = Math.min(lowest, low);
            }
            return lowest;
        });
    }

    public double atr(MarketView market, String symbol, int period) {
        requirePeriod(period);
        return cached(market, symbol, "atr", period, () -> calculateAtr(market, symbol, period));
    }

    public double adx(MarketView market, String symbol, int period) {
        requirePeriod(period);
        return cached(market, symbol, "adx", period, () -> calculateAdx(market, symbol, period));
    }

    public long calculationCount() {
        return calculationCount;
    }

    private double calculateAtr(MarketView market, String symbol, int period) {
        int contiguousSessions = contiguousSessions(market, symbol);
        if (contiguousSessions <= period) {
            return Double.NaN;
        }
        double smoothed = 0.0;
        int lastIndex = market.visibleSessionCount() - 1;
        int firstIndex = lastIndex - contiguousSessions + 1;
        for (int index = firstIndex + 1; index <= firstIndex + period; index++) {
            double trueRange = trueRange(market, symbol, lastIndex - index);
            if (Double.isNaN(trueRange)) {
                return Double.NaN;
            }
            smoothed += trueRange;
        }
        smoothed /= period;
        for (int index = firstIndex + period + 1; index <= lastIndex; index++) {
            double trueRange = trueRange(market, symbol, lastIndex - index);
            if (Double.isNaN(trueRange)) {
                return Double.NaN;
            }
            smoothed = (smoothed * (period - 1) + trueRange) / period;
        }
        return smoothed;
    }

    private double calculateAdx(MarketView market, String symbol, int period) {
        int contiguousSessions = contiguousSessions(market, symbol);
        if (contiguousSessions < period * 2) {
            return Double.NaN;
        }
        double smoothedTrueRange = 0.0;
        double smoothedPlus = 0.0;
        double smoothedMinus = 0.0;
        double adx = Double.NaN;
        double dxSeed = 0.0;
        int dxCount = 0;
        int lastIndex = market.visibleSessionCount() - 1;
        int firstIndex = lastIndex - contiguousSessions + 1;
        for (int index = firstIndex + 1; index <= lastIndex; index++) {
            int back = lastIndex - index;
            double[] directional = directionalMovement(market, symbol, back);
            if (Double.isNaN(directional[0])) {
                return Double.NaN;
            }
            if (index <= firstIndex + period) {
                smoothedTrueRange += directional[0];
                smoothedPlus += directional[1];
                smoothedMinus += directional[2];
                if (index < firstIndex + period) {
                    continue;
                }
            } else {
                smoothedTrueRange = smoothedTrueRange - smoothedTrueRange / period + directional[0];
                smoothedPlus = smoothedPlus - smoothedPlus / period + directional[1];
                smoothedMinus = smoothedMinus - smoothedMinus / period + directional[2];
            }
            double dx = directionalIndex(smoothedTrueRange, smoothedPlus, smoothedMinus);
            if (dxCount < period) {
                dxSeed += dx;
                dxCount++;
                if (dxCount == period) {
                    adx = dxSeed / period;
                }
            } else {
                adx = (adx * (period - 1) + dx) / period;
            }
        }
        return adx;
    }

    private int contiguousSessions(MarketView market, String symbol) {
        int count = 0;
        for (int back = 0; back < market.visibleSessionCount(); back++) {
            if (Double.isNaN(market.open(symbol, back)) || Double.isNaN(market.high(symbol, back))
                    || Double.isNaN(market.low(symbol, back)) || Double.isNaN(market.close(symbol, back))) {
                break;
            }
            count++;
        }
        return count;
    }

    private double trueRange(MarketView market, String symbol, int back) {
        double high = market.high(symbol, back);
        double low = market.low(symbol, back);
        double previousClose = market.close(symbol, back + 1);
        if (Double.isNaN(high) || Double.isNaN(low) || Double.isNaN(previousClose)) {
            return Double.NaN;
        }
        return Math.max(high - low,
                Math.max(Math.abs(high - previousClose), Math.abs(low - previousClose)));
    }

    private double[] directionalMovement(MarketView market, String symbol, int back) {
        double high = market.high(symbol, back);
        double low = market.low(symbol, back);
        double previousHigh = market.high(symbol, back + 1);
        double previousLow = market.low(symbol, back + 1);
        double trueRange = trueRange(market, symbol, back);
        if (Double.isNaN(high) || Double.isNaN(low) || Double.isNaN(previousHigh)
                || Double.isNaN(previousLow) || Double.isNaN(trueRange)) {
            return new double[] {Double.NaN, Double.NaN, Double.NaN};
        }
        double up = high - previousHigh;
        double down = previousLow - low;
        double plus = up > down && up > 0.0 ? up : 0.0;
        double minus = down > up && down > 0.0 ? down : 0.0;
        return new double[] {trueRange, plus, minus};
    }

    private double directionalIndex(double trueRange, double plus, double minus) {
        if (trueRange == 0.0) {
            return 0.0;
        }
        double plusIndex = 100.0 * plus / trueRange;
        double minusIndex = 100.0 * minus / trueRange;
        double sum = plusIndex + minusIndex;
        return sum == 0.0 ? 0.0 : 100.0 * Math.abs(plusIndex - minusIndex) / sum;
    }

    private double cached(MarketView market, String symbol, String indicator, int period,
                         java.util.function.DoubleSupplier calculation) {
        Key key = new Key(symbol, indicator, period, market.sessionIndex());
        Double value = values.get(key);
        if (value != null) {
            return value;
        }
        calculationCount++;
        double computed = calculation.getAsDouble();
        values.put(key, computed);
        return computed;
    }

    private static void requirePeriod(int period) {
        if (period <= 0) {
            throw new IllegalArgumentException("Indicator period must be positive");
        }
    }

    private static final class Key {
        private final String symbol;
        private final String indicator;
        private final int period;
        private final int session;

        Key(String symbol, String indicator, int period, int session) {
            this.symbol = symbol;
            this.indicator = indicator;
            this.period = period;
            this.session = session;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Key)) {
                return false;
            }
            Key key = (Key) other;
            return period == key.period && session == key.session
                    && Objects.equals(symbol, key.symbol) && Objects.equals(indicator, key.indicator);
        }

        @Override
        public int hashCode() {
            return Objects.hash(symbol, indicator, period, session);
        }
    }
}