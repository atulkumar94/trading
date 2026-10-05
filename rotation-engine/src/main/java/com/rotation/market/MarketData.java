package com.rotation.market;

import java.time.LocalDate;

import com.rotation.model.DailyBars;

/** Read-only indexed market history used to create point-in-time views. */
public final class MarketData {

    private final DailyBars bars;
    private final MarketView.IndicatorProvider indicators;

    public MarketData(DailyBars bars) {
        this.bars = bars;
        this.indicators = new MarketView.IndicatorProvider();
    }

    /** Number of aligned sessions in this market snapshot. */
    public int sessionCount() {
        return bars.dateCount();
    }

    /** Date at an aligned session index. */
    public LocalDate sessionDate(int sessionIndex) {
        return bars.dates().get(sessionIndex);
    }

    /** Create a view whose current session is the requested index. */
    public MarketView asOf(int sessionIndex) {
        if (sessionIndex < 0 || sessionIndex >= bars.dateCount()) {
            throw new IndexOutOfBoundsException("As-of session index outside market history: " + sessionIndex);
        }
        return new MarketView(bars, sessionIndex, indicators);
    }

    /** Resolve an as-of view for an exact session date. */
    public MarketView asOf(LocalDate date) {
        int sessionIndex = bars.dates().indexOf(date);
        if (sessionIndex < 0) {
            throw new IllegalArgumentException("As-of date is not an aligned market session: " + date);
        }
        return asOf(sessionIndex);
    }

    /** Resolve the index for engine scheduling and reporting, not price reads. */
    public int indexOf(LocalDate date) {
        return bars.dates().indexOf(date);
    }
}