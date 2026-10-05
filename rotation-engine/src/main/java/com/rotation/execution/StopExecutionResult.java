package com.rotation.execution;

/** Accounting effects from protective stop fills applied during a scan. */
public final class StopExecutionResult {

    public final double cash;
    public final double realizedPnl;
    public final double basisRemoved;

    public StopExecutionResult(double cash, double realizedPnl, double basisRemoved) {
        this.cash = cash;
        this.realizedPnl = realizedPnl;
        this.basisRemoved = basisRemoved;
    }
}