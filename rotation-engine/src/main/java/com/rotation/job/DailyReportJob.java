package com.rotation.job;

import com.rotation.config.RotationConfig;
import com.rotation.data.SectorSymbolsReader;
import com.rotation.report.DailyValuationBuilder;
import com.rotation.report.PerformanceCalculator;
import com.rotation.report.ReportReconciler;
import com.rotation.model.BacktestResult;
import com.rotation.model.DailyBars;
import com.rotation.model.DailyValuation;
import com.rotation.model.RangeMetrics;
import com.rotation.model.ReconciliationReport;
import com.rotation.report.DailyReportExporter;
import com.rotation.report.PortalExporter;
import com.rotation.report.RunManifestBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Writes the daily valuation reports, the run manifest and (optionally) the HTML
 * portal for a finished backtest, then fails loudly if any reconciliation check
 * failed. Runs after {@code CsvExporter} in both the default and daily-refresh modes.
 */
public final class DailyReportJob {

    public ReconciliationReport run(RotationConfig config, DailyBars bars, BacktestResult result) {
        Path outputDir = config.resolveOutputDir();
        String prefix = config.outputPrefix();
        Map<String, String> sectors = SectorSymbolsReader.readSectorMap(config.resolveSectorFile());

        DailyValuation valuation = new DailyValuationBuilder(sectors).build(bars, result);
        ReconciliationReport checks = new ReportReconciler().check(bars, result, valuation, config.startDate());
        new DailyReportExporter().export(valuation, outputDir, prefix);

        Map<String, long[]> outputRows = countOutputRows(outputDir, prefix, bars, result, valuation);
        ReconciliationReport report = withRowCountCheck(checks, outputRows);

        List<RangeMetrics> presets = valuation.portfolioRows().isEmpty() ? List.of()
                : new PerformanceCalculator(valuation.portfolioRows(), bars.dates(), valuation.initialCapital())
                        .presets(valuation.portfolioRows().get(valuation.portfolioRows().size() - 1).date);
        String manifest = new RunManifestBuilder().build(config, bars, result, valuation, report, presets,
                outputRows, OffsetDateTime.now());
        Path manifestPath = outputDir.resolve(prefix + "_run_manifest.json");
        try {
            Files.writeString(manifestPath, manifest + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write " + manifestPath, e);
        }

        System.out.printf(Locale.US, "Saved daily reports: %s, %s, %s%n",
                outputDir.resolve(prefix + "_daily_portfolio.csv"),
                outputDir.resolve(prefix + "_daily_positions.csv"),
                outputDir.resolve(prefix + "_trade_ledger.csv"));
        System.out.printf(Locale.US, "Saved run manifest: %s%n", manifestPath);
        if (config.portalEnabled()) {
            Path portal = new PortalExporter().export(manifest, bars, result, valuation, sectors, outputDir, prefix);
            System.out.printf(Locale.US, "Saved reporting portal: %s (%.1f MB)%n", portal, sizeMb(portal));
        }
        for (ReconciliationReport.Check check : report.checks()) {
            System.out.printf(Locale.US, "  [%s] %s - %s%n", check.passed ? "ok" : "FAIL", check.name, check.detail);
        }
        for (String warning : report.warnings()) {
            System.out.printf(Locale.US, "  [warn] %s%n", warning);
        }
        report.requirePassed();
        return report;
    }

    /** Actual data rows per output file, with the expected count where it is derivable (else -1). */
    private static Map<String, long[]> countOutputRows(Path outputDir, String prefix, DailyBars bars,
                                                       BacktestResult result, DailyValuation valuation) {
        long snapshotCells = 0;
        for (int d = 0; d < bars.dateCount(); d++) {
            for (int s = 0; s < bars.symbolCount(); s++) {
                if (!Double.isNaN(bars.closeAt(d, s))) {
                    snapshotCells++;
                }
            }
        }
        Map<String, Long> expected = new LinkedHashMap<>();
        expected.put("daily_market_snapshot", snapshotCells);
        expected.put("monthly_market_snapshot", -1L);
        expected.put("rebalances", (long) result.rebalances().size());
        expected.put("equity", (long) result.equityCurve().size());
        expected.put("performance", (long) result.performanceRows().size());
        expected.put("tradebook", (long) result.tradebookRows().size());
        expected.put("holdings", (long) result.holdingsRows().size());
        expected.put("lookback", (long) result.lookbackRows().size());
        expected.put("yearly", result.equityCurve().isEmpty() ? 0L : (long) result.yearEndMarks().size());
        expected.put("daily_portfolio", (long) valuation.portfolioRows().size());
        expected.put("daily_positions", (long) valuation.positionRows().size());
        expected.put("trade_ledger", (long) valuation.ledgerRows().size());

        Map<String, long[]> rows = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : expected.entrySet()) {
            String file = prefix + "_" + e.getKey() + ".csv";
            Path path = outputDir.resolve(file);
            if (!Files.exists(path)) {
                continue;
            }
            try (Stream<String> lines = Files.lines(path)) {
                rows.put(file, new long[] {Math.max(0, lines.count() - 1), e.getValue()});
            } catch (IOException ex) {
                throw new UncheckedIOException("Unable to count rows in " + path, ex);
            }
        }
        return rows;
    }

    private static ReconciliationReport withRowCountCheck(ReconciliationReport report, Map<String, long[]> rows) {
        List<String> mismatches = new ArrayList<>();
        for (Map.Entry<String, long[]> e : rows.entrySet()) {
            long[] v = e.getValue();
            if (v[1] >= 0 && v[0] != v[1]) {
                mismatches.add(e.getKey() + " has " + v[0] + " rows, expected " + v[1]);
            }
        }
        List<ReconciliationReport.Check> checks = new ArrayList<>(report.checks());
        checks.add(new ReconciliationReport.Check("output_row_counts", mismatches.isEmpty(),
                mismatches.isEmpty() ? rows.size() + " CSV files match their expected row counts"
                        : String.join("; ", mismatches)));
        return new ReconciliationReport(checks, report.warnings());
    }

    private static double sizeMb(Path path) {
        try {
            return Files.size(path) / 1_000_000.0;
        } catch (IOException e) {
            return Double.NaN;
        }
    }
}
