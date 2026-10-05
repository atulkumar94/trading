# Stocks Data — Daily Market Snapshot & Rotation Strategy Engine

End-to-end pipeline: download NSE daily OHLCV data, normalize it into market snapshots, and run a momentum rotation backtest.

## Data flow

```
symbols.csv
    ↓  download_nse_data.py (yfinance)
stocks/daily/{SYMBOL}.csv
    ↓  BacktestPipeline: DailyFileBarLoader → DailyBars → validate/filter
(end.date / symbols.file / market.sector) — full history kept for lookback warm-up
    ↓  MarketSnapshotExporter
rotation_daily_market_snapshot.csv   ← single source of truth
    ↓  SnapshotDailyBarLoader (reload)
    ├─→ MonthlyMarketSnapshotExporter → rotation_monthly_market_snapshot.csv
    └─→ BacktestRunner (trades from start.date; Strategy emits close-time intents) → CsvExporter → rebalances / equity / performance /
                                        │               tradebook / holdings / lookback / yearly
                                        └─→ DailyReportJob → daily_portfolio / daily_positions / trade_ledger
                                                           → run_manifest.json (config, coverage, checks)
                                                           → rotation_portal.html (self-contained report)
```

## Project structure

```
stocksData/
├── README.md                          ← Source of truth: data flow & usage
├── .github/copilot-instructions.md    ← AI assistant rules (follows this README)
├── .claude/CLAUDE.md                  ← Claude Code entry point (follows copilot-instructions)
├── download_nse_data.py               ← Step 1: download daily OHLCV via yfinance
├── symbols.csv                        ← Input universe (symbol + sector columns)
├── stocks/daily/                      ← Downloaded CSVs, one per symbol (gitignored)
└── rotation-engine/                   ← Step 2: Java snapshot & backtest engine
    ├── README.md                      ← Engine details & strategy rules
    ├── pom.xml
    ├── config/rotation.properties     ← All engine settings
    ├── output/rotation/               ← Generated CSV reports (gitignored)
    └── src/
        ├── main/java/com/rotation/
        │   ├── Main.java              ← CLI argument parsing
        │   ├── config/                ← CommonConfig, MomentumConfig, RotationConfig facade
        │   ├── data/                  ← Loaders (MinuteHistory, Snapshot)
        │   ├── engine/                ← RotationEngine compatibility facade
        │   ├── pipeline/              ← Shared ingest-to-report workflow
        │   ├── runner/                ← BacktestRunner day loop and strategy coordination
        │   ├── market/                 ← MarketData and backward-only MarketView
        │   ├── indicators/             ← Cached point-in-time indicators
        │   ├── execution/             ← ExecutionModel and BacktestExecution
        │   ├── portfolio/             ← Portfolio, Position, Ledger, Fill
        │   ├── strategy/              ← Strategy intents and momentum signal logic
        │   ├── job/                   ← DailyRefreshJob
        │   ├── model/                 ← Immutable data models (DailyBars, …)
        │   └── report/                ← Ledger report projections and CSV exporters
        └── test/java/com/rotation/    ← Unit tests
```

## Quick start

### 1) Download stock data

Run from the repo root:

```bash
pip install yfinance pandas
python download_nse_data.py symbols.csv --start 2010-01-01
```

Output defaults to `stocks/daily/` next to the script (override with `--outdir`).

### 2) Run the engine

Run from `rotation-engine/` — relative paths in the config resolve from there:

```bash
cd rotation-engine
mvn exec:java -Dexec.mainClass=com.rotation.Main

# Daily refresh mode (scheduled jobs)
mvn -q compile
java -cp target/classes com.rotation.Main daily-refresh

# Alternate config
java -cp target/classes com.rotation.Main --config config/other.properties
```

Reports are written to `rotation-engine/output/rotation/`.

## Configuration

All settings live in [rotation-engine/config/rotation.properties](rotation-engine/config/rotation.properties). Key options:

