package com.rotation.model;

import java.time.LocalDate;

/**
 * A single one-day OHLCV candle for one symbol, produced by aggregating raw
 * intraday input (ticks or minute bars).
 */
public final class DailyCandle {

    private final LocalDate date;
    private final double open;
    private final double high;
    private final double low;
    private final double close;
    private final double volume;

    public DailyCandle(LocalDate date, double open, double high, double low, double close, double volume) {
        this.date = date;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
    }

    public LocalDate date() {
        return date;
    }

    public double open() {
        return open;
    }

    public double high() {
        return high;
    }

    public double low() {
        return low;
    }

    public double close() {
        return close;
    }

    public double volume() {
        return volume;
    }
}
