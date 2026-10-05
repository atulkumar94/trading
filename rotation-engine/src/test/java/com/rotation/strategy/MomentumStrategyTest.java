package com.rotation.strategy;

import com.rotation.config.RotationConfig;
import com.rotation.market.MarketData;
import com.rotation.market.MarketView;
import com.rotation.model.DailyBars;
import com.rotation.model.DailyCandle;
import com.rotation.model.SymbolDailyCandles;
import com.rotation.portfolio.Portfolio;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MomentumStrategyTest {

    @Test
    void emitsEntryAndReweightIntentsOnCloseWithoutMutatingPortfolio() throws IOException {
        DailyBars bars = bars();
        MarketData market = new MarketData(bars);
        RotationConfig config = config();
        MomentumRotationStrategy strategy = new MomentumRotationStrategy(config);
        strategy.init(new StrategyContext(0, 2, bars.dateCount() - 2, 3));
        Portfolio empty = new Portfolio(10000.0);

        List<OrderIntent> first = List.of();
        for (int session = 0; session <= 2; session++) {
            first = strategy.onClose(market.asOf(session), new PortfolioView(empty));
        }

        assertEquals(1, first.size());
        assertEquals(OrderIntent.Kind.ENTER, first.get(0).kind);
        assertEquals("AAA", first.get(0).symbol);
        assertEquals(2, first.get(0).signalIndex);
        assertTrue(strategy.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.kind == Diagnostic.Kind.REBALANCE && diagnostic.selected));
        assertTrue(empty.quantities().isEmpty(), "strategy must not mutate account positions");

        Portfolio held = new Portfolio(10000.0);
        held.quantities().put("AAA", 100.0);
        List<OrderIntent> next = List.of();
        for (int session = 3; session <= 22; session++) {
            next = strategy.onClose(market.asOf(session), new PortfolioView(held));
        }
        assertEquals(1, next.size());
        assertEquals(OrderIntent.Kind.TARGET_ALLOCATION, next.get(0).kind);
        assertEquals("AAA", next.get(0).symbol);
        assertFalse(held.quantities().isEmpty(), "strategy receives a view and cannot clear holdings");
    }

    private static RotationConfig config() throws IOException {
        Path dir = Files.createTempDirectory("momentum-strategy-test");
        Path file = dir.resolve("rotation.properties");
        Files.writeString(file, "lookback.days=3\n"
                + "top.n=1\n"
                + "min.history.days=1\n"
                + "rebalance.mode=monthly\n"
                + "capital.per.stock=10000\n"
                + "verbose=false\n");
        return RotationConfig.load(file, dir);
    }

    private static DailyBars bars() {
        List<DailyCandle> rising = new ArrayList<>();
        List<DailyCandle> falling = new ArrayList<>();
        LocalDate date = LocalDate.of(2024, 1, 2);
        for (int i = 0; i < 24; i++) {
            double up = 100.0 + i;
            double down = 200.0 - i;
            rising.add(new DailyCandle(date.plusDays(i), up, up, up, up, 1.0));
            falling.add(new DailyCandle(date.plusDays(i), down, down, down, down, 1.0));
        }
        return DailyBars.build(List.of(new SymbolDailyCandles("AAA", rising),
                new SymbolDailyCandles("BBB", falling)), false);
    }
}