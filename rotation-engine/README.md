# Rotation Engine

This project is a daily market snapshot and reporting engine for stock data.

In simple terms, it does this:

1. Reads historical daily stock data from the configured folder.
2. Builds a clean daily OHLCV dataset for each symbol.
3. Keeps the full history for lookback warm-up and starts trading from the configured start date.
4. Exports a daily market snapshot with one row per symbol per day.
5. Builds a monthly market snapshot with one row per symbol per month.
6. Can also be run as a dedicated daily refresh job to regenerate the snapshot files from the latest historical data.

This is not a trade-execution engine anymore. It is a market-data preparation and reporting engine.

---

## What this project is for

The main goal is to create data snapshots from stock history so you can analyze:

- daily price movement
- return vs previous close
- monthly rollups
- yearly aggregate summaries
- a standard market snapshot output for downstream analytics

The engine is designed around a simple idea:

- load the raw stock history
- normalize the data into daily bars
- trade from a configured start date (earlier history warms up the lookback)
- export daily and monthly snapshots in CSV format

---

## Data flow

### 1) Configuration

The project reads settings from [config/rotation.properties](config/rotation.properties).

This file controls:

- the data source
- the stock data path
- the start date
- the output folder
- the output file prefix
- optional symbol filtering
- optional single-sector universe (`market.sector`)
- the strategy to run (`strategy`, default `momentum`)

The most important setting here is the input path:

```text
../stocks/daily   (repo-root stocks/daily, relative to rotation-engine/)
```

That folder contains historical daily stock CSV files.

### 2) Data loading

The loader reads each stock file and turns it into daily OHLCV records.

Each symbol file is normalized into a dataset with fields such as:

- date
- open
- high
- low
- close
- volume

This is done in the classes under the data package and then converted into a single daily matrix using the bar model.

### 3) Start date and lookback warm-up

The project supports a configurable `start.date`. It is the date the backtest
**starts trading**, not a data filter.

All available history (up to `end.date`) is always loaded and kept, so on the
start date the lookback window and the min-history eligibility counts are
already filled from the sessions before it:

