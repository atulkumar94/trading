package com.rotation.pipeline;

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
import com.rotation.data.DailyFileBarLoader;
import com.rotation.data.SectorSymbolsReader;
import com.rotation.data.SnapshotDailyBarLoader;
import com.rotation.engine.RotationEngine;
import com.rotation.job.DailyReportJob;
import com.rotation.model.BacktestResult;
import com.rotation.model.DailyBars;
import com.rotation.model.SymbolDailyCandles;
import com.rotation.report.CsvExporter;
import com.rotation.report.MarketSnapshotExporter;
import com.rotation.report.MonthlyMarketSnapshotExporter;

/** Shared ingest, filter, snapshot, run, and report pipeline for CLI and refresh mode. */
public final class BacktestPipeline {

    public void run(Path configFile, Path projectRoot, boolean dailyRefresh) {
        run(RotationConfig.load(configFile, projectRoot), dailyRefresh);
    }

    public void run(RotationConfig config, boolean dailyRefresh) {
        Path dataDir = config.resolveDataDir();
        if (!Files.isDirectory(dataDir)) {
            throw new IllegalArgumentException("Input data path does not exist: " + dataDir);
        }
        DailyBarLoader loader = new DailyFileBarLoader();
        if (!dailyRefresh) {
            System.out.printf(Locale.US, "Loading data from %s ...%n", dataDir);
        }
        List<SymbolDailyCandles> series = loader.load(dataDir);
        DailyBars bars = DailyBars.build(series, loader.forwardFill());

        if (!dailyRefresh && config.startDate() != null) {
            System.out.printf(Locale.US,
                    "Trading from start date: %s (history loaded from %s for lookback warm-up)%n",
                    config.startDate(), bars.dates().get(0));
        }
        if (config.endDate() != null) {
            bars = bars.filterTo(config.endDate());
            if (!dailyRefresh) {
                System.out.printf(Locale.US, "Applying end date filter: %s (last available date: %s)%n",
                        config.endDate(), bars.dates().get(bars.dateCount() - 1));
            }
        }

        Path symbolsFile = config.resolveSymbolsFile();
        if (symbolsFile != null) {
            int before = bars.symbolCount();
            bars = bars.restrictTo(readSymbols(symbolsFile));
            if (!dailyRefresh) {
                System.out.printf(Locale.US, "Universe filtered by %s: %d matched (was %d in source)%n",
                        symbolsFile, bars.symbolCount(), before);
            }
        }

        String marketSector = config.marketSector();
        if (marketSector != null) {
            int before = bars.symbolCount();
            bars = bars.restrictTo(
                    SectorSymbolsReader.readSymbolsInSector(config.resolveSectorFile(), marketSector));
            if (!dailyRefresh) {
                System.out.printf(Locale.US, "Universe filtered by market.sector '%s': %d matched (was %d in source)%n",
                        marketSector, bars.symbolCount(), before);
            }
        }

        MarketDataValidator.validate(bars, config.dataValidationMode());
        validateBars(bars);

        Path outputDir = config.resolveOutputDir();
        String prefix = config.outputPrefix();
        new MarketSnapshotExporter().export(bars, outputDir, prefix);

        // Both reports and the runner consume the same materialized daily snapshot.
        Path snapshot = outputDir.resolve(prefix + "_daily_market_snapshot.csv");
        DailyBars snapshotBars = DailyBars.build(SnapshotDailyBarLoader.load(snapshot), false);
        new MonthlyMarketSnapshotExporter().export(snapshotBars, outputDir, prefix);

        BacktestResult result = new RotationEngine(config).run(snapshotBars);
        new CsvExporter().export(result, outputDir, prefix);
        new DailyReportJob().run(config, snapshotBars, result);
        printSummary(config, dataDir, snapshotBars, dailyRefresh);
    }

    private static void printSummary(RotationConfig config, Path dataDir, DailyBars bars,
                                    boolean dailyRefresh) {
        Path outputDir = config.resolveOutputDir();
        String prefix = config.outputPrefix();
        if (dailyRefresh) {
            System.out.printf(Locale.US, "Daily refresh job: processed %d symbols and %d dates from %s to %s%n",
                    bars.symbolCount(), bars.dateCount(),
                    bars.dates().isEmpty() ? "n/a" : bars.dates().get(0),
                    bars.dates().isEmpty() ? "n/a" : bars.dates().get(bars.dateCount() - 1));
        } else {
            System.out.printf(Locale.US, "Data directory: %s%n", dataDir);
            System.out.printf(Locale.US, "Snapshot rows exported for %d symbols across %d dates%n",
                    bars.symbolCount(), bars.dateCount());
        }
        System.out.printf(Locale.US, "Saved daily market snapshot: %s%n",
                outputDir.resolve(prefix + "_daily_market_snapshot.csv"));
        System.out.printf(Locale.US, "Saved monthly market snapshot: %s%n",
                outputDir.resolve(prefix + "_monthly_market_snapshot.csv"));
        System.out.printf(Locale.US, "Saved rotation backtest outputs: %s, %s, %s, %s, %s, %s, %s%n",
                outputDir.resolve(prefix + "_rebalances.csv"), outputDir.resolve(prefix + "_equity.csv"),
                outputDir.resolve(prefix + "_performance.csv"), outputDir.resolve(prefix + "_tradebook.csv"),
                outputDir.resolve(prefix + "_holdings.csv"), outputDir.resolve(prefix + "_lookback.csv"),
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
            int symbolColumn = -1;
            for (int i = 0; i < columns.length; i++) {
                if (columns[i].trim().equalsIgnoreCase("symbol")) {
                    symbolColumn = i;
                    break;
                }
            }
            if (symbolColumn < 0) {
                throw new IllegalArgumentException("Symbols file must include a 'symbol' column: " + symbolsFile);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length > symbolColumn) {
                    String symbol = parts[symbolColumn].trim();
                    if (!symbol.isEmpty()) {
                        symbols.add(symbol);
                    }
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to read symbols file: " + symbolsFile, exception);
        }
        if (symbols.isEmpty()) {
            throw new IllegalArgumentException("No symbols found in symbols file: " + symbolsFile);
        }
        return symbols;
    }

    private static void validateBars(DailyBars bars) {
        if (bars.dateCount() == 0 || bars.symbolCount() == 0) {
            throw new IllegalArgumentException("Filtered market data must contain sessions and symbols.");
        }
        for (int index = 1; index < bars.dateCount(); index++) {
            if (!bars.dates().get(index).isAfter(bars.dates().get(index - 1))) {
                throw new IllegalArgumentException("Market sessions must be unique and in ascending order.");
            }
        }
    }
}