| Key | Purpose |
|-----|---------|
| `data.path` | Folder of daily CSVs (default `../stocks/daily`) |
| `start.date` | Optional first trading date. History before it is still loaded and used for the lookback, so the first rebalance executes on the start date itself (it only waits `lookback.days` when no earlier history exists) |
| `end.date` | Optional inclusive end date; data after it is dropped |
| `symbols.file` | Optional CSV with a `symbol` column to restrict the universe |
| `sector.file` / `momentum.max.per.sector` | Sector diversification cap (default `../symbols.csv`) |
| `market.sector` | Optional: run on one sector only (e.g. `Healthcare`, case-insensitive, must exist in `sector.file`). Disables `momentum.max.per.sector` |
| `momentum.lookback.days` | Momentum lookback in trading sessions |
| `momentum.top.n` / `momentum.exit.n` | Names entered / rank threshold before exit |
| `strategy` | Active signal logic (currently `momentum`) |
| `momentum.rebalance.mode` | Trading-session cadence: `weekly` = every 5 sessions, `monthly_twice` = every 10, `monthly` = every 20 |
| `momentum.capital.per.stock`, `momentum.allocation.mode`, `momentum.monthly.contribution` | Sizing |
| `momentum.stop.loss.pct` / `momentum.trailing.stop.pct` | Intra-period exits |
| `momentum.min.history.days` | Eligibility (effective threshold is `max(momentum.min.history.days, momentum.lookback.days)`) |
| `output.dir` / `output.prefix` | Report location and file prefix |
| `portal.enabled` | Write the self-contained HTML reporting portal (default `true`) |

Unprefixed historical momentum keys remain accepted with a deprecation warning. If both a
legacy key and its `momentum.*` form are set, the namespaced value wins. Unknown keys fail
with a closest-match suggestion.

The daily market snapshot keeps its existing first eight columns and appends `volume`,
`raw_close`, `adjustment_factor`, and `valid_bar`. Older snapshots without the appended
metadata remain readable. `data.validation.mode=warn` (default) reports invalid/missing bars,
valid close jumps over 50%, and runs of at least three identical valid OHLC bars without
changing prices; `fail` stops before exporting the snapshot.

## Outputs

All in `rotation-engine/output/rotation/` with prefix `rotation`:

- `_daily_market_snapshot.csv` — one row per symbol per trading day (source of truth; full history up to `end.date`, including pre-`start.date` warm-up)
- `_monthly_market_snapshot.csv` — one row per symbol per month (same range)
- `_rebalances.csv` — one row per rebalance
- `_equity.csv` — equity curve
- `_performance.csv` — ranked universe per rebalance
- `_tradebook.csv` — entries and exits
- `_holdings.csv` — positions per period
- `_lookback.csv` — latest lookback ranking
- `_yearly.csv` — calendar-year returns
- `_daily_portfolio.csv` — one row per session from the trade start: cash, invested value, equity, contributions, P&L, daily return, TWR index, drawdown
- `_daily_positions.csv` — one row per held symbol per session: entry date, quantity, entry price, average cost, cost basis, adjusted close, market value, unrealized P&L, weight, price status
- `_trade_ledger.csv` — every fill in execution order, incl. the share re-weights of held names (`ADD`/`TRIM`) that the tradebook omits, with average-cost realized P&L and the exit reason
- `_run_manifest.json` — configuration, data coverage, valuation conventions, output row counts, reconciliation results and data-quality warnings for the run
- `_portal.html` — the reporting portal (see below; skip with `portal.enabled=false`)

## Momentum Baseline

The rotation engine's Phase 0 reference reports are stored in
`rotation-engine/baseline/momentum/`. The fixed window trades from 2024-01-01
through 2026-09-30; the long window starts 2010-01-01 and has the same end date.
Both pinned configs retain the current momentum settings and use the daily stock
files in `stocks/daily/`.

From `rotation-engine/`, regenerate either window with:

```bash
mvn test
java -cp target/classes com.rotation.Main --config baseline/momentum/fixed.properties
java -cp target/classes com.rotation.Main --config baseline/momentum/long.properties
```

The comparison utility checks equity, tradebook, holdings, trade ledger, daily
portfolio, yearly, and rebalance CSVs. It validates headers and column order,
row counts, exact text fields, and numeric values within `1e-9`:

```bash
python3 ../scripts/rotation-engine/BaselineCompare.py compare \
    baseline/momentum/fixed-window output/baseline-capture-fixed
python3 ../scripts/rotation-engine/BaselineCompare.py compare \
    baseline/momentum/long-window output/baseline-capture-long
```

Run manifests and portals are not baseline-compared: manifests include run-time
metadata, and portals embed the full market history.

## Reporting portal

`rotation_portal.html` is a single, read-only HTML file written by every engine run (default and
`daily-refresh`). All data and the chart library are embedded, so it opens offline straight from disk:
no server, no CDN, no CSV fetching. It is a reporting view only: no trading, no live data, no
parameter optimization.

