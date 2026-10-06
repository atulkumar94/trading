package com.rotation.market;

import java.time.LocalDate;
import java.util.List;

import com.rotation.indicators.IndicatorCache;
import com.rotation.model.DailyBars;

/**
 * Point-in-time market access. Offset zero is the as-of session; positive
 * offsets move strictly into the past and negative offsets are rejected.
 */
public final class MarketView {

    private final DailyBars bars;
    private final int asOfIndex;
    private final IndicatorProvider indicatorProvider;

    MarketView(DailyBars bars, int asOfIndex, IndicatorProvider indicatorProvider) {
        this.bars = bars;
        this.asOfIndex = asOfIndex;
        this.indicatorProvider = indicatorProvider;
    }

    public LocalDate asOfDate() {
        return bars.dates().get(asOfIndex);
    }

    public int sessionIndex() {
        return asOfIndex;
    }

    /** Number of sessions visible through this view, including today. */
    public int visibleSessionCount() {
        return asOfIndex + 1;
    }

    public List<String> symbols() {
        return bars.symbols();
    }

    public int symbolCount() {
        return bars.symbolCount();
    }

    public LocalDate date(int back) {
        int index = indexFor(back);
        return index < 0 ? null : bars.dates().get(index);
    }

    public double open(String symbol, int back) {
        return value(symbol, back, PriceField.OPEN);
    }

    public double high(String symbol, int back) {
        return value(symbol, back, PriceField.HIGH);
    }

    public double low(String symbol, int back) {
        return value(symbol, back, PriceField.LOW);
    }

    public double close(String symbol, int back) {
        return value(symbol, back, PriceField.CLOSE);
    }

    public int eligibility(String symbol) {
        int symbolIndex = symbolIndex(symbol);
        return symbolIndex < 0 ? 0 : bars.eligibilityAt(asOfIndex, symbolIndex);
    }

    public double sma(String symbol, int period) {
        if (bars.hasDma(period)) {
            return dma(symbol, period);
        }
        return indicatorProvider.cache().sma(this, symbol, period);
    }

    /** Adjusted-close moving average for a configured {@code market.dma.periods} value. */
    public double dma(String symbol, int period) {
        return dma(symbol, period, 0);
    }

    /** DMA as of {@code back} sessions ago; NaN while the window is incomplete or the symbol is unknown. */
    public double dma(String symbol, int period, int back) {
        int index = indexFor(back);
        int symbolIndex = symbolIndex(symbol);
        if (!bars.hasDma(period)) {
            throw new IllegalArgumentException(
                    "DMA period " + period + " is not configured: " + bars.dmaPeriods());
        }
        if (index < 0 || symbolIndex < 0) {
            return Double.NaN;
        }
        return bars.dmaAt(period, index, symbolIndex);
    }

    public double atr(String symbol, int period) {
        return indicatorProvider.cache().atr(this, symbol, period);
    }

    public double adx(String symbol, int period) {
        return indicatorProvider.cache().adx(this, symbol, period);
    }

    /** Fractional close-to-close return over the requested number of sessions. */
    public double returns(String symbol, int period) {
        return indicatorProvider.cache().returns(this, symbol, period);
    }

    public double rollingLow(String symbol, int period) {
        return indicatorProvider.cache().rollingLow(this, symbol, period);
    }

    private double value(String symbol, int back, PriceField field) {
        int index = indexFor(back);
        int symbolIndex = symbolIndex(symbol);
        if (index < 0 || symbolIndex < 0) {
            return Double.NaN;
        }
        return switch (field) {
            case OPEN -> bars.openAt(index, symbolIndex);
            case HIGH -> bars.highAt(index, symbolIndex);
            case LOW -> bars.lowAt(index, symbolIndex);
            case CLOSE -> bars.closeAt(index, symbolIndex);
        };
    }

    private int indexFor(int back) {
        if (back < 0) {
            throw new IllegalArgumentException("MarketView cannot read future sessions: back must be >= 0");
        }
        int index = asOfIndex - back;
        return index < 0 ? -1 : index;
    }

    private int symbolIndex(String symbol) {
        return bars.indexOfSymbol(symbol);
    }

    private enum PriceField { OPEN, HIGH, LOW, CLOSE }

    /** Lazily creates one cache shared by every view from the same MarketData. */
    static final class IndicatorProvider {
        private IndicatorCache cache;

        IndicatorCache cache() {
            if (cache == null) {
                cache = new IndicatorCache();
            }
            return cache;
        }
    }
}