package com.rotation.portfolio;

import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.ObjDoubleConsumer;
import java.util.function.ToDoubleFunction;

/**
 * Mutable account state owned by execution: ordered positions, cash, deployed
 * cost basis, and account equity. Copies isolate valuation replays from live state.
 */
public final class Portfolio {

    private final LinkedHashMap<String, Position> positions = new LinkedHashMap<>();
    private final Map<String, Double> quantities = new PositionValueMap(
            Position::quantity, Position::setQuantity, true);
    private final Map<String, Double> entryPrices = new PositionValueMap(
            Position::entryPrice, Position::setEntryPrice, false);
    private final Map<String, Double> stopBases = new PositionValueMap(
            Position::stopBasis, Position::setStopBasis, false);
    private final Map<String, Double> peakCloses = new PositionValueMap(
            Position::peakClose, Position::setPeakClose, false);
    private double cash;
    private double deployedCapital;
    private double accountEquity;
    private int rebalanceNumber;
    private final Ledger ledger;

    public Portfolio(double initialCapital) {
        this(initialCapital, new Ledger());
    }

    public Portfolio(double initialCapital, Ledger ledger) {
        this.accountEquity = initialCapital;
        this.ledger = ledger;
    }

    private Portfolio(Portfolio source) {
        source.positions.forEach((symbol, position) -> positions.put(symbol, position.copy()));
        cash = source.cash;
        deployedCapital = source.deployedCapital;
        accountEquity = source.accountEquity;
        rebalanceNumber = source.rebalanceNumber;
        ledger = new Ledger();
    }

    public Map<String, Position> positions() {
        return positions;
    }

    public Map<String, Double> quantities() {
        return quantities;
    }

    public Map<String, Double> entryPrices() {
        return entryPrices;
    }

    public Map<String, Double> stopBases() {
        return stopBases;
    }

    public Map<String, Double> peakCloses() {
        return peakCloses;
    }

    public Set<String> symbols() {
        return positions.keySet();
    }

    public Position position(String symbol) {
        return positions.get(symbol);
    }

    public void put(String symbol, Position position) {
        positions.put(symbol, position);
    }

    public Position remove(String symbol) {
        return positions.remove(symbol);
    }

    public void clearPositions() {
        positions.clear();
    }

    public void replaceQuantities(Map<String, Double> replacement) {
        LinkedHashMap<String, Position> updated = new LinkedHashMap<>();
        replacement.forEach((symbol, quantity) -> {
            Position position = positions.get(symbol);
            if (position == null) {
                position = new Position(quantity);
            } else {
                position.setQuantity(quantity);
            }
            updated.put(symbol, position);
        });
        positions.clear();
        positions.putAll(updated);
    }

    public double cash() {
        return cash;
    }

    public void setCash(double cash) {
        this.cash = cash;
    }

    public double deployedCapital() {
        return deployedCapital;
    }

    public void setDeployedCapital(double deployedCapital) {
        this.deployedCapital = deployedCapital;
    }

    public double accountEquity() {
        return accountEquity;
    }

    public void setAccountEquity(double accountEquity) {
        this.accountEquity = accountEquity;
    }

    public Portfolio copy() {
        return new Portfolio(this);
    }

    public int rebalanceNumber() {
        return rebalanceNumber;
    }

    public void setRebalanceNumber(int rebalanceNumber) {
        this.rebalanceNumber = rebalanceNumber;
    }

    public Ledger ledger() {
        return ledger;
    }

    private final class PositionValueMap extends AbstractMap<String, Double> {
        private final ToDoubleFunction<Position> read;
        private final ObjDoubleConsumer<Position> write;
        private final boolean includeNaN;

        PositionValueMap(ToDoubleFunction<Position> read, ObjDoubleConsumer<Position> write,
                         boolean includeNaN) {
            this.read = read;
            this.write = write;
            this.includeNaN = includeNaN;
        }

        @Override
        public Double get(Object key) {
            if (!(key instanceof String symbol)) {
                return null;
            }
            Position position = positions.get(symbol);
            if (position == null) {
                return null;
            }
            double value = read.applyAsDouble(position);
            return !includeNaN && Double.isNaN(value) ? null : value;
        }

        @Override
        public boolean containsKey(Object key) {
            if (!(key instanceof String symbol)) {
                return false;
            }
            Position position = positions.get(symbol);
            return position != null && (includeNaN || !Double.isNaN(read.applyAsDouble(position)));
        }

        @Override
        public Double put(String key, Double value) {
            Position position = positions.computeIfAbsent(key, ignored -> new Position(0.0));
            Double previous = get(key);
            write.accept(position, value);
            return previous;
        }

        @Override
        public Double remove(Object key) {
            if (!(key instanceof String symbol)) {
                return null;
            }
            if (includeNaN) {
                Position removed = positions.remove(symbol);
                return removed == null ? null : read.applyAsDouble(removed);
            }
            Position position = positions.get(symbol);
            if (position == null) {
                return null;
            }
            Double previous = get(symbol);
            write.accept(position, Double.NaN);
            return previous;
        }

        @Override
        public void clear() {
            if (includeNaN) {
                positions.clear();
            } else {
                positions.values().forEach(position -> write.accept(position, Double.NaN));
            }
        }

        @Override
        public Set<Entry<String, Double>> entrySet() {
            Set<Entry<String, Double>> entries = new LinkedHashSet<>();
            positions.forEach((symbol, position) -> {
                double value = read.applyAsDouble(position);
                if (includeNaN || !Double.isNaN(value)) {
                    entries.add(new SimpleImmutableEntry<>(symbol, value));
                }
            });
            return entries;
        }
    }
}