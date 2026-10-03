package com.rotation.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Properties;

/**
 * Strategy configuration, loaded from a {@code .properties} file. Every tunable
 * parameter lives here so behaviour can be changed without touching code.
 */
public final class RotationConfig {

    private final Path projectRoot;
    private final int lookbackDays;
    private final int topN;
    private final int exitN;
    private final double capitalPerStock;
    private final double monthlyContribution;
    private final double stopLossPct;
    private final double trailingStopPct;
    private final int minHistoryDays;
    private final String allocationMode;
    private final String rebalanceMode;
    private final String strategy;
    private final String dataPath;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final String symbolsFile;
    private final String sectorFile;
    private final int maxPerSector;
    private final String marketSector;
    private final String outputDir;
    private final String outputPrefix;
    private final boolean verbose;
    private final boolean portalEnabled;
    private final Path sourceFile;

    private RotationConfig(Path projectRoot, Path sourceFile, Properties props) {
        this.projectRoot = projectRoot;
        this.sourceFile = sourceFile;
        this.lookbackDays = intProp(props, "lookback.days", 30);
        this.topN = intProp(props, "top.n", 1);
        this.exitN = intProp(props, "exit.n", 0);
        this.capitalPerStock = doubleProp(props, "capital.per.stock", 100000.0);
        this.monthlyContribution = doubleProp(props, "monthly.contribution", 0.0);
        this.stopLossPct = doubleProp(props, "stop.loss.pct", 0.0);
        this.trailingStopPct = doubleProp(props, "trailing.stop.pct", 0.0);
        this.minHistoryDays = intProp(props, "min.history.days", 2);
        this.allocationMode = stringProp(props, "allocation.mode", "compound");
        this.rebalanceMode = stringProp(props, "rebalance.mode", "monthly_twice");
        this.strategy = stringProp(props, "strategy", "momentum");
        this.dataPath = stringProp(props, "data.path", "");
        this.startDate = localDateProp(props, "start.date", null);
        this.endDate = localDateProp(props, "end.date", null);
        this.symbolsFile = stringProp(props, "symbols.file", "");
        this.sectorFile = stringProp(props, "sector.file", "");
        this.maxPerSector = intProp(props, "max.per.sector", 0);
        this.marketSector = stringProp(props, "market.sector", "");
        this.outputDir = stringProp(props, "output.dir", "output/rotation");
        this.outputPrefix = stringProp(props, "output.prefix", "rotation");
        this.verbose = booleanProp(props, "verbose", true);
        this.portalEnabled = booleanProp(props, "portal.enabled", true);
        validate();
    }

