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
    private final double capitalPerStock;
    private final double monthlyContribution;
    private final int minHistoryDays;
    private final String allocationMode;
    private final String rebalanceMode;
    private final String dataPath;
    private final LocalDate startDate;
    private final String symbolsFile;
    private final String outputDir;
    private final String outputPrefix;
    private final boolean verbose;

    private RotationConfig(Path projectRoot, Properties props) {
        this.projectRoot = projectRoot;
        this.lookbackDays = intProp(props, "lookback.days", 30);
        this.topN = intProp(props, "top.n", 1);
        this.capitalPerStock = doubleProp(props, "capital.per.stock", 100000.0);
        this.monthlyContribution = doubleProp(props, "monthly.contribution", 0.0);
        this.minHistoryDays = intProp(props, "min.history.days", 2);
        this.allocationMode = stringProp(props, "allocation.mode", "compound");
        this.rebalanceMode = stringProp(props, "rebalance.mode", "monthly_twice");
        this.dataPath = stringProp(props, "data.path", "");
        this.startDate = localDateProp(props, "start.date", null);
        this.symbolsFile = stringProp(props, "symbols.file", "");
        this.outputDir = stringProp(props, "output.dir", "output/rotation");
        this.outputPrefix = stringProp(props, "output.prefix", "rotation");
        this.verbose = booleanProp(props, "verbose", true);
        validate();
    }

    public static RotationConfig load(Path configFile, Path projectRoot) {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(configFile)) {
            props.load(in);
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to read config file: " + configFile, e);
        }
        return new RotationConfig(projectRoot, props);
    }

    private void validate() {
        if (topN <= 0) {
            throw new IllegalArgumentException("top.n must be greater than zero.");
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

    public Path resolveSymbolsFile() {
        if (symbolsFile == null || symbolsFile.isBlank()) {
            return null;
        }
        return absolute(Path.of(symbolsFile));
    }

    private Path absolute(Path candidate) {
        return candidate.isAbsolute() ? candidate : projectRoot.resolve(candidate);
    }

    /** Starting capital: each of the top.n slots is funded with capital.per.stock. */
    public double effectiveInitialCapital() {
        return topN * capitalPerStock;
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

    public double capitalPerStock() {
        return capitalPerStock;
    }

    public double monthlyContribution() {
        return monthlyContribution;
    }

    public String allocationMode() {
        return allocationMode;
    }

    public String rebalanceMode() {
        return rebalanceMode;
    }

    public String outputPrefix() {
        return outputPrefix;
    }

    public boolean verbose() {
        return verbose;
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
