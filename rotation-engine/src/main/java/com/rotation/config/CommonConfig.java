package com.rotation.config;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Configuration shared by market ingestion, reporting, and every strategy. */
public final class CommonConfig {

    private final Path projectRoot;
    private final String dataPath;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final String symbolsFile;
    private final String sectorFile;
    private final String marketSector;
    private final String outputDir;
    private final String outputPrefix;
    private final String dataValidationMode;
    private final boolean verbose;
    private final boolean portalEnabled;
    private final List<Integer> dmaPeriods;

    CommonConfig(Path projectRoot, Properties properties) {
        this.projectRoot = projectRoot;
        dataPath = value(properties, "data.path", "");
        startDate = date(properties, "start.date");
        endDate = date(properties, "end.date");
        symbolsFile = value(properties, "symbols.file", "");
        sectorFile = value(properties, "sector.file", "");
        marketSector = value(properties, "market.sector", "");
        outputDir = value(properties, "output.dir", "output/rotation");
        outputPrefix = value(properties, "output.prefix", "rotation");
        dataValidationMode = value(properties, "data.validation.mode", "warn").toLowerCase(java.util.Locale.ROOT);
        verbose = bool(properties, "verbose", true);
        portalEnabled = bool(properties, "portal.enabled", true);
        dmaPeriods = periods(properties, "market.dma.periods", "10,20,50,100,200");
        if (!dataValidationMode.equals("warn") && !dataValidationMode.equals("fail")) {
            throw new IllegalArgumentException("data.validation.mode must be 'warn' or 'fail'.");
        }
    }

    public Path projectRoot() { return projectRoot; }
    public String dataPath() { return dataPath; }
    public LocalDate startDate() { return startDate; }
    public LocalDate endDate() { return endDate; }
    public String symbolsFile() { return symbolsFile; }
    public String sectorFile() { return sectorFile; }
    public String marketSector() { return marketSector.isBlank() ? null : marketSector; }
    public String outputDir() { return outputDir; }
    public String outputPrefix() { return outputPrefix; }
    public String dataValidationMode() { return dataValidationMode; }
    public boolean verbose() { return verbose; }
    public boolean portalEnabled() { return portalEnabled; }

    /** Moving-average periods stored with the daily prices; empty when disabled. */
    public List<Integer> dmaPeriods() { return dmaPeriods; }

    public Path resolveDataDir() {
        return dataPath.isBlank() ? projectRoot.resolve("data") : absolute(Path.of(dataPath));
    }

    public Path resolveOutputDir() {
        return absolute(Path.of(outputDir));
    }

    public Path resolveSymbolsFile() {
        return symbolsFile.isBlank() ? null : absolute(Path.of(symbolsFile));
    }

    public Path resolveSectorFile() {
        return sectorFile.isBlank() ? null : absolute(Path.of(sectorFile));
    }

    private Path absolute(Path path) {
        return path.isAbsolute() ? path : projectRoot.resolve(path);
    }

    private static String value(Properties properties, String key, String fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : value.trim();
    }

    private static LocalDate date(Properties properties, String key) {
        String value = value(properties, key, "");
        return value.isBlank() ? null : LocalDate.parse(value);
    }

    private static List<Integer> periods(Properties properties, String key, String fallback) {
        List<Integer> periods = new ArrayList<>();
        String value = value(properties, key, fallback);
        if (value.isBlank()) {
            return periods;
        }
        for (String part : value.split(",")) {
            int period;
            try {
                period = Integer.parseInt(part.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(key + " must be comma-separated positive integers: " + value);
            }
            if (period <= 0 || periods.contains(period)) {
                throw new IllegalArgumentException(key + " must be distinct positive integers: " + value);
            }
            periods.add(period);
        }
        return List.copyOf(periods);
    }

    private static boolean bool(Properties properties, String key, boolean fallback) {
        String value = value(properties, key, "");
        return value.isBlank() ? fallback : Boolean.parseBoolean(value);
    }
}
