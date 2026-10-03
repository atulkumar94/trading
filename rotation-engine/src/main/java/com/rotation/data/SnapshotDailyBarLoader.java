package com.rotation.data;

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

import com.rotation.model.DailyCandle;
import com.rotation.model.SymbolDailyCandles;

/**
 * Reads {@code DailyBars} input back from a previously written daily market
 * snapshot CSV (header
 * {@code date,symbol,prev_close,open,high,low,close,return_vs_prev_close,volume}).
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
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split(",");
                if (parts.length < 7) {
                    continue;
                }
                try {
                    LocalDate date = LocalDate.parse(parts[0].trim());
                    String symbol = parts[1].trim();
                    double open = Double.parseDouble(parts[3].trim());
                    double high = Double.parseDouble(parts[4].trim());
                    double low = Double.parseDouble(parts[5].trim());
                    double close = Double.parseDouble(parts[6].trim());
                    double volume = parseVolume(parts);
                    bySymbol.computeIfAbsent(symbol, ignored -> new ArrayList<>())
                            .add(new DailyCandle(date, open, high, low, close, volume));
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

    /** Volume is the optional 9th column; absent or blank reads as NaN (unknown). */
    private static double parseVolume(String[] parts) {
        if (parts.length <= 8) {
            return Double.NaN;
        }
        String raw = parts[8].trim();
        return raw.isEmpty() ? Double.NaN : Double.parseDouble(raw);
    }
}