```bash
cd rotation-engine
mvn -q compile && java -cp target/classes com.rotation.Main   # writes CSVs + manifest + portal
open output/rotation/rotation_portal.html                     # macOS; any modern browser works
```

The file is ~14 MB for 177 symbols × 16 years. It embeds full-history adjusted OHLC for every symbol.

**Toolbar** (applies to every view): *As of* date, range presets (30 calendar days, 30 trading
sessions, MTD, YTD, All), a custom *From* date (the range always ends on the as-of date), and a symbol
filter. State is kept in the URL hash, so a view can be bookmarked.

**Views:** Overview (equity, cash, invested, P&L split, drawdown, equity curve, daily returns), Tradebook
(sortable/filterable fills with exit reasons), Holdings (positions at the as-of close with cost, value,
unrealized P&L, weight, sector), Stocks (adjusted candles with entry/exit/stop markers, relative
performance vs. other symbols or the portfolio), Performance (preset and custom ranges, monthly grid,
yearly results, largest drawdowns), Rankings (rankings and decisions at each rebalance, plus the daily
lookback ranking for the last 30 sessions), Run details (configuration, coverage, checks, warnings).
Each panel is badged **As of** (end-of-day state on the as-of date) or **Range** (the selected range).
Clicking a trade, holding or ranking row opens its stock chart. Every table exports its filtered rows to CSV.

**Definitions** (all computed in Java; the browser only compounds and differences the engine's daily figures):

| Term | Definition |
|------|------------|
| As-of date | End of day: every execution at that session's open has happened (stop fills from the prior close, then the rebalance), valued at that session's adjusted close. A non-trading date resolves to the previous session, and the effective date is shown. Nothing after it (trades, prices, rankings) is displayed. |
| Signal vs. execution date | Rankings use the signal session's close; trades execute at the next session's open. Stops trigger on a close and fill at the next open. |
| Account equity | The engine's mark-to-market equity. It always reconciles to cash + Σ quantity × adjusted close. |
| Contributions | External cash (`monthly.contribution`), credited at a rebalance open. Excluded from P&L and returns. |
| Daily return | `equity / (previous equity + same-day contribution) − 1`: time-weighted and contribution adjusted. |
| Range return | Daily returns chained over the sessions in the range, measured from the close before the range (or initial capital at inception). Range P&L = end equity − start equity − contributions. |
| Drawdown | Chained (TWR) index vs. its running peak. |
| Realized / unrealized P&L | Average cost, incl. rebalance re-weights, so realized + unrealized = total P&L = equity − initial capital − contributions. The tradebook's `realized_pnl` ((exit − first entry price) × exit quantity) is kept as `pnl_vs_entry_price`. |
| Partial / insufficient history | Months and years that were not invested for the whole period, or that end at the as-of date, are marked partial (†). Ranges that need history from before the backtest start are flagged *insufficient history*. Annualized figures need ≥ 1 year; volatility needs ≥ 20 sessions. |

**Validation:** each run reconciles the daily reports against the existing ones and fails loudly on a
mismatch. Checks: row counts, unique date/symbol keys, equity = cash + invested, the P&L split,
year-end marks vs. `_yearly.csv`, rebalance-open marks vs. `_rebalances.csv`, the final mark vs.
`_holdings.csv`, ledger vs. `_tradebook.csv`, and every fill executing after its signal. Results are
in the manifest and the Run details view.

**Limitations:**
- Volume is retained in the daily snapshot; the portal does not currently chart it.
- Prices are embedded rounded to 0.01 (0.001 / 0.0001 for symbols trading below 10 / 1), for display only. Valuations come from the engine at full precision.
- Forward-filled bars in the source data can't be told apart from real ones. Bars identical to the prior session are flagged as possibly stale.
- A held position with no close is valued at zero (engine convention) and flagged.
- The daily lookback ranking is exported only for the last 30 sessions; rebalance rankings cover the full history.
- Yearly *engine* rows treat contributions at year end and report XIRR; the TWR column is shown alongside. They are equal when there are no contributions.

## Development

```bash
cd rotation-engine
mvn test                                        # Java unit + engine tests
node --test src/test/js/portal-metrics.test.js  # portal range arithmetic (Node ≥ 18, no packages);
                                                # [generated] tests also check output/rotation/ after a run
```

See [rotation-engine/README.md](rotation-engine/README.md) for engine internals and [.github/copilot-instructions.md](.github/copilot-instructions.md) for development rules.
