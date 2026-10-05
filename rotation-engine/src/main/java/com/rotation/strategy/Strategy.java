package com.rotation.strategy;

import java.util.List;

import com.rotation.market.MarketView;

/** Signal-only strategy contract. Portfolio mutation and execution remain outside this API. */
public interface Strategy {

    String name();

    int warmupSessions();

    void init(StrategyContext context);

    List<OrderIntent> onClose(MarketView market, PortfolioView portfolio);

    default List<Diagnostic> diagnostics() {
        return List.of();
    }

    default ExitPolicy exitPolicy() {
        return ExitPolicy.NONE;
    }
}
