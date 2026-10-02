package com.rotation;

import com.rotation.config.RotationConfig;
import com.rotation.data.DailyBarLoader;
import com.rotation.data.MinuteHistoryDailyBarLoader;
import com.rotation.data.SectorSymbolsReader;
import com.rotation.data.SnapshotDailyBarLoader;
import com.rotation.engine.RotationEngine;
import com.rotation.job.DailyRefreshJob;
import com.rotation.job.DailyReportJob;
import com.rotation.model.DailyBars;
import com.rotation.model.SymbolDailyCandles;
import com.rotation.report.CsvExporter;
import com.rotation.report.MarketSnapshotExporter;
import com.rotation.report.MonthlyMarketSnapshotExporter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Entry point: load config, build daily bars, run the rotation backtest, export results. */
public final class Main {

    public static void main(String[] args) {
        Path projectRoot = Path.of(System.getProperty("user.dir"));

        Path configFile = projectRoot.resolve("config/rotation.properties");
        boolean dailyRefresh = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equalsIgnoreCase("--daily-refresh") || arg.equalsIgnoreCase("daily-refresh")) {
                dailyRefresh = true;
            } else if (arg.equalsIgnoreCase("--config") || arg.equalsIgnoreCase("-c")) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("Missing value for " + arg + ". Expected a config file path.");
                }
                configFile = resolvePath(projectRoot, args[++i]);
            } else if (arg.startsWith("--config=")) {
                configFile = resolvePath(projectRoot, arg.substring("--config=".length()));
            } else if (!arg.startsWith("--")) {
                configFile = resolvePath(projectRoot, arg);
            }
        }

        if (!Files.exists(configFile)) {
            System.err.println("Config file not found: " + configFile);
            System.exit(2);
        }

        if (dailyRefresh) {
            runDailyRefresh(configFile);
            return;
        }

        RotationConfig config = RotationConfig.load(configFile, projectRoot);

        Path dataDir = config.resolveDataDir();
        if (!Files.isDirectory(dataDir)) {
            System.err.println("Input data path does not exist: " + dataDir);
            System.exit(2);
        }

        DailyBarLoader loader = new MinuteHistoryDailyBarLoader();

        System.out.printf(Locale.US, "Loading data from %s ...%n", dataDir);
        List<SymbolDailyCandles> series = loader.load(dataDir);
        DailyBars bars = DailyBars.build(series, loader.forwardFill());

        // start.date does not trim the data: full history is kept so the lookback
        // window is already warm on the start date. The engine trades from start.date.
        if (config.startDate() != null) {
            System.out.printf(Locale.US, "Trading from start date: %s (history loaded from %s for lookback warm-up)%n",
                    config.startDate(), bars.dates().get(0));
        }

        if (config.endDate() != null) {
            bars = bars.filterTo(config.endDate());
            System.out.printf(Locale.US, "Applying end date filter: %s (last available date: %s)%n",
                    config.endDate(), bars.dates().get(bars.dateCount() - 1));
        }

        Path symbolsFile = config.resolveSymbolsFile();
        if (symbolsFile != null) {
            List<String> requested = readSymbols(symbolsFile);
            int before = bars.symbolCount();
            bars = bars.restrictTo(requested);
            System.out.printf(Locale.US, "Universe filtered by %s: %d matched (was %d in source)%n",
                    symbolsFile, bars.symbolCount(), before);
        }

        String marketSector = config.marketSector();
        if (marketSector != null) {
            List<String> sectorSymbols =
                    SectorSymbolsReader.readSymbolsInSector(config.resolveSectorFile(), marketSector);
            int before = bars.symbolCount();
            bars = bars.restrictTo(sectorSymbols);
            System.out.printf(Locale.US, "Universe filtered by market.sector '%s': %d matched (was %d in source)%n",
                    marketSector, bars.symbolCount(), before);
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

        var rotationResult = new RotationEngine(config).run(snapshotBars);
        new CsvExporter().export(rotationResult, outputDir, prefix);
        new DailyReportJob().run(config, snapshotBars, rotationResult);

        System.out.println();
        System.out.printf(Locale.US, "Data directory: %s%n", dataDir);
        System.out.printf(Locale.US, "Snapshot rows exported for %d symbols across %d dates%n",
                snapshotBars.symbolCount(), snapshotBars.dateCount());
        System.out.printf(Locale.US, "Saved daily market snapshot: %s%n",
                outputDir.resolve(prefix + "_daily_market_snapshot.csv"));
        System.out.printf(Locale.US, "Saved monthly market snapshot: %s%n",
                outputDir.resolve(prefix + "_monthly_market_snapshot.csv"));
        System.out.printf(Locale.US, "Saved rotation backtest outputs: %s, %s, %s, %s, %s, %s, %s%n",
                outputDir.resolve(prefix + "_rebalances.csv"),
                outputDir.resolve(prefix + "_equity.csv"),
                outputDir.resolve(prefix + "_performance.csv"),
            outputDir.resolve(prefix + "_tradebook.csv"),
            outputDir.resolve(prefix + "_holdings.csv"),
            outputDir.resolve(prefix + "_lookback.csv"),
                outputDir.resolve(prefix + "_yearly.csv"));
    }

    public static void runDailyRefresh(Path configFile) {
        RotationConfig config = RotationConfig.load(configFile, Path.of(System.getProperty("user.dir")));
        new DailyRefreshJob().run(config, Path.of(System.getProperty("user.dir")));
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

    private static Path resolvePath(Path projectRoot, String value) {
        Path candidate = Path.of(value);
        return candidate.isAbsolute() ? candidate : projectRoot.resolve(candidate);
    }
}
