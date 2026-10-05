package com.rotation.execution;

import java.util.List;

import com.rotation.model.EntryDetail;

/** Execution details needed by the existing rebalance log projection. */
public final class ExecutionResult {

    private final List<EntryDetail> entries;

    public ExecutionResult(List<EntryDetail> entries) {
        this.entries = List.copyOf(entries);
    }

    public List<EntryDetail> entries() {
        return entries;
    }
}