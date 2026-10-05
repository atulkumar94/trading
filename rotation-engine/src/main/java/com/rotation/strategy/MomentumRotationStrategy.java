package com.rotation.strategy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.rotation.config.RotationConfig;
import com.rotation.market.MarketView;

/**
 * The default strategy: fixed top-N momentum rotation with an optional sector cap
 * and exit buffer. Each signal the eligible universe is ranked by trailing lookback
 * return; the sector-capped top-N is always entered and, when {@code exit.n > top.n},
 * currently-held names are retained until they fall out of the top {@code exit.n}.
 *
 * <p>This class is the exact decision logic that previously lived inside the engine;
 * it is unchanged in behaviour and now emits signal-only order intents.
 */
public final class MomentumRotationStrategy implements Strategy {

    static final String MODE_MONTHLY = "monthly";
    static final String MODE_WEEKLY = "weekly";
    static final String MODE_MONTHLY_TWICE = "monthly_twice";

    private final int topN;
    private final int exitN;
    private final int maxPerSector;
    private final int lookbackDays;
    private final String rebalanceMode;
    private final double stopLossPct;
    private final double trailingStopPct;
    private final Map<String, String> sectorBySymbol;
    private final List<Diagnostic> diagnosticRows = new ArrayList<>();
    private StrategyContext context;
    private int nextSignalIndex = -1;

    public MomentumRotationStrategy(RotationConfig config) {
        this.topN = config.topN();
        this.exitN = config.effectiveExitN();
        this.maxPerSector = config.maxPerSector();
        this.lookbackDays = config.lookbackDays();
        this.rebalanceMode = config.rebalanceMode();
        this.stopLossPct = config.stopLossPct();
        this.trailingStopPct = config.trailingStopPct();
        this.sectorBySymbol = maxPerSector > 0
                ? loadSectorMap(config.resolveSectorFile())
                : Collections.emptyMap();
    }

    @Override
    public String name() {
        return "momentum";
    }

    /** Trading sessions between consecutive rebalances for a rebalance.mode. */
    public static int rebalanceIntervalSessions(String mode) {
        switch (mode) {
            case MODE_WEEKLY:
                return 5;
            case MODE_MONTHLY_TWICE:
                return 10;
            case MODE_MONTHLY:
                return 20;
            default:
                throw new IllegalArgumentException("Unknown rebalance.mode: " + mode);
        }
    }

    @Override
    public int warmupSessions() {
        return lookbackDays;
    }

    @Override
    public ExitPolicy exitPolicy() {
        return new StopLossExitPolicy(stopLossPct, trailingStopPct);
    }

    /** Rank all eligible symbols by trailing lookback return (desc), ties by symbol (asc). */
    public List<Candidate> rank(MarketView market, int minHistory) {
        List<Candidate> candidates = new ArrayList<>();
        List<String> symbols = market.symbols();
        for (String symbol : symbols) {
            double current = market.close(symbol, 0);
            double lookback = market.close(symbol, lookbackDays - 1);
            int history = market.eligibility(symbol);
            boolean eligible = !Double.isNaN(current) && !Double.isNaN(lookback)
                    && lookback > 0 && history >= minHistory;
            if (!eligible) {
                continue;
            }
            double returnPct = ((current / lookback) - 1.0) * 100.0;
            candidates.add(new Candidate(symbol, returnPct, lookback, current, history));
        }
        candidates.sort(Comparator
                .comparingDouble((Candidate c) -> c.score).reversed()
                .thenComparing(c -> c.symbol));
        return candidates;
    }

    @Override
    public void init(StrategyContext context) {
        this.context = context;
        this.nextSignalIndex = -1;
        diagnosticRows.clear();
    }

    @Override
    public List<Diagnostic> diagnostics() {
        return Collections.unmodifiableList(diagnosticRows);
    }

