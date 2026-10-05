package com.rotation.data;

import com.rotation.model.DailyCandle;
import com.rotation.model.SymbolDailyCandles;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads {@code DailyBars} input back from a previously written daily market
 * snapshot CSV (header
 * {@code date,symbol,prev_close,open,high,low,close,return_vs_prev_close}).
 *
 * <p>This makes the daily market snapshot the single source of truth: the raw
 * data is parsed once to build the snapshot, and every downstream consumer
 * (monthly snapshot, rotation engine) reads the exact same materialized data
 * from here instead of re-deriving it.
 */
public final class SnapshotDailyBarLoader {

    private SnapshotDailyBarLoader() {
    }

    public static List<SymbolDailyCandles> load(Path snapshotFile) {
        Map<String, List<DailyCandle>> bySymbol = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(snapshotFile)) {
            String header = reader.readLine();
            if (header == null) {
                throw new IllegalArgumentException("Daily market snapshot is empty: " + snapshotFile);
            }
            Map<String, Integer> columns = columns(header);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split(",", -1);
                if (parts.length < 7) {
                    continue;
                }
                try {
                    LocalDate date = LocalDate.parse(parts[column(columns, "date", 0)].trim());
                    String symbol = parts[column(columns, "symbol", 1)].trim();
                    boolean validBar = optionalBoolean(parts, columns, "valid_bar", true);
                    double open = optionalDouble(parts, columns, "open", 3, Double.NaN);
                    double high = optionalDouble(parts, columns, "high", 4, Double.NaN);
                    double low = optionalDouble(parts, columns, "low", 5, Double.NaN);
                    double close = optionalDouble(parts, columns, "close", 6, Double.NaN);
                    double volume = optionalDouble(parts, columns, "volume", Double.NaN);
                    double rawClose = optionalDouble(parts, columns, "raw_close", close);
                    double adjustmentFactor = optionalDouble(parts, columns, "adjustment_factor", 1.0);
                    if (validBar && (Double.isNaN(open) || Double.isNaN(high)
                            || Double.isNaN(low) || Double.isNaN(close))) {
                        continue;
                    }
                    bySymbol.computeIfAbsent(symbol, ignored -> new ArrayList<>())
                            .add(new DailyCandle(date, open, high, low, close, volume,
                                    rawClose, adjustmentFactor, validBar));
                } catch (RuntimeException ignored) {
                    // skip malformed rows
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read daily market snapshot: " + snapshotFile, e);
        }

        List<SymbolDailyCandles> series = new ArrayList<>();
        for (Map.Entry<String, List<DailyCandle>> entry : bySymbol.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                series.add(new SymbolDailyCandles(entry.getKey(), entry.getValue()));
            }
        }
        if (series.isEmpty()) {
            throw new IllegalArgumentException("No usable rows found in daily market snapshot: " + snapshotFile);
        }
        return series;
    }

    private static Map<String, Integer> columns(String header) {
        Map<String, Integer> result = new LinkedHashMap<>();
        String[] names = header.split(",", -1);
        for (int index = 0; index < names.length; index++) {
            result.put(names[index].trim().toLowerCase(java.util.Locale.ROOT), index);
        }
        return result;
    }

    private static int column(Map<String, Integer> columns, String name, int fallback) {
        return columns.getOrDefault(name, fallback);
    }

    private static double optionalDouble(String[] parts, Map<String, Integer> columns,
                                         String name, double fallback) {
        return optionalDouble(parts, columns, name, columns.getOrDefault(name, -1), fallback);
    }

    private static double optionalDouble(String[] parts, Map<String, Integer> columns,
                                         String name, int fallbackIndex, double fallback) {
        Integer index = columns.get(name);
        if (index == null && fallbackIndex >= 0) {
            index = fallbackIndex;
        }
        if (index == null) {
            return fallback;
        }
        if (index >= parts.length || parts[index].isBlank()) {
            return Double.NaN;
        }
        return Double.parseDouble(parts[index].trim());
    }

    private static boolean optionalBoolean(String[] parts, Map<String, Integer> columns,
                                           String name, boolean fallback) {
        Integer index = columns.get(name);
        if (index == null) {
            return fallback;
        }
        if (index >= parts.length || parts[index].isBlank()) {
            return false;
        }
        return Boolean.parseBoolean(parts[index].trim());
    }
}
