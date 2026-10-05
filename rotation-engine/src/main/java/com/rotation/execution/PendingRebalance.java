package com.rotation.execution;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.rotation.model.ExitDetail;
import com.rotation.strategy.Candidate;

/** Immutable execution inputs prepared from one strategy decision and account mark. */
public final class PendingRebalance {

    public final LocalDate signalDate;
    public final int executionIdx;
    public final int rebalanceNumber;
    public final List<String> entered;
    public final List<String> exited;
    public final List<String> held;
    public final Map<String, Double> priorHoldings;
    public final Map<String, Double> targetHoldings;
    public final List<ExitDetail> exits;
    public final List<Candidate> ranked;
    public final Map<String, Integer> rankBySymbol;
    public final Set<String> topNSelection;
    public final double targetCapital;
    public final double cashBeforeRebalance;
    public final double pendingContributionUsed;
    public final boolean cameFromCash;
    public final int topN;
    public final int exitN;

    public PendingRebalance(LocalDate signalDate, int executionIdx, int rebalanceNumber, List<String> entered,
                            List<String> exited, List<String> held, Map<String, Double> priorHoldings,
                            Map<String, Double> targetHoldings, List<ExitDetail> exits,
                            List<Candidate> ranked, Map<String, Integer> rankBySymbol,
                            Set<String> topNSelection, double targetCapital,
                            double cashBeforeRebalance, double pendingContributionUsed,
                            boolean cameFromCash, int topN, int exitN) {
        this.signalDate = signalDate;
        this.executionIdx = executionIdx;
        this.rebalanceNumber = rebalanceNumber;
        this.entered = List.copyOf(entered);
        this.exited = List.copyOf(exited);
        this.held = List.copyOf(held);
        this.priorHoldings = Map.copyOf(priorHoldings);
        this.targetHoldings = Map.copyOf(targetHoldings);
        this.exits = List.copyOf(exits);
        this.ranked = List.copyOf(ranked);
        this.rankBySymbol = Map.copyOf(rankBySymbol);
        this.topNSelection = Set.copyOf(topNSelection);
        this.targetCapital = targetCapital;
        this.cashBeforeRebalance = cashBeforeRebalance;
        this.pendingContributionUsed = pendingContributionUsed;
        this.cameFromCash = cameFromCash;
        this.topN = topN;
        this.exitN = exitN;
    }
}