- the first rebalance executes at the open of the first session on/after `start.date`
  (ranked on the prior session's close), then the `rebalance.mode` cadence follows
- no trades, equity rows or yearly rows are produced before `start.date`
- if `start.date` is at (or before) the start of the history, there is nothing to warm up
  from, so the first rebalance waits until the lookback is complete (see below)

### Rebalance schedule

Rebalances are counted in **trading sessions**, not calendar dates:

1. **First rebalance:** the earliest session (no earlier than `start.date`) whose
   prior session has a full `lookback.days` window and at least one eligible symbol.
2. **After that:** every N trading sessions, set by `rebalance.mode`:

| `rebalance.mode` | Rebalance every |
|------------------|-----------------|
| `weekly`         | 5 trading sessions |
| `monthly_twice`  | 10 trading sessions (default) |
| `monthly`        | 20 trading sessions |

Each decision is ranked on the signal session's close and executed at the next
session's open. Example: history starts 2010-01-04 and `lookback.days=90`, so the
90th session (2010-05-14) is the first signal, the first trade is at the open of
2010-05-17, and with `monthly` the next is 20 sessions later (2010-06-14).

Because the cadence ignores calendar months, a month can occasionally have no
rebalance; `monthly.contribution` still credits every elapsed month.

`end.date` is still a hard filter: data after it is dropped.

### Universe filters

After loading, the universe can be narrowed (applied in this order, in both the
default and daily refresh modes):

- `symbols.file` — keep only symbols listed in its `symbol` column
- `market.sector` — keep only symbols whose `sector` in `sector.file` matches
  (case-insensitive), e.g. `market.sector=Healthcare`. Requires `sector.file`;
  the run fails if no symbol has that sector. Because every remaining name shares
  one sector, `max.per.sector` is ignored while this is set.

Snapshots and backtest outputs then cover only the filtered symbols.

### 4) Snapshot export

After loading the history, the engine creates snapshot outputs in the output folder.
The daily and monthly snapshots cover the full loaded history (including the warm-up
period before `start.date`), because the engine reads its bars from the daily snapshot.

Typical outputs include:

- rotation_daily_market_snapshot.csv
- rotation_monthly_market_snapshot.csv
- rotation_yearly.csv
- rotation_equity.csv
- rotation_performance.csv
- rotation_rebalances.csv
- rotation_tradebook.csv
- rotation_lookback.csv

The daily output has one row per symbol per trading day.
The monthly output has one row per symbol per month.

---

## Daily refresh job

The project also includes a dedicated refresh job designed for scheduled runs.

This is the intended operational model:

1. load all historical stock data (up to `end.date`)
2. trade from the configured start date, using earlier history as lookback warm-up
3. rebuild the daily snapshot
4. rebuild the monthly snapshot
5. save the files in the configured output folder

This is a daily batch job, not an event-driven system.

A typical daily refresh is simple:

- run once a day
- read the latest historical files
- rebuild the snapshots from the full history and the backtest from the configured date onward
- overwrite the current output files

This matches the requirement of a daily market snapshot system.

---

## Execution flow

There are two main modes of execution:

### Default mode

Running the application normally creates the snapshot outputs from the configured data range.

Example:

```bash
mvn exec:java -Dexec.mainClass=com.rotation.Main
```

### Daily refresh mode

This is the dedicated job mode for the market snapshot workflow.

Example:

```bash
mvn -q compile
java -cp target/classes com.rotation.Main daily-refresh
```

The same job also runs through the main entry point with the refresh flag if needed.

---

## Why this is a snapshot engine

This project is built around the idea of generating readable market-level tables, not portfolio-backtest decisions.

The core data shape is:

- date
- symbol
- previous close
- open
- high
- low
- close
- return vs previous close

That makes it easy to:

- compare symbols across dates
- aggregate by month or year
- feed downstream dashboards
- run calendar-based market summaries

---

## Important distinction

The older rotation/backtest logic is not the primary goal anymore.

The active goal is:

- market snapshot generation
- daily refresh behavior
- monthly snapshot rollups
- yearly reporting summaries
- data preparation for a scheduled market monitoring pipeline

So the current architecture is intentionally a daily reporting engine with a refresh workflow, rather than a strategy execution engine.

The portfolio value may rise or fall, but the base capital used for each position does not expand automatically.

---

## Eligibility rule

The engine does not allow a stock to be selected before it has enough history.

It enforces:

```text
max(min.history.days, lookback.days)
```

This means a stock must have at least as much tracked history as the lookback requirement before it becomes eligible.

This prevents using a stock with too little historical data and avoids meaningless rankings.

---

## Data flow summary

The end-to-end process looks like this:

```text
CSV stock data
    ↓
Data loader
    ↓
Daily candle builder
    ↓
Universe ranking by momentum
    ↓
Position selection and rebalance
    ↓
P&L + equity calculation
    ↓
CSV output reports
```

---

## What the engine exports

The project writes reports to `rotation-engine/output/rotation` (`output.dir`, relative to `rotation-engine/`).

### 1) rebalance log

`rotation_rebalances.csv`

This contains one row per rebalance with fields such as:

- rebalance date
- signal date
- portfolio value before
- portfolio value after
- account equity
- period P&L
- selected count
- entered symbols
- exited symbols
- held symbols
- period return %

### 2) equity curve

`rotation_equity.csv`

This is the equity curve showing how the portfolio value changes over time.

### 3) performance table

`rotation_performance.csv`

This shows the ranked universe for each rebalance, including:

- rank
- symbol
- lookback price
- current price
- lookback return %
- whether the symbol was selected

### 4) yearly report

`rotation_yearly.csv`

This aggregates the results by calendar year and shows:

- year
- start date
- end date
- start equity
- end equity
- annual P&L
- yearly return %
- rebalance count

Year boundaries are struck at each calendar year's last trading-day close (the
latest available session for the still-running final year), with the book marked
to market on that day. Consecutive years chain end-to-start, so a return is never
mis-attributed to whichever rebalance date happened to sit nearest the year end.

This is especially useful when you want to see multi-year performance in a compact way.

A year-end mark replays only the stops triggered by closes *before* the year-end session (those fill
at or before its open). A stop triggered by the year-end close itself fills at the next open, so the
position is still valued at the year-end close. No future open price leaks into the yearly report.

### 5) tradebook

`rotation_tradebook.csv`

This contains one row per entry or exit trade with:

- trade date
- signal date
- rebalance number
- action
- symbol
- quantity
- trade price
- trade value
- entry price
- exit price
- realized P&L

### 6) latest lookback view

`rotation_lookback.csv`

This contains the latest 30 trading-day lookback rankings using the current run parameters, including:

