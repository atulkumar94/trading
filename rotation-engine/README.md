# Rotation Engine

This project is a daily market snapshot and reporting engine for stock data.

In simple terms, it does this:

1. Reads historical daily stock data from the configured folder.
2. Builds a clean daily OHLCV dataset for each symbol.
3. Applies a start date filter, so you can run from a specific historical date onward.
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
- filter from a configured start date
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

### 3) Date filtering

The project supports a configurable `start.date`.

When set, the system keeps only data from that date onward. This matters for:

- historical backfills
- running a specific historical window
- daily refresh jobs that should start from a chosen point in time

So the engine does not have to process the entire dataset if you want only a slice of history.

### 4) Snapshot export

After loading and filtering the history, the engine creates snapshot outputs in the output folder.

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

1. load all historical stock data
2. filter from the configured start date
3. rebuild the daily snapshot
4. rebuild the monthly snapshot
5. save the files in the configured output folder

This is a daily batch job, not an event-driven system.

A typical daily refresh is simple:

- run once a day
- read the latest historical files
- rebuild the snapshots from the configured date onward
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

## How to run it

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
- `RotationEngine.java` — core backtesting logic
- `MinuteHistoryDailyBarLoader.java` — loads external daily/history CSVs
- `CsvExporter.java` — writes CSV reports

---

## In one sentence

This project is a historical backtesting engine that repeatedly ranks stocks by recent momentum, picks the top ones on a fixed schedule, and records how the portfolio performs over time.

---

## Example of the strategy rule

A simple description of the strategy in human terms:

> Every 13 trading days, look at the last 30 days of performance for all stocks, choose the best-performing stock, buy it at the next open, keep it until the next rebalance, and repeat.

That is exactly the kind of systematic rule this engine tests.
