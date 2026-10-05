package com.rotation.strategy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.rotation.portfolio.Portfolio;

/** Immutable holdings-only snapshot exposed to signal logic. */
public final class PortfolioView {

    private final Map<String, Double> quantities;

    public PortfolioView(Portfolio portfolio) {
        this.quantities = Collections.unmodifiableMap(new LinkedHashMap<>(portfolio.quantities()));
    }

    public Set<String> symbols() {
        return quantities.keySet();
    }

    public double quantity(String symbol) {
        return quantities.getOrDefault(symbol, 0.0);
    }

    public Map<String, Double> quantities() {
        return quantities;
    }
}
