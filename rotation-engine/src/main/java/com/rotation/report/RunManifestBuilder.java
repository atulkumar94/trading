package com.rotation.report;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.rotation.config.RotationConfig;
import com.rotation.model.BacktestResult;
import com.rotation.model.DailyBars;
import com.rotation.model.DailyPortfolioRow;
import com.rotation.model.DailyValuation;
import com.rotation.model.RangeMetrics;
import com.rotation.model.ReconciliationReport;
import com.rotation.model.TradeLedgerRow;
import com.rotation.strategy.MomentumRotationStrategy;

/**
 * Builds the run manifest JSON: the strategy configuration, data coverage,
 * valuation conventions, output row counts, reconciliation results, data-quality
 * warnings and reference range metrics for the displayed run.
 */
public final class RunManifestBuilder {

    public static final int SCHEMA_VERSION = 2;

    /** Valuation and timing conventions, shown verbatim in the portal's run details. */
    public static final List<String> CONVENTIONS = List.of(
            "As-of date = end of day: every execution at that session's open (stop fills triggered by the prior"
                    + " close, then the scheduled rebalance) has happened, and positions are valued at that"
                    + " session's adjusted close. Stops triggered by a close fill at the next open.",
            "Non-trading dates resolve to the previous available session; the effective date is always shown.",
            "Prices are split/bonus/dividend adjusted for the whole bar (open/high/low scaled by Adj Close / Close)."
                    + " The engine executes at adjusted opens and values at adjusted closes.",
            "Rankings use the signal session's close; the resulting trades execute at the next session's open."
                    + " Signal and execution dates are kept separately.",
            "Position sizes are floored to whole shares; the remainder stays in cash.",
            "Contributions are external cash credited at the open of the first rebalance in each calendar month"
                    + " (compound mode). They are excluded from P&L and from returns.",
            "Daily return = equity / (previous equity + same-day contribution) - 1 (time-weighted, contribution"
                    + " adjusted). Range returns chain daily returns; drawdown is measured on the chained index.",
            "Realized P&L uses average cost including rebalance re-weights (ADD/TRIM), so realized + unrealized ="
                    + " equity - initial capital - contributions. The tradebook's realized_pnl ((exit - first entry"
                    + " price) x exit quantity) is kept as pnl_vs_entry_price.",
            "A held position without a close is valued at zero, as the engine's mark-to-market does, and is flagged.",
            "Yearly report rows are the engine's own: year boundaries at the last session's close, contributions"
                    + " treated at year end, CAGR as money-weighted XIRR from the first rebalance.");