- signal date
- next execution date when available
- lookback start date
- rank
- symbol
- lookback price
- current price
- lookback return %
- history days
- whether the symbol falls inside the configured top N

---

### 7) daily reports, run manifest and portal

`DailyReportJob` runs after the CSV exports in both modes:

- `RotationEngine` records every fill (`LedgerFill`: ENTRY, ADD/TRIM re-weights, EXIT, STOP, DROP)
  and an end-of-day `DailyMark` per session (book after that session's open executions, engine
  mark-to-market equity at its close).
- `DailyValuationBuilder` replays the fills on an independent cash ledger with average-cost accounting
  and writes `rotation_daily_portfolio.csv`, `rotation_daily_positions.csv` and `rotation_trade_ledger.csv`.
  If the replayed book diverges from the engine's book on any day, it throws.
- `ReportReconciler` checks the new reports against `rotation_rebalances.csv`, `rotation_holdings.csv`,
  `rotation_yearly.csv` and `rotation_tradebook.csv`. A failed check stops the run after the files are
  written, so they can be inspected.
- `PerformanceCalculator` computes the range presets embedded in `rotation_run_manifest.json`. The
  portal recomputes them in the browser and shows the cross-check in *Run details*.
- `PortalExporter` writes `rotation_portal.html` from `src/main/resources/portal/` (template, CSS,
  `portal-metrics.js`, `portal.js` and the vendored Lightweight Charts 5.2.1, Apache-2.0).

See the root README's *Reporting portal* section for metric definitions and limitations.

## Pluggable strategy

The **signals** — which symbols to hold, when to rebalance, and (optionally) when to
stop out — are isolated behind a small interface so you can swap the logic without
touching the engine. The engine owns everything mechanical: next-open execution,
capital accounting, running the exit policy, and all reporting. The strategy owns the
rebalance schedule, ranks the universe, picks the book each period, and decides whether
to supply an exit policy at all.

The interface lives in the `strategy` package:

```java
public interface RotationStrategy {
    String name();

    // Rebalance schedule: signal indices (execution is at signalIdx + 1).
    List<Integer> rebalanceSignals(DailyBars bars, int tradeStartIdx, int minHistory);
    default String scheduleLabel() { return name() + "-schedule"; }

    // Selection: rank the universe, then pick the book.
    List<Candidate> rank(DailyBars bars, int signalIdx, int referenceIdx, int minHistory);
    List<String> selectCore(List<Candidate> ranked);              // always-entered core (top-N)
    List<String> select(List<Candidate> ranked, Set<String> held); // full book incl. exit buffer

    // Optional intra-period exit rule. Strategies that don't stop out keep the default.
    default ExitPolicy exitPolicy() { return ExitPolicy.NONE; }
}
```

Exit criteria are therefore **strategy-owned but optional**: a strategy returns an
`ExitPolicy` (e.g. `StopLossExitPolicy` for hard/trailing stops) when it wants intra-period
exits, or leaves the `ExitPolicy.NONE` default when it does not. The engine simply runs
whatever policy it is given.

The default implementation is `MomentumRotationStrategy` (trailing-lookback-return
ranking with the optional sector cap and exit buffer, a fixed-session rebalance cadence
from `rebalance.mode`, and a `StopLossExitPolicy` built from `stop.loss.pct` /
`trailing.stop.pct`). It is selected by the config key:

```text
strategy=momentum
```

### Plugging in a new strategy

There are two seams, at different levels:

- **Top-level `Strategy`** (`com.rotation.strategy.Strategy`) — a whole, self-contained
  strategy that takes the daily candles and runs a full backtest (`run(DailyBars)`).
  This is the plug point the pipeline dispatches to after the candles are built. The
  momentum rotation strategy is `RotationEngine` (it owns the rotation machinery); a
  different idea (e.g. a breakout) implements `Strategy` directly and shares none of it.
- **Rotation's `RotationStrategy`** — the *selection* layer used only *inside* the
  rotation strategy (schedule + ranking + which symbols to hold, with an optional
  `exitPolicy()`). This only matters if you are varying the rotation strategy itself.

To add a brand-new strategy (no rotation):

1. Implement `Strategy` in `com.rotation.strategy`, reading its own tunables from the
   `RotationConfig` it is constructed with.
2. Register it in `Strategies.create(...)` under a new `strategy` name.
3. Set `strategy=<yourname>` in [config/rotation.properties](config/rotation.properties) and run.

