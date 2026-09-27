# Stocks Data — Daily Market Snapshot & Rotation Strategy Engine

End-to-end pipeline: download NSE daily OHLCV data, normalize it into market snapshots, and run a momentum rotation backtest.

## Data flow

```
symbols.csv
    ↓  download_nse_data.py (yfinance)
stocks/daily/{SYMBOL}.csv
    ↓  rotation-engine: MinuteHistoryDailyBarLoader → DailyBars
filter (start.date / end.date / symbols.file)
    ↓  MarketSnapshotExporter
rotation_daily_market_snapshot.csv   ← single source of truth
    ↓  SnapshotDailyBarLoader (reload)
    ├─→ MonthlyMarketSnapshotExporter → rotation_monthly_market_snapshot.csv
    └─→ RotationEngine → CsvExporter → rebalances / equity / performance /
                                        tradebook / holdings / lookback / yearly
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
        │   ├── Main.java              ← Entry point / orchestration
        │   ├── config/                ← RotationConfig
        │   ├── data/                  ← Loaders (MinuteHistory, Snapshot)
        │   ├── engine/                ← RotationEngine
        │   ├── job/                   ← DailyRefreshJob
        │   ├── model/                 ← Immutable data models (DailyBars, …)
        │   └── report/                ← CSV exporters
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
| `start.date` / `end.date` | Optional date window |
| `symbols.file` | Optional CSV with a `symbol` column to restrict the universe |
| `sector.file` / `max.per.sector` | Sector diversification cap (default `../symbols.csv`) |
| `lookback.days` | Momentum lookback in trading sessions |
| `top.n` / `exit.n` | Names entered / rank threshold before exit |
| `rebalance.mode` | `monthly`, `weekly`, or `monthly_twice` |
| `capital.per.stock`, `allocation.mode`, `monthly.contribution` | Sizing |
| `stop.loss.pct` / `trailing.stop.pct` | Intra-period exits |
| `min.history.days` | Eligibility (engine enforces `max(min.history.days, lookback.days)`) |
| `output.dir` / `output.prefix` | Report location and file prefix |

## Outputs

All in `rotation-engine/output/rotation/` with prefix `rotation`:

- `_daily_market_snapshot.csv` — one row per symbol per trading day (source of truth)
- `_monthly_market_snapshot.csv` — one row per symbol per month
- `_rebalances.csv` — one row per rebalance
- `_equity.csv` — equity curve
- `_performance.csv` — ranked universe per rebalance
- `_tradebook.csv` — entries and exits
- `_holdings.csv` — positions per period
- `_lookback.csv` — latest lookback ranking
- `_yearly.csv` — calendar-year returns

## Development

```bash
cd rotation-engine
mvn test
```

See [rotation-engine/README.md](rotation-engine/README.md) for engine internals and [.github/copilot-instructions.md](.github/copilot-instructions.md) for development rules.
