package com.rotation.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Backward-compatible facade over common pipeline and momentum strategy configuration. */
public final class RotationConfig {

    private static final Set<String> COMMON_KEYS = Set.of(
            "strategy", "data.path", "start.date", "end.date", "symbols.file", "sector.file",
            "market.sector", "market.dma.periods", "output.dir", "output.prefix", "data.validation.mode",
            "portal.enabled", "verbose");
    private static final Set<String> MOMENTUM_KEYS = Set.of(
            "momentum.lookback.days", "momentum.top.n", "momentum.exit.n",
            "momentum.capital.per.stock", "momentum.monthly.contribution",
            "momentum.stop.loss.pct", "momentum.trailing.stop.pct", "momentum.min.history.days",
            "momentum.allocation.mode", "momentum.rebalance.mode", "momentum.max.per.sector");
    private static final Map<String, String> LEGACY_KEYS = Map.ofEntries(
            Map.entry("lookback.days", "momentum.lookback.days"),
            Map.entry("top.n", "momentum.top.n"),
            Map.entry("exit.n", "momentum.exit.n"),
            Map.entry("capital.per.stock", "momentum.capital.per.stock"),
            Map.entry("monthly.contribution", "momentum.monthly.contribution"),
            Map.entry("stop.loss.pct", "momentum.stop.loss.pct"),
            Map.entry("trailing.stop.pct", "momentum.trailing.stop.pct"),
            Map.entry("min.history.days", "momentum.min.history.days"),
            Map.entry("allocation.mode", "momentum.allocation.mode"),
            Map.entry("rebalance.mode", "momentum.rebalance.mode"),
            Map.entry("max.per.sector", "momentum.max.per.sector"));

    private final CommonConfig common;
    private final MomentumConfig momentum;
    private final String strategy;
    private final Path sourceFile;

    private RotationConfig(Path projectRoot, Path sourceFile, Properties source) {
        this.sourceFile = sourceFile;
        Properties normalized = normalizeAndValidate(source);
        common = new CommonConfig(projectRoot, normalized);
        momentum = new MomentumConfig(normalized);
        strategy = value(normalized, "strategy", "momentum");
        validateCommon();
    }

    public static RotationConfig load(Path configFile, Path projectRoot) {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(configFile)) {
            properties.load(input);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to read config file: " + configFile, exception);
        }
        return new RotationConfig(projectRoot, configFile, properties);
    }

    private void validateCommon() {
        if (!strategy.equalsIgnoreCase("momentum")) {
            throw new IllegalArgumentException("Unknown strategy: '" + strategy + "'. Known strategies: momentum.");
        }
        if (common.marketSector() != null && common.sectorFile().isBlank()) {
            throw new IllegalArgumentException("market.sector requires sector.file to be set.");
        }
        if (common.startDate() != null && common.endDate() != null
                && common.endDate().isBefore(common.startDate())) {
            throw new IllegalArgumentException("end.date must be on or after start.date.");
        }
    }

    private static Properties normalizeAndValidate(Properties source) {
        Properties normalized = new Properties();
        normalized.putAll(source);
        List<String> legacyKeys = new ArrayList<>();
        for (String key : source.stringPropertyNames()) {
            String legacyTarget = LEGACY_KEYS.get(key);
            if (legacyTarget != null) {
                legacyKeys.add(key);
                if (!source.containsKey(legacyTarget)) {
                    normalized.setProperty(legacyTarget, source.getProperty(key));
                }
                continue;
            }
            if (!COMMON_KEYS.contains(key) && !MOMENTUM_KEYS.contains(key)) {
                throw unknownKey(key);
            }
        }
        for (String legacy : legacyKeys) {
            System.err.printf("Deprecated config key '%s'; use '%s'.%n", legacy, LEGACY_KEYS.get(legacy));
        }
        return normalized;
    }

    private static IllegalArgumentException unknownKey(String key) {
        Set<String> supported = new HashSet<>(COMMON_KEYS);
        supported.addAll(MOMENTUM_KEYS);
        supported.addAll(LEGACY_KEYS.keySet());
        String closest = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : supported) {
            int distance = editDistance(key, candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                closest = candidate;
            }
        }
        String suggestion = LEGACY_KEYS.getOrDefault(closest, closest);
        String suffix = bestDistance <= Math.max(2, key.length() / 3)
            ? " Did you mean '" + suggestion + "'?" : "";
        return new IllegalArgumentException("Unknown config key '" + key + "'." + suffix);
    }

    private static int editDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int substitution = previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1), substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    private static String value(Properties properties, String key, String fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : value.trim();
    }

    public CommonConfig common() { return common; }
    public MomentumConfig momentum() { return momentum; }
    public String strategy() { return strategy; }
    public Path sourceFile() { return sourceFile; }
    public Path projectRoot() { return common.projectRoot(); }
    public Path resolveDataDir() { return common.resolveDataDir(); }
    public Path resolveOutputDir() { return common.resolveOutputDir(); }
    public Path resolveSymbolsFile() { return common.resolveSymbolsFile(); }
    public Path resolveSectorFile() { return common.resolveSectorFile(); }
    public LocalDate startDate() { return common.startDate(); }
    public LocalDate endDate() { return common.endDate(); }
    public String marketSector() { return common.marketSector(); }
    public List<Integer> dmaPeriods() { return common.dmaPeriods(); }
    public int lookbackDays() { return momentum.lookbackDays(); }
    public int topN() { return momentum.topN(); }
    public int exitN() { return momentum.exitN(); }
    public int effectiveExitN() { return momentum.effectiveExitN(); }
    public double effectiveInitialCapital() { return momentum.effectiveInitialCapital(); }
    public int effectiveMinHistoryDays() { return momentum.effectiveMinHistoryDays(); }
    public double capitalPerStock() { return momentum.capitalPerStock(); }
    public double monthlyContribution() { return momentum.monthlyContribution(); }
    public double stopLossPct() { return momentum.stopLossPct(); }
    public double trailingStopPct() { return momentum.trailingStopPct(); }
    public String allocationMode() { return momentum.allocationMode(); }
    public String rebalanceMode() { return momentum.rebalanceMode(); }
    public int maxPerSector() { return common.marketSector() == null ? momentum.maxPerSector() : 0; }
    public String outputPrefix() { return common.outputPrefix(); }
    public String outputDir() { return common.outputDir(); }
    public String dataValidationMode() { return common.dataValidationMode(); }
    public boolean verbose() { return common.verbose(); }
    public boolean portalEnabled() { return common.portalEnabled(); }
}