    public String build(RotationConfig config, DailyBars bars, BacktestResult result, DailyValuation valuation,
                        ReconciliationReport report, List<RangeMetrics> presets, Map<String, long[]> outputRows,
                        OffsetDateTime generatedAt) {
        JsonWriter j = new JsonWriter();
        j.beginObject();
        j.field("schema_version", SCHEMA_VERSION);
        j.field("generated_at", generatedAt.truncatedTo(ChronoUnit.SECONDS).toString());
        j.field("config_file", relative(config.projectRoot(), config.sourceFile()));
        j.field("output_dir", relative(config.projectRoot(), config.resolveOutputDir()));
        j.field("output_prefix", config.outputPrefix());

        j.name("strategy").beginObject();
        j.field("name", config.strategy());
        j.field("lookback_days", config.lookbackDays());
        j.field("top_n", config.topN());
        j.field("exit_n", config.exitN());
        j.field("effective_exit_n", config.effectiveExitN());
        j.field("rebalance_mode", config.rebalanceMode());
        j.field("rebalance_interval_sessions", MomentumRotationStrategy.rebalanceIntervalSessions(config.rebalanceMode()));
        j.field("allocation_mode", config.allocationMode());
        j.field("capital_per_stock", config.capitalPerStock(), 2);
        j.field("initial_capital", result.initialCapital(), 2);
        j.field("monthly_contribution", config.monthlyContribution(), 2);
        j.field("stop_loss_pct", config.stopLossPct(), 4);
        j.field("trailing_stop_pct", config.trailingStopPct(), 4);
        j.field("min_history_days", config.effectiveMinHistoryDays());
        j.field("max_per_sector", config.maxPerSector());
        j.field("sector_file", relative(config.projectRoot(), config.resolveSectorFile()));
        j.endObject();

        j.name("common_config").beginObject();
        j.field("market_sector", config.marketSector());
        j.field("symbols_file", relative(config.projectRoot(), config.resolveSymbolsFile()));
        j.field("start_date", config.startDate());
        j.field("end_date", config.endDate());
        j.field("data_path", relative(config.projectRoot(), config.resolveDataDir()));
        j.endObject();

        List<LocalDate> dates = bars.dates();
        List<DailyPortfolioRow> days = valuation.portfolioRows();
        j.name("coverage").beginObject();
        j.field("data_first_session", dates.get(0));
        j.field("data_last_session", dates.get(dates.size() - 1));
        j.field("data_sessions", dates.size());
        j.field("symbols", bars.symbolCount());
        j.field("backtest_first_session", days.isEmpty() ? null : days.get(0).date);
        j.field("backtest_last_session", days.isEmpty() ? null : days.get(days.size() - 1).date);
        j.field("backtest_sessions", days.size());
        boolean anyRebalance = !result.rebalances().isEmpty();
        j.field("first_signal", anyRebalance ? result.rebalances().get(0).signalDate : null);
        j.field("first_rebalance", anyRebalance ? result.rebalances().get(0).date : null);
        j.field("last_rebalance", anyRebalance ? result.rebalances().get(result.rebalances().size() - 1).date : null);
        j.field("rebalances", result.rebalances().size());
        Map<String, Integer> fillsByAction = new TreeMap<>();
        for (TradeLedgerRow row : valuation.ledgerRows()) {
            fillsByAction.merge(row.action, 1, Integer::sum);
        }
        j.name("fills_by_action").beginObject();
        for (Map.Entry<String, Integer> e : fillsByAction.entrySet()) {
            j.field(e.getKey(), e.getValue());
        }
        j.endObject();
        j.field("volume_available", false);
        j.endObject();

        j.name("conventions").beginArray();
        for (String c : CONVENTIONS) {
            j.value(c);
        }
        j.endArray();

        j.name("outputs").beginArray();
        for (Map.Entry<String, long[]> e : outputRows.entrySet()) {
            j.beginObject();
            j.field("file", e.getKey());
            j.field("rows", e.getValue()[0]);
            if (e.getValue()[1] >= 0) {
                j.field("expected_rows", e.getValue()[1]);
            }
            j.endObject();
        }
        j.endArray();

        j.name("reconciliation").beginObject();
        j.field("passed", report.passed());
        j.name("checks").beginArray();
        for (ReconciliationReport.Check check : report.checks()) {
            j.beginObject().field("name", check.name).field("passed", check.passed)
                    .field("detail", check.detail).endObject();
        }
        j.endArray();
        j.endObject();

        j.name("warnings").beginArray();
        for (String w : report.warnings()) {
            j.value(w);
        }
        j.endArray();

        j.name("reference_metrics").beginObject();
        j.field("as_of", days.isEmpty() ? null : days.get(days.size() - 1).date);
        j.name("presets").beginArray();
        for (RangeMetrics m : presets) {
            writeRange(j, m);
        }
        j.endArray();
        j.endObject();

        j.endObject();
        return j.toString();
    }

    static void writeRange(JsonWriter j, RangeMetrics m) {
        j.beginObject();
        j.field("label", m.label);
        j.field("requested_from", m.requestedFrom);
        j.field("as_of", m.asOf);
        j.field("first_session", m.firstSession);
        j.field("base_date", m.baseDate);
        j.field("sessions", m.sessions);
        j.field("start_equity", m.startEquity, 2);
        j.field("end_equity", m.endEquity, 2);
        j.field("contributions", m.contributions, 2);
        j.field("pnl", m.pnl, 2);
        j.field("return_pct", m.returnPct, 6);
        j.field("max_drawdown_pct", m.maxDrawdownPct, 6);
        j.field("annualized_return_pct", m.annualizedReturnPct, 6);
        j.field("truncated", m.truncated);
        j.endObject();
    }

    private static String relative(Path root, Path path) {
        if (path == null) {
            return null;
        }
        try {
            return root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString();
        } catch (IllegalArgumentException e) {
            return path.toString();
        }
    }
}