To vary the rotation strategy's selection logic instead, implement `RotationStrategy`,
register it in `RotationStrategies.create(...)`, and inject it for quick experiments:

```java
BacktestResult result = new RotationEngine(config, new MySelection(config)).run(bars);
```

Because the top-level seam only swaps which `Strategy` runs, every output CSV, the daily
reports, and the portal keep working unchanged.

### The 10 DMA breakout strategy (`strategy=breakout`)

A second, self-contained `Strategy` that shares none of the rotation machinery. It lives in
`com.rotation.strategy.breakout` and reads only `breakout.*` keys from
[config/rotation.properties](config/rotation.properties).

- **Entry** (evaluated at the close, filled at the *next* open): the close crosses above
  SMA10. Optional, individually toggleable quality filters: trend (close > SMA50 and SMA200
  with a rising SMA50), ADX(14) above a floor, cross-day volume above its trailing average,
  and a minimum traded-value liquidity screen. Ties are ranked by the stock's own trailing
  return.
- **Exit** (strategy-owned): close below SMA10, far-below / consecutive-closes-below SMA10,
  SMA10 crossing below SMA20, an ATR trailing stop, and an intraday hard stop (gap-downs
  fill at the open). An optional partial take-profit trims at a configured R multiple.
- **Sizing**: `breakout.capital`, a `breakout.max.positions` concurrency cap, a
  `breakout.max.weight.pct` per-position weight cap, and `breakout.cost.bps` baked into the
  fill price.

It produces the same CSV outputs and portal as momentum, except the per-rebalance
cross-sectional *rankings* and *lookback* reports are empty (the breakout book has no
cross-sectional ranking table analog).

### EOD breakout scanner (`mode=scan`)

Setting `mode=scan` runs a read-only, stateless scan over the **latest available session**
instead of a backtest. It applies the breakout entry rules (always using the `breakout.*`
config, regardless of the `strategy` key) and reports every symbol whose close crossed above
SMA10, flagging which ones clear all enabled filters (a full entry signal) and why the rest
were rejected. It writes `{output.prefix}_scan.csv` and prints a console summary. No trades
are placed — a signal would fill at the next open, exactly as in the backtest, so the output
is a next-session watchlist.

`{output.prefix}_scan.csv` columns: `date, symbol, is_entry, trend_ok, adx_ok, volume_ok,
liquidity_ok, close, sma_fast, sma_mid, sma_slow, adx, atr, volume, avg_volume,
avg_traded_value, rank_score`. Rows are full entries first, then by `rank_score`.



From the project root:

```bash
mvn exec:java -Dexec.mainClass=com.rotation.Main
```

This will:

- load config,
- read the stock data,
- run the backtest,
- save the CSV outputs.

---

## Main project files

- `Main.java` — entry point
- `RotationConfig.java` — loads all settings
- `strategy/Strategy.java` + `Strategies.java` — the top-level plug point: a whole strategy run after the candles are built
- `RotationEngine.java` — the momentum rotation `Strategy`: core backtesting logic (next-open execution, accounting, running the exit policy, reports)
- `strategy/RotationStrategy.java` + `MomentumRotationStrategy.java` + `RotationStrategies.java` — rotation's internal signal layer (schedule + rank + pick the book)
- `strategy/ExitPolicy.java` + `StopLossExitPolicy.java` — optional, strategy-supplied intra-period exit rule
- `MinuteHistoryDailyBarLoader.java` — loads external daily/history CSVs
- `CsvExporter.java` — writes CSV reports
- `DailyValuationBuilder.java` / `ReportReconciler.java` / `PerformanceCalculator.java` — daily accounting, reconciliation, range metrics
- `DailyReportExporter.java` / `RunManifestBuilder.java` / `PortalExporter.java` — daily CSVs, run manifest, HTML portal
- `job/DailyReportJob.java` — orchestrates the daily reports after `CsvExporter`

---

## In one sentence

This project is a historical backtesting engine that repeatedly ranks stocks by recent momentum, picks the top ones on a fixed schedule, and records how the portfolio performs over time.

---

## Example of the strategy rule

A simple description of the strategy in human terms:

> Every 13 trading days, look at the last 30 days of performance for all stocks, choose the best-performing stock, buy it at the next open, keep it until the next rebalance, and repeat.

That is exactly the kind of systematic rule this engine tests.
