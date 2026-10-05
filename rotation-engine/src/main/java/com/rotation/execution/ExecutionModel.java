package com.rotation.execution;

import java.time.LocalDate;

import com.rotation.model.DailyBars;
import com.rotation.portfolio.Portfolio;

/** Owns session-open fills and close-triggered protective stop fills. */
public interface ExecutionModel {

    ExecutionResult executeAtOpen(LocalDate session, PendingRebalance pending,
                                  Portfolio portfolio, DailyBars market);

    StopExecutionResult checkProtectiveStops(LocalDate session, Portfolio portfolio, DailyBars market);
}