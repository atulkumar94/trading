package com.rotation.data;

import com.rotation.model.DailyCandle;
import com.rotation.model.SymbolDailyCandles;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Loads daily OHLCV history files ({@code {SYMBOL}.csv} with header
 * {@code Date,Adj Close,Close,High,Low,Open,Volume}). The whole bar is split /
 * bonus / dividend adjusted using the {@code Adj Close / Close} ratio so returns
 * and ranking are consistent. Legacy minute files
 * ({@code ddMMyyyyHHmm,open,high,low,close,volume}) are still parsed row by row.
 * Files whose name starts with {@code _} (e.g. {@code _download_log.csv}) are skipped.
 */
public final class DailyFileBarLoader implements DailyBarLoader {

    private static final DateTimeFormatter MINUTE_TS = DateTimeFormatter.ofPattern("ddMMyyyyHHmm");

    @Override
    public boolean forwardFill() {
        return true;
    }

    @Override
    public List<SymbolDailyCandles> load(Path dataDir) {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDir, "*.csv")) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (name.startsWith("_")) {
                    continue; // skip non-data files such as _download_log.csv
                }
                files.add(path);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to list history files under " + dataDir, e);
        }
        if (files.isEmpty()) {
            throw new IllegalArgumentException("No readable history files found under " + dataDir);
        }
        Collections.sort(files);

        List<SymbolDailyCandles> result = new ArrayList<>();
        for (Path file : files) {
            List<DailyCandle> candles = readFile(file);
            if (!candles.isEmpty()) {
                String symbol = stripExtension(file.getFileName().toString());
                result.add(new SymbolDailyCandles(symbol, candles));
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("No usable history files found under " + dataDir);
        }
        return result;
    }

    private List<DailyCandle> readFile(Path file) {
        List<DailyCandle> candles = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            boolean headerSkipped = false;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split(",");
                if (parts.length < 6) {
                    continue;
                }

                if (!headerSkipped) {
                    headerSkipped = true;
                    String first = parts[0].trim();
                    if (first.equalsIgnoreCase("Date") || first.equalsIgnoreCase("timestamp")) {
                        continue;
                    }
                }

                try {
                    String first = parts[0].trim();
                    if (first.matches("\\d{8}\\d{4}")) {
                        LocalDateTime ts = LocalDateTime.parse(first, MINUTE_TS);
                        LocalDate date = ts.toLocalDate();
                        double open = Double.parseDouble(parts[1].trim());
                        double high = Double.parseDouble(parts[2].trim());
                        double low = Double.parseDouble(parts[3].trim());
                        double close = Double.parseDouble(parts[4].trim());
                        double volume = Double.parseDouble(parts[5].trim());
                        candles.add(new DailyCandle(date, open, high, low, close, volume,
                                close, 1.0, validBar(open, high, low, close, volume)));
                    } else {
                        LocalDate date = LocalDate.parse(first);
                        double adjClose = Double.parseDouble(parts[1].trim());
                        double rawClose = Double.parseDouble(parts[2].trim());
                        double rawHigh = Double.parseDouble(parts[3].trim());
                        double rawLow = Double.parseDouble(parts[4].trim());
                        double rawOpen = Double.parseDouble(parts[5].trim());
                        double volume = Double.parseDouble(parts[6].trim());
                        // Split/bonus/dividend adjust the whole bar using the
                        // Adj Close / Close ratio so returns and ranking stay
                        // consistent and free of phantom overnight jumps.
                        double factor = rawClose > 0 ? adjClose / rawClose : 1.0;
                        boolean valid = factor > 0.0 && validBar(rawOpen, rawHigh, rawLow, rawClose, volume)
                            && adjClose > 0.0;
                        candles.add(new DailyCandle(date, rawOpen * factor, rawHigh * factor,
                            rawLow * factor, adjClose, volume, rawClose, factor, valid));
                    }
                } catch (NumberFormatException | DateTimeParseException | ArrayIndexOutOfBoundsException ignored) {
                    // skip malformed rows
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        return candles;
    }

    private static boolean validBar(double open, double high, double low, double close, double volume) {
        return Double.isFinite(open) && Double.isFinite(high) && Double.isFinite(low)
                && Double.isFinite(close) && Double.isFinite(volume) && open > 0.0 && high > 0.0
                && low > 0.0 && close > 0.0 && volume >= 0.0
                && high >= Math.max(open, close) && low <= Math.min(open, close);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