    public static RotationConfig load(Path configFile, Path projectRoot) {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(configFile)) {
            props.load(in);
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to read config file: " + configFile, e);
        }
        return new RotationConfig(projectRoot, configFile, props);
    }

    private void validate() {
        if (topN <= 0) {
            throw new IllegalArgumentException("top.n must be greater than zero.");
        }
        if (exitN != 0 && exitN < topN) {
            throw new IllegalArgumentException("exit.n must be zero (disabled) or greater than or equal to top.n.");
        }
        if (lookbackDays <= 0) {
            throw new IllegalArgumentException("lookback.days must be greater than zero.");
        }
        if (capitalPerStock <= 0) {
            throw new IllegalArgumentException("capital.per.stock must be greater than zero.");
        }
        if (monthlyContribution < 0) {
            throw new IllegalArgumentException("monthly.contribution must be zero or positive.");
        }
        if (stopLossPct < 0 || stopLossPct >= 100) {
            throw new IllegalArgumentException("stop.loss.pct must be in [0, 100).");
        }
        if (trailingStopPct < 0 || trailingStopPct >= 100) {
            throw new IllegalArgumentException("trailing.stop.pct must be in [0, 100).");
        }
        if (maxPerSector < 0) {
            throw new IllegalArgumentException("max.per.sector must be zero or positive.");
        }
        if (!marketSector.isBlank() && sectorFile.isBlank()) {
            throw new IllegalArgumentException("market.sector requires sector.file to be set.");
        }
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("end.date must be on or after start.date.");
        }
        if (!allocationMode.equals("compound") && !allocationMode.equals("fixed_principal")) {
            throw new IllegalArgumentException("allocation.mode must be 'compound' or 'fixed_principal'.");
        }
        if (!rebalanceMode.equals("monthly") && !rebalanceMode.equals("weekly")
                && !rebalanceMode.equals("monthly_twice")) {
            throw new IllegalArgumentException(
                    "rebalance.mode must be 'monthly', 'weekly', or 'monthly_twice'.");
        }
    }

    /** Resolve the input data directory from the configured data.path. */
    public Path resolveDataDir() {
        if (dataPath != null && !dataPath.isBlank()) {
            return absolute(Path.of(dataPath));
        }
        return projectRoot.resolve("data");
    }

    public Path resolveOutputDir() {
        return absolute(Path.of(outputDir));
    }

    /** Resolve the optional symbols file; returns null when not configured. */
    public LocalDate startDate() {
        return startDate;
    }

    /** Optional inclusive end date; data after this date is ignored. Returns null when not configured. */
    public LocalDate endDate() {
        return endDate;
    }

    public Path resolveSymbolsFile() {
        if (symbolsFile == null || symbolsFile.isBlank()) {
            return null;
        }
        return absolute(Path.of(symbolsFile));
    }

    /** Resolve the optional sector-map CSV (symbol,sector); returns null when not configured. */
    public Path resolveSectorFile() {
        if (sectorFile == null || sectorFile.isBlank()) {
            return null;
        }
        return absolute(Path.of(sectorFile));
    }

    /**
     * Maximum holdings allowed from a single sector each rebalance (0 disables the cap).
     * Always 0 when market.sector is set: every name shares one sector, so the cap would
     * only block exit.n buffer retention.
     */
    public int maxPerSector() {
        return marketSector() != null ? 0 : maxPerSector;
    }

    /** Sector the universe is restricted to (matched case-insensitively); null when not configured. */
    public String marketSector() {
        return marketSector.isBlank() ? null : marketSector;
    }

    private Path absolute(Path candidate) {
        return candidate.isAbsolute() ? candidate : projectRoot.resolve(candidate);
    }

    /**
     * Starting capital: each of the effective book slots (exit.n when set, else top.n)
     * is funded with capital.per.stock. The book can grow up to exit.n holdings, so the
     * baseline reserves capital for the full capacity even though only top.n names are
     * entered on the first deployment.
     */
    public double effectiveInitialCapital() {
        return effectiveExitN() * capitalPerStock;
    }

    /** A symbol is never entered before it has this many tracked daily bars. */
    public int effectiveMinHistoryDays() {
        return Math.max(minHistoryDays, lookbackDays);
    }

    public int lookbackDays() {
        return lookbackDays;
    }

    public int topN() {
        return topN;
    }

    /** Raw exit.n as configured (0 = disabled, i.e. exit when a holding leaves the top.n). */
    public int exitN() {
        return exitN;
    }

    /**
     * Effective exit rank / maximum book size. When exit.n is unset (0) this is top.n, so a
     * holding is dropped as soon as it falls out of the top.n. When exit.n is set, holdings
     * are retained until they fall out of the top exit.n and the book may hold up to exit.n
     * names (top.n are always entered; older names linger in the buffer between them).
     */
    public int effectiveExitN() {
        return exitN <= 0 ? topN : exitN;
    }

    public double capitalPerStock() {
        return capitalPerStock;
    }

    public double monthlyContribution() {
        return monthlyContribution;
    }

    /** Hard stop: exit a holding intra-period if it falls this % below its period entry price (0 disables). */
    public double stopLossPct() {
        return stopLossPct;
    }

    /** Trailing stop: exit a holding if it falls this % below its peak since entry (0 disables). */
    public double trailingStopPct() {
        return trailingStopPct;
    }

    public String allocationMode() {
        return allocationMode;
    }

    public String rebalanceMode() {
        return rebalanceMode;
    }

    /** Id of the pluggable strategy to run (default 'momentum'); resolved by RotationStrategies. */
    public String strategy() {
        return strategy;
    }

    public String outputPrefix() {
        return outputPrefix;
    }

    public boolean verbose() {
        return verbose;
    }

    /** Write the self-contained HTML reporting portal alongside the CSV reports. */
    public boolean portalEnabled() {
        return portalEnabled;
    }

    /** Config file this configuration was loaded from (recorded in the run manifest). */
    public Path sourceFile() {
        return sourceFile;
    }

    /** Directory relative config paths resolve from (the working directory). */
    public Path projectRoot() {
        return projectRoot;
    }

    private static String stringProp(Properties props, String key, String def) {
        String value = props.getProperty(key);
        return value == null ? def : value.trim();
    }

    private static int intProp(Properties props, String key, int def) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            return def;
        }
        return Integer.parseInt(value.trim());
    }

    private static double doubleProp(Properties props, String key, double def) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            return def;
        }
        return Double.parseDouble(value.trim());
    }

    private static boolean booleanProp(Properties props, String key, boolean def) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            return def;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private static LocalDate localDateProp(Properties props, String key, LocalDate def) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            return def;
        }
        return LocalDate.parse(value.trim());
    }
}