    @Override
    public List<OrderIntent> onClose(MarketView market, PortfolioView portfolio) {
        if (context == null) {
            throw new IllegalStateException("Momentum strategy must be initialized before onClose");
        }
        int signalIndex = market.sessionIndex();
        List<Candidate> ranked = rank(market, context.minHistory);
        boolean rebalance = false;
        if (signalIndex >= context.firstSignalIndex && signalIndex <= context.lastSignalIndex) {
            if (nextSignalIndex < 0) {
                if (!ranked.isEmpty()) {
                    rebalance = true;
                    nextSignalIndex = signalIndex + rebalanceIntervalSessions(rebalanceMode);
                }
            } else if (signalIndex >= nextSignalIndex) {
                rebalance = true;
                nextSignalIndex += rebalanceIntervalSessions(rebalanceMode);
            }
        }

        List<OrderIntent> intents = List.of();
        if (rebalance) {
            intents = createIntents(market, portfolio, ranked);
            Set<String> selected = selectedSymbols(intents);
            Set<String> core = new HashSet<>(selectCore(ranked));
            int rank = 1;
            for (Candidate candidate : ranked) {
                diagnosticRows.add(new Diagnostic(Diagnostic.Kind.REBALANCE, market.asOfDate(),
                    market.date(lookbackDays - 1),
                        signalIndex, rank++, candidate.symbol, candidate.referencePrice,
                        candidate.currentPrice, candidate.score, candidate.historyDays,
                        selected.contains(candidate.symbol), core.contains(candidate.symbol), true));
            }
            if (ranked.isEmpty()) {
                diagnosticRows.add(new Diagnostic(Diagnostic.Kind.REBALANCE, market.asOfDate(),
                    market.date(lookbackDays - 1),
                        signalIndex, 0, "", Double.NaN, Double.NaN, Double.NaN, 0,
                        false, false, true));
            }
        }

        int lookbackStart = Math.max(lookbackDays - 1, context.lastSignalIndex - 28);
        if (signalIndex >= lookbackStart) {
            int rank = 1;
            for (Candidate candidate : ranked) {
                boolean selected = rank <= topN;
                diagnosticRows.add(new Diagnostic(Diagnostic.Kind.LOOKBACK, market.asOfDate(),
                    market.date(lookbackDays - 1),
                        signalIndex, rank++, candidate.symbol, candidate.referencePrice,
                        candidate.currentPrice, candidate.score, candidate.historyDays,
                        selected, selected, false));
            }
        }
        return intents;
    }

    private List<OrderIntent> createIntents(MarketView market, PortfolioView portfolio,
                                             List<Candidate> ranked) {
        List<String> selected = select(ranked, portfolio.symbols());
        Set<String> selectedSet = new HashSet<>(selected);
        Map<String, Integer> ranks = new HashMap<>();
        for (int i = 0; i < ranked.size(); i++) {
            ranks.put(ranked.get(i).symbol, i + 1);
        }
        Set<String> core = new HashSet<>(selectCore(ranked));
        List<OrderIntent> intents = new ArrayList<>();
        for (String symbol : selected) {
            int rank = ranks.getOrDefault(symbol, Integer.MAX_VALUE);
            boolean wasHeld = portfolio.symbols().contains(symbol);
            String reason = wasHeld
                    ? "Re-sized to target allocation (rank " + rank + " of " + ranked.size()
                            + (core.contains(symbol) ? ", in top.n selection)" : ", retained in exit buffer)")
                    : "Entered: rank " + rank + " of " + ranked.size() + " (top.n=" + topN
                            + (rank > topN ? ", higher-ranked names skipped by sector cap)" : ")");
            intents.add(new OrderIntent(symbol,
                    wasHeld ? OrderIntent.Kind.TARGET_ALLOCATION : OrderIntent.Kind.ENTER,
                    1.0 / exitN, Double.NaN, reason, rank, market.sessionIndex()));
        }

        TreeSet<String> previous = new TreeSet<>(portfolio.symbols());
        for (String symbol : previous) {
            if (!selectedSet.contains(symbol)) {
                Integer rank = ranks.get(symbol);
                intents.add(new OrderIntent(symbol, OrderIntent.Kind.EXIT, 0.0, Double.NaN,
                        exitReason(rank, ranked.size()), rank == null ? Integer.MAX_VALUE : rank,
                        market.sessionIndex()));
            }
        }
        return intents;
    }

    private static Set<String> selectedSymbols(List<OrderIntent> intents) {
        Set<String> selected = new HashSet<>();
        for (OrderIntent intent : intents) {
            if (intent.kind == OrderIntent.Kind.ENTER
                    || intent.kind == OrderIntent.Kind.TARGET_ALLOCATION) {
                selected.add(intent.symbol);
            }
        }
        return selected;
    }

    private String exitReason(Integer rank, int rankedCount) {
        if (rank == null) {
            return "Ineligible at signal (missing close or insufficient history)";
        }
        if (rank > exitN) {
            return "Rank " + rank + " of " + rankedCount + ", below exit rank " + exitN;
        }
        return "Rank " + rank + " of " + rankedCount + ", within exit rank " + exitN
                + " but not reselected (sector cap or book capacity)";
    }

