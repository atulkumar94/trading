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
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.rotation.config.RotationConfig;
import com.rotation.market.MarketView;

/**
 * The default strategy: fixed top-N momentum rotation with an optional sector cap
 * and exit buffer. Each signal the eligible universe is ranked by trailing lookback
 * return; the sector-capped top-N is always entered and, when {@code exit.n > top.n},
 * currently-held names are retained until they fall out of the top {@code exit.n}.
 *
 * <p>This class is the exact decision logic that previously lived inside the engine;
 * it is unchanged in behaviour and simply relocated behind {@link RotationStrategy}.
 */
public final class MomentumRotationStrategy implements RotationStrategy {

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
    public String scheduleLabel() {
        return rebalanceMode + "@every-" + rebalanceIntervalSessions(rebalanceMode) + "-sessions";
    }

    /**
     * Signal indices (execution is at {@code signalIdx + 1}). The first signal is the
     * earliest session, no earlier than the one before the start date, whose lookback
     * window is complete and has at least one eligible symbol. Each later signal is
     * {@link #rebalanceIntervalSessions} trading sessions after the previous one.
     */
    @Override
    public int warmupSessions() {
        return lookbackDays;
    }

    @Override
    public List<Integer> rebalanceSignals(int sessionCount, int firstEligibleSignalIdx) {
        int interval = rebalanceIntervalSessions(rebalanceMode);
        int lastSignal = sessionCount - 2;
        List<Integer> signalIndices = new ArrayList<>();
        for (int signal = firstEligibleSignalIdx; signal <= lastSignal; signal += interval) {
            signalIndices.add(signal);
        }
        return signalIndices;
    }

    @Override
    public ExitPolicy exitPolicy() {
        return new StopLossExitPolicy(stopLossPct, trailingStopPct);
    }

    /** Rank all eligible symbols by trailing lookback return (desc), ties by symbol (asc). */
    @Override
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

    /**
     * Pick the top {@code topN} symbols from the ranked list, optionally capping how many
     * may come from a single sector. The cap keeps two highly-correlated same-theme names
     * out of a concentrated book: it takes the highest-ranked eligible symbol per sector
     * first and, only if that starves us of names, backfills with the next best regardless
     * of sector so a slot is never left empty. Symbols with no sector mapping are never
     * capped. When the cap is disabled (or no map is loaded) this is a plain top-N cut.
     */
    @Override
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
    @Override
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
