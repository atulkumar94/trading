package com.rotation.job;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.rotation.config.RotationConfig;
import com.rotation.data.DailyBarLoader;
import com.rotation.data.MinuteHistoryDailyBarLoader;
import com.rotation.data.SectorSymbolsReader;
import com.rotation.data.SnapshotDailyBarLoader;
import com.rotation.model.DailyBars;
import com.rotation.model.SymbolDailyCandles;
import com.rotation.report.CsvExporter;
import com.rotation.report.MarketSnapshotExporter;
import com.rotation.report.MonthlyMarketSnapshotExporter;
import com.rotation.strategy.Strategies;

/** Daily refresh job that rebuilds the market snapshot outputs and trades from a configurable start date. */
public final class DailyRefreshJob {

    public void run(RotationConfig config, Path projectRoot) {
        Path dataDir = config.resolveDataDir();
        if (!Files.isDirectory(dataDir)) {
            throw new IllegalArgumentException("Input data path does not exist: " + dataDir);
        }

        DailyBarLoader loader = new MinuteHistoryDailyBarLoader();

        List<SymbolDailyCandles> series = loader.load(dataDir);
        DailyBars bars = DailyBars.build(series, loader.forwardFill());

        // start.date does not trim the data: full history is kept so the lookback
        // window is already warm on the start date. The engine trades from start.date.
        if (config.endDate() != null) {
            bars = bars.filterTo(config.endDate());
        }

        Path symbolsFile = config.resolveSymbolsFile();
        if (symbolsFile != null) {
            List<String> requested = readSymbols(symbolsFile);
            bars = bars.restrictTo(requested);
        }

        String marketSector = config.marketSector();
        if (marketSector != null) {
            bars = bars.restrictTo(
                    SectorSymbolsReader.readSymbolsInSector(config.resolveSectorFile(), marketSector));
        }

        Path outputDir = config.resolveOutputDir();
        String prefix = config.outputPrefix();
        new MarketSnapshotExporter().export(bars, outputDir, prefix);

        // The daily market snapshot is the single source of truth: reload
        // DailyBars from it so the monthly snapshot and rotation engine read the
        // exact same materialized data instead of re-deriving it.
        Path dailySnapshot = outputDir.resolve(prefix + "_daily_market_snapshot.csv");
        DailyBars snapshotBars = DailyBars.build(SnapshotDailyBarLoader.load(dailySnapshot), false);

        new MonthlyMarketSnapshotExporter().export(snapshotBars, outputDir, prefix);

        var rotationResult = Strategies.create(config).run(snapshotBars);
        new CsvExporter().export(rotationResult, outputDir, prefix);
        new DailyReportJob().run(config, snapshotBars, rotationResult);

        System.out.printf(Locale.US, "Daily refresh job: processed %d symbols and %d dates from %s to %s%n",
                snapshotBars.symbolCount(), snapshotBars.dateCount(),
                snapshotBars.dates().isEmpty() ? "n/a" : snapshotBars.dates().get(0),
                snapshotBars.dates().isEmpty() ? "n/a" : snapshotBars.dates().get(snapshotBars.dates().size() - 1));
        System.out.printf(Locale.US, "Saved daily market snapshot: %s%n",
                outputDir.resolve(prefix + "_daily_market_snapshot.csv"));
        System.out.printf(Locale.US, "Saved monthly market snapshot: %s%n",
                outputDir.resolve(prefix + "_monthly_market_snapshot.csv"));
        System.out.printf(Locale.US, "Saved rotation backtest outputs: %s, %s, %s, %s, %s, %s%n",
                outputDir.resolve(prefix + "_rebalances.csv"),
                outputDir.resolve(prefix + "_equity.csv"),
                outputDir.resolve(prefix + "_performance.csv"),
            outputDir.resolve(prefix + "_tradebook.csv"),
            outputDir.resolve(prefix + "_lookback.csv"),
                outputDir.resolve(prefix + "_yearly.csv"));
    }

    private static List<String> readSymbols(Path symbolsFile) {
        List<String> symbols = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(symbolsFile)) {
            String header = reader.readLine();
            if (header == null) {
                throw new IllegalArgumentException("Symbols file is empty: " + symbolsFile); 
            }
            String[] columns = header.split(",");
            int symbolCol = -1;
            for (int i = 0; i < columns.length; i++) {
                if (columns[i].trim().equalsIgnoreCase("symbol")) {
                    symbolCol = i;
                    break;
                }
            }
            if (symbolCol < 0) {
                throw new IllegalArgumentException("Symbols file must include a 'symbol' column: " + symbolsFile);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length > symbolCol) {
                    String symbol = parts[symbolCol].trim();
                    if (!symbol.isEmpty()) {
                        symbols.add(symbol);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read symbols file: " + symbolsFile, e);
        }
        if (symbols.isEmpty()) {
            throw new IllegalArgumentException("No symbols found in symbols file: " + symbolsFile);
        }
        return symbols;
    }
}