    /**
     * Pick the top {@code topN} symbols from the ranked list, optionally capping how many
     * may come from a single sector. The cap keeps two highly-correlated same-theme names
     * out of a concentrated book: it takes the highest-ranked eligible symbol per sector
     * first and, only if that starves us of names, backfills with the next best regardless
     * of sector so a slot is never left empty. Symbols with no sector mapping are never
     * capped. When the cap is disabled (or no map is loaded) this is a plain top-N cut.
     */
    public List<String> selectCore(List<Candidate> ranked) {
        if (maxPerSector <= 0 || sectorBySymbol.isEmpty()) {
            List<String> plain = new ArrayList<>();
            for (int i = 0; i < Math.min(topN, ranked.size()); i++) {
                plain.add(ranked.get(i).symbol);
            }
            return plain;
        }
        List<String> selected = new ArrayList<>();
        Map<String, Integer> perSector = new HashMap<>();
        for (Candidate c : ranked) {
            if (selected.size() >= topN) {
                break;
            }
            String sector = sectorBySymbol.get(c.symbol);
            if (sector != null && perSector.getOrDefault(sector, 0) >= maxPerSector) {
                continue;
            }
            selected.add(c.symbol);
            if (sector != null) {
                perSector.merge(sector, 1, Integer::sum);
            }
        }
        if (selected.size() < topN) {
            for (Candidate c : ranked) {
                if (selected.size() >= topN) {
                    break;
                }
                if (!selected.contains(c.symbol)) {
                    selected.add(c.symbol);
                }
            }
        }
        return selected;
    }

    /**
     * Build the book for a rebalance. The top-N names (sector-capped) are always entered.
     * When {@code exitN > topN} the book additionally retains any currently-held name that
     * still ranks within the top {@code exitN} by momentum, so a holding is only dropped once
     * it falls out of the top {@code exitN} (never merely because it left the top-N). The book
     * is capped at {@code exitN} names and the sector cap is honoured when retaining buffers,
     * so two same-sector names never coexist. When {@code exitN == topN} this reduces to a
     * plain sector-capped top-N cut (legacy behaviour).
     */
    public List<String> select(List<Candidate> ranked, Set<String> currentHoldings) {
        List<String> selected = selectCore(ranked);
        if (exitN <= topN) {
            return selected;
        }
        selected = new ArrayList<>(selected);
        Map<String, Integer> perSector = new HashMap<>();
        for (String symbol : selected) {
            String sector = sectorBySymbol.get(symbol);
            if (sector != null) {
                perSector.merge(sector, 1, Integer::sum);
            }
        }
        int limit = Math.min(exitN, ranked.size());
        for (int i = 0; i < limit && selected.size() < exitN; i++) {
            Candidate c = ranked.get(i);
            if (!currentHoldings.contains(c.symbol) || selected.contains(c.symbol)) {
                continue; // only previously-held names may fill the buffer beyond the top-N
            }
            String sector = sectorBySymbol.get(c.symbol);
            if (maxPerSector > 0 && sector != null && perSector.getOrDefault(sector, 0) >= maxPerSector) {
                continue;
            }
            selected.add(c.symbol);
            if (sector != null) {
                perSector.merge(sector, 1, Integer::sum);
            }
        }
        return selected;
    }

    /** Load a {@code symbol -> sector} map from a CSV with 'symbol' and 'sector' columns. */
    private static Map<String, String> loadSectorMap(Path file) {
        Map<String, String> map = new HashMap<>();
        if (file == null || !Files.exists(file)) {
            return map;
        }
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String header = reader.readLine();
            if (header == null) {
                return map;
            }
            String[] cols = header.split(",");
            int symCol = -1;
            int secCol = -1;
            for (int i = 0; i < cols.length; i++) {
                String h = cols[i].trim();
                if (h.equalsIgnoreCase("symbol")) {
                    symCol = i;
                } else if (h.equalsIgnoreCase("sector")) {
                    secCol = i;
                }
            }
            if (symCol < 0 || secCol < 0) {
                throw new IllegalArgumentException(
                        "Sector file must include 'symbol' and 'sector' columns: " + file);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",", -1);
                if (parts.length > Math.max(symCol, secCol)) {
                    String symbol = parts[symCol].trim();
                    String sector = parts[secCol].trim();
                    if (!symbol.isEmpty() && !sector.isEmpty()) {
                        map.put(symbol, sector);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read sector file: " + file, e);
        }
        return map;
    }
}
