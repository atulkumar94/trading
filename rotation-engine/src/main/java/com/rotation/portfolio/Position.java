package com.rotation.portfolio;

import java.time.LocalDate;

/** Mutable execution state for one holding; reporting replays use independent copies. */
public final class Position {

    private final LocalDate entryDate;
    private double quantity;
    private double entryPrice;
    private double stopBasis;
    private double peakClose;
    private double cost;

    public Position(double quantity) {
        this(null, quantity, Double.NaN, Double.NaN, Double.NaN, 0.0);
    }

    public Position(double quantity, double entryPrice, double stopBasis, double peakClose) {
        this(null, quantity, entryPrice, stopBasis, peakClose, 0.0);
    }

    public Position(LocalDate entryDate, double entryPrice) {
        this(entryDate, 0.0, entryPrice, entryPrice, entryPrice, 0.0);
    }

    private Position(LocalDate entryDate, double quantity, double entryPrice,
                     double stopBasis, double peakClose, double cost) {
        this.entryDate = entryDate;
        this.quantity = quantity;
        this.entryPrice = entryPrice;
        this.stopBasis = stopBasis;
        this.peakClose = peakClose;
        this.cost = cost;
    }

    public LocalDate entryDate() {
        return entryDate;
    }

    public double quantity() {
        return quantity;
    }

    public void setQuantity(double quantity) {
        this.quantity = quantity;
    }

    public double entryPrice() {
        return entryPrice;
    }

    public void setEntryPrice(double entryPrice) {
        this.entryPrice = entryPrice;
    }

    public double stopBasis() {
        return stopBasis;
    }

    public void setStopBasis(double stopBasis) {
        this.stopBasis = stopBasis;
    }

    public double peakClose() {
        return peakClose;
    }

    public void setPeakClose(double peakClose) {
        this.peakClose = peakClose;
    }

    public double cost() {
        return cost;
    }

    public double averageCost() {
        return quantity > 0.0 ? cost / quantity : 0.0;
    }

    public void add(double addedQuantity, double price) {
        quantity += addedQuantity;
        cost += addedQuantity * price;
    }

    public void remove(double removedQuantity, double averageCost) {
        quantity -= removedQuantity;
        cost -= averageCost * removedQuantity;
    }

    public Position copy() {
        return new Position(entryDate, quantity, entryPrice, stopBasis, peakClose, cost);
    }
